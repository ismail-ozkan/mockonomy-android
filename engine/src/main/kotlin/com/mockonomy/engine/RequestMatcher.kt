package com.mockonomy.engine

import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.ResponseMode
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.SequentialBehavior
import com.mockonomy.engine.template.TemplateContext
import com.mockonomy.engine.template.TemplateEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Matches an incoming request against an environment definition.
 *
 * Behavior contract: `spec/behavior/matching.md`. This class implements that
 * document; behavior changes are settled there first.
 *
 * Covers path parameters (`:id`), specificity ordering, percent-decoding,
 * response rules, sequential responses and the state machine. The selected body
 * is rendered by the template engine (`spec/behavior/templating.md`).
 *
 * The class is stateless and [Environment] is immutable, so a running server can
 * switch to a new configuration object without locking (hot reload).
 */
class RequestMatcher {

    /**
     * Returns the matched response and the captured path parameters, or `null` if nothing matched.
     *
     * [templates] is only used for `{{ ... }}` expressions in the body; it defaults to the
     * system clock and randomness. Tests pass a fixed context to pin the output
     * (`spec/behavior/templating.md`).
     */
    fun match(
        environment: Environment,
        request: MockRequest,
        templates: TemplateContext = TemplateContext.system(),
        session: MockSession = MockSession(),
    ): MatchResult? = resolve(environment, request, templates, session, trace = null)

    /**
     * Returns the final response to serve — the default 404 when nothing matched.
     *
     * The server may forward unmatched requests to a proxy target before this
     * fallback is used.
     */
    fun serve(
        environment: Environment,
        request: MockRequest,
        templates: TemplateContext = TemplateContext.system(),
        session: MockSession = MockSession(),
    ): MockResponse = match(environment, request, templates, session)?.response ?: notFound(request)

    /**
     * Returns the same response as [serve], together with **the reason** for it.
     *
     * The trace is filled even when nothing matched: "why did I get a 404?" is the
     * most common question, and it can only be answered if the attempted routes
     * were recorded.
     *
     * Collecting a trace costs something; [serve] never pays that cost
     * (`spec/behavior/rules.md` §5).
     */
    fun explain(
        environment: Environment,
        request: MockRequest,
        templates: TemplateContext = TemplateContext.system(),
        session: MockSession = MockSession(),
    ): TracedResponse {
        val builder = TraceBuilder()
        val result = resolve(environment, request, templates, session, builder)
        return TracedResponse(
            response = result?.response ?: notFound(request),
            trace = builder.build(),
        )
    }

    /**
     * The single decision path. With [trace] set to `null` the behavior is
     * identical — the trace never changes the decision, it only records it.
     */
    private fun resolve(
        environment: Environment,
        request: MockRequest,
        templates: TemplateContext,
        session: MockSession,
        trace: TraceBuilder?,
    ): MatchResult? {
        val effectivePath = stripPrefix(environment.prefix, request.path)
        trace?.effectivePath(effectivePath)
        if (effectivePath == null) return null

        val routeMatch = findRoute(environment, request.method, effectivePath, trace) ?: return null

        // A route matched: increment its counter here, once, **before** response
        // selection. The same number feeds both the SEQUENTIAL index and the
        // REQUEST_NUMBER rule; it must not be incremented twice
        // (spec/behavior/sequential-response.md).
        val requestNumber = session.counters.next(routeMatch.route.id)
        trace?.requestNumber(requestNumber)

        // The active state is read once for the whole selection; setState is applied
        // *after* selection (spec/behavior/state-machine.md).
        val activeState = session.state
        trace?.activeState(activeState)

        // A forced override is the strongest selector: if the pinned response still
        // exists on the route, rule/sequential/state selection is skipped entirely. If
        // the response was deleted (hot reload) the override is ignored and normal
        // selection applies (spec/behavior/response-override.md).
        val forced = session.overriddenResponse(routeMatch.route.id)
            ?.let { forcedId -> routeMatch.route.responses.firstOrNull { it.id == forcedId } }
        forced?.let { trace?.forcedResponse(it.id) }

        val response = forced
            ?: selectResponse(routeMatch, request, requestNumber, activeState, trace)
            ?: return null

        rejectUnsupported(routeMatch.route, response)

        // Response selected: apply setState now (right before the socket write; for a
        // sequential test flow this is observably the same as "after the write").
        if (response.setState != null) {
            session.transition(response.setState)
            trace?.appliedSetState(response.setState)
        }

        val mockResponse = MockResponse(
            statusCode = response.statusCode,
            headers = mergeHeaders(environment.headers, response.headers),
            body = TemplateEngine.render(response.body, request, routeMatch.pathParams, templates),
            latencyMs = environment.defaultLatencyMs + response.latencyMs,
        )
        return MatchResult(mockResponse, routeMatch.pathParams)
    }

    // --- spec/behavior/matching.md §1 — prefix stripping ---

    /** `null` if the prefix does not match, otherwise the remaining effective path. */
    private fun stripPrefix(prefix: String, path: String): String? {
        val prefixSegments = splitPath(prefix)
        if (prefixSegments.isEmpty()) return path

        val pathSegments = splitPath(path)
        if (pathSegments.size < prefixSegments.size) return null
        for (index in prefixSegments.indices) {
            if (pathSegments[index] != prefixSegments[index]) return null
        }
        return "/" + pathSegments.drop(prefixSegments.size).joinToString("/")
    }

    // --- spec/behavior/matching.md §2 and §4 — candidate routes and specificity ---

    private class RouteMatch(
        val route: Route,
        val pathParams: Map<String, String>,
        val patternSegments: List<String>,
    )

    /**
     * Picks the **most specific** route among the candidates whose method and path
     * match. On a tie the first route in `environment.routes` wins — so only a
     * *strictly* more specific candidate replaces the current best.
     */
    private fun findRoute(
        environment: Environment,
        method: String,
        path: String,
        trace: TraceBuilder?,
    ): RouteMatch? {
        val normalizedMethod = method.uppercase()
        val pathSegments = splitPath(path).map(::percentDecode)

        var best: RouteMatch? = null
        for (route in environment.routes) {
            if (!route.enabled) {
                trace?.route(route, SkipReason.DISABLED)
                continue
            }
            if (route.method.name != normalizedMethod) {
                trace?.route(route, SkipReason.METHOD_MISMATCH)
                continue
            }

            val patternSegments = splitPath(route.path)
            val params = matchSegments(patternSegments, pathSegments)
            if (params == null) {
                trace?.route(route, SkipReason.PATH_MISMATCH)
                continue
            }

            // Matched; whether it wins is known once the specificity comparison is done.
            trace?.route(route, skipReason = null)
            val current = best
            if (current == null || compareSpecificity(patternSegments, current.patternSegments) > 0) {
                best = RouteMatch(route, params, patternSegments)
            }
        }
        trace?.routesResolved(best?.route?.id)
        return best
    }

    /**
     * Matches pattern segments against actual (already percent-decoded) segments.
     * `:name` is a path parameter and always captures the corresponding segment;
     * everything else is compared literally and case-sensitively. Returns `null`
     * if the segment counts differ.
     */
    private fun matchSegments(patternSegments: List<String>, pathSegments: List<String>): Map<String, String>? {
        if (patternSegments.size != pathSegments.size) return null

        val params = mutableMapOf<String, String>()
        for (index in patternSegments.indices) {
            val pattern = patternSegments[index]
            val actual = pathSegments[index]
            if (pattern.startsWith(":")) {
                params[pattern.substring(1)] = actual
            } else if (pattern != actual) {
                return null
            }
        }
        return params
    }

    /**
     * Compares the specificity of two patterns. Only meaningful for patterns that
     * match the same request: at the first position (left to right) where they
     * differ, a literal segment (not starting with `:`) beats a path parameter.
     * `> 0` → [a] is more specific.
     */
    private fun compareSpecificity(a: List<String>, b: List<String>): Int {
        for (index in a.indices) {
            val aIsStatic = !a[index].startsWith(":")
            val bIsStatic = !b[index].startsWith(":")
            if (aIsStatic != bIsStatic) return if (aIsStatic) 1 else -1
        }
        return 0
    }

    /**
     * Leading and trailing empty segments are dropped, which makes a trailing slash
     * meaningless (`/users` and `/users/` are the same). Percent-encoding is NOT
     * decoded here — only actual request segments go through [percentDecode];
     * route patterns stay as they are.
     */
    private fun splitPath(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }

    /**
     * Decodes `%XX` sequences into raw bytes and reassembles them as UTF-8.
     * `java.net.URLDecoder` is deliberately not used: it turns `+` into a space
     * (form-encoding semantics), which is wrong for path segments.
     */
    private fun percentDecode(segment: String): String {
        if ('%' !in segment) return segment

        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < segment.length) {
            val char = segment[index]
            if (char == '%' && index + 3 <= segment.length) {
                val byte = segment.substring(index + 1, index + 3).toIntOrNull(16)
                if (byte != null) {
                    bytes.add(byte.toByte())
                    index += 3
                    continue
                }
            }
            bytes.addAll(char.toString().toByteArray(Charsets.UTF_8).toList())
            index++
        }
        return bytes.toByteArray().toString(Charsets.UTF_8)
    }

    // --- spec/behavior/matching.md §5 — response selection ---

    /**
     * 1. Responses **with** rules are tried in order; the first whose rules pass wins
     * 2. If none passes, the response marked `isDefault`
     * 3. If there is none, the first response in the list
     *
     * A response without rules does not take part in step 1. If it did, it would
     * be a response that "always passes", and "Switch response" would break: the
     * user would pick the second response and still always get the first one.
     * Adding a rule engine must not change behavior for people who write no rules
     * (`spec/behavior/matching.md` §5).
     */
    private fun selectResponse(
        routeMatch: RouteMatch,
        request: MockRequest,
        requestNumber: Int,
        activeState: String?,
        trace: TraceBuilder?,
    ): RouteResponse? {
        // SEQUENTIAL: rules AND whenState are ignored; responses are returned in
        // exact list order (spec/behavior/sequential-response.md). Users who need
        // rule- or state-based ordering use REQUEST_NUMBER.
        if (routeMatch.route.responseMode == ResponseMode.SEQUENTIAL) {
            return selectSequential(routeMatch.route, requestNumber, trace)
        }

        // whenState is an implicit pre-rule: responses that do not match the current
        // state are removed from the candidate list entirely — they take part in
        // neither rule-based selection nor the fallback (spec/behavior/state-machine.md).
        val eligible = routeMatch.route.responses.filter { response ->
            val ok = response.whenState == null || response.whenState == activeState
            if (!ok) trace?.stateSkip(response.id, response.whenState, activeState)
            ok
        }

        for (response in eligible) {
            if (response.rules.isEmpty()) continue
            if (RuleEvaluator.passes(response, request, routeMatch.pathParams, requestNumber, trace)) {
                trace?.response(response.id, fallback = false)
                return response
            }
        }

        val fallback = eligible.firstOrNull { it.isDefault } ?: eligible.firstOrNull()
        trace?.response(fallback?.id, fallback = true)
        return fallback
    }

    /**
     * Sequential selection: the Nth request gets the Nth response (1-based). What
     * happens after the end of the list depends on [Route.sequentialBehavior]:
     * - `STAY_ON_LAST` *(default)* — stays on the last response (polling: the
     *   scenario that should settle on its final state)
     * - `CYCLE` — starts over from the beginning
     *
     * `isDefault` and rules play no role in this mode.
     */
    private fun selectSequential(route: Route, requestNumber: Int, trace: TraceBuilder?): RouteResponse? {
        val size = route.responses.size
        if (size == 0) {
            trace?.response(null, fallback = true)
            return null
        }
        val index = when (route.sequentialBehavior) {
            SequentialBehavior.STAY_ON_LAST -> (requestNumber - 1).coerceAtMost(size - 1)
            SequentialBehavior.CYCLE -> (requestNumber - 1) % size
        }
        val response = route.responses[index]
        trace?.sequentialIndex(index)
        trace?.response(response.id, fallback = false)
        return response
    }

    /**
     * Explicit error for features the schema defines but the engine does not support
     * yet. Silently behaving differently would send the user hunting for a bug in
     * the wrong place for hours.
     *
     * Only the *selected* route and response are checked; an unrelated unsupported
     * route sitting in the environment must not break other requests.
     */
    private fun rejectUnsupported(route: Route, response: RouteResponse) {
        // RULES and SEQUENTIAL are supported; RANDOM is not yet.
        if (route.responseMode == ResponseMode.RANDOM) {
            throw UnsupportedMockFeatureException(
                "responseMode=${route.responseMode} is not supported yet (planned for phase 4+). " +
                    "Route: ${route.method} ${route.path}",
            )
        }
        if (response.bodyType != BodyType.INLINE) {
            throw UnsupportedMockFeatureException(
                "bodyType=${response.bodyType} is not supported yet (planned for phase 1). " +
                    "Route: ${route.method} ${route.path}",
            )
        }
    }

    // --- spec/behavior/matching.md §7 — header merging ---

    /** Response headers override environment headers. */
    private fun mergeHeaders(
        environmentHeaders: List<HeaderPair>,
        responseHeaders: List<HeaderPair>,
    ): List<HeaderPair> {
        if (responseHeaders.isEmpty()) return environmentHeaders
        val overridden = responseHeaders.mapTo(mutableSetOf()) { it.key.lowercase() }
        return environmentHeaders.filterNot { it.key.lowercase() in overridden } + responseHeaders
    }

    // --- spec/behavior/matching.md §3 — default 404 ---

    /**
     * A synthetic response, not a route response: the environment's
     * `defaultLatencyMs` and `headers` are not applied.
     */
    private fun notFound(request: MockRequest): MockResponse {
        val body = NoMatchBody(
            error = "no_match",
            message = "No route matched ${request.method.uppercase()} ${request.path}",
        )
        return MockResponse(
            statusCode = 404,
            headers = listOf(HeaderPair("Content-Type", "application/json")),
            body = compactJson.encodeToString(NoMatchBody.serializer(), body),
            latencyMs = 0,
        )
    }

    /** The error body is not concatenated by hand; it must be valid JSON even if the path contains quotes. */
    @Serializable
    private data class NoMatchBody(val error: String, val message: String)

    private companion object {
        /** Unlike environment files, error bodies must be single-line. */
        val compactJson = Json { prettyPrint = false }
    }
}
