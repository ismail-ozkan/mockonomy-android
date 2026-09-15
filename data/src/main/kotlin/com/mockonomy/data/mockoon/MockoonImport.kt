package com.mockonomy.data.mockoon

import com.mockonomy.data.IdGenerator
import com.mockonomy.data.openapi.ImportIssue
import com.mockonomy.data.openapi.ImportPreview
import com.mockonomy.data.openapi.ImportResult
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import com.mockonomy.engine.model.RulesOperator
import com.mockonomy.engine.template.TemplateEngine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Converts a Mockoon environment file into routes.
 *
 * **Best effort plus a report of unsupported fields.** Full compatibility is not a
 * goal. Everything that cannot be imported is reported with a reason in
 * [ImportPreview.issues] and shown to the user before importing — the same screen as
 * the OpenAPI import, and the same "no silent skipping" principle.
 *
 * Summary of the mapping:
 * - HTTP routes are imported; CRUD and WebSocket routes are skipped.
 * - Supported rule targets/operators (query/header/body/params · equals/regex) are
 *   imported; everything else is skipped and reported.
 * - Handlebars/faker templates are not converted. Responses containing expressions the
 *   engine does not recognize ([TemplateEngine.recognizes]) are reported per route;
 *   the body is imported as is and the user fixes it by hand.
 * - Environment name/port/prefix/latency are imported; global headers, CORS and proxy
 *   settings are (deliberately) not imported and are written to the report.
 */
object MockoonImport {

    /**
     * Looks at the content and tells whether the file is a Mockoon file — the import
     * screen uses this to pick the OpenAPI or the Mockoon parser. The criterion is
     * conservative: there is a `routes` array, no `openapi`/`swagger` field, and a
     * Mockoon-specific marker (`lastMigration`, `endpointPrefix`, or `endpoint` on a
     * route) is present.
     */
    fun looksLikeMockoon(text: String): Boolean {
        val env = try {
            LENIENT.decodeFromString(Probe.serializer(), text)
        } catch (_: SerializationException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (env.openapi != null || env.swagger != null) return false
        if (env.routes == null) return false
        return env.lastMigration != null ||
            env.endpointPrefix != null ||
            env.routes.any { it.endpoint != null }
    }

    fun parse(text: String): ImportResult {
        val env = try {
            LENIENT.decodeFromString(MockoonEnvironment.serializer(), text)
        } catch (error: SerializationException) {
            return ImportResult.Failure(error.message ?: "The file could not be parsed")
        } catch (error: IllegalArgumentException) {
            return ImportResult.Failure(error.message ?: "The file could not be parsed")
        }

        if (env.routes.isEmpty()) return ImportResult.Failure("This Mockoon file has no routes")

        val issues = mutableListOf<ImportIssue>()
        val routes = env.routes.mapNotNull { route -> mapRoute(route, issues) }
        reportEnvironmentSettings(env, issues)

        return ImportResult.Success(
            ImportPreview(
                title = env.name,
                version = "",
                prefix = normalizePrefix(env.endpointPrefix),
                routes = routes,
                issues = issues,
                defaultLatencyMs = env.latency,
            ),
        )
    }

    private fun mapRoute(route: MockoonRoute, issues: MutableList<ImportIssue>): Route? {
        val label = routeLabel(route.method, route.endpoint)

        if (route.type.lowercase() != TYPE_HTTP) {
            issues += ImportIssue(label, unsupportedTypeReason(route.type))
            return null
        }

        val method = METHODS[route.method.lowercase()]
        if (method == null) {
            issues += ImportIssue(label, "HTTP method '${route.method}' is not supported")
            return null
        }

        if (route.responses.isEmpty()) {
            issues += ImportIssue(label, "Route has no responses")
            return null
        }

        // In Mockoon a null responseMode means rule matching, which is the only mode we
        // support. Other modes (SEQUENTIAL/RANDOM/DISABLE_RULES/FALLBACK) change the ordering.
        route.responseMode?.takeIf { it.isNotBlank() }?.let { mode ->
            issues += ImportIssue(label, "Response mode '$mode' is not supported — falling back to rule matching")
        }

        val location = "$method /${route.endpoint.trimStart('/')}"
        val responses = route.responses.map { mapResponse(it, location, issues) }
        flagTemplating(route, label, issues)

        return Route(
            id = IdGenerator.generate("route"),
            method = method,
            path = normalizePath(route.endpoint),
            enabled = route.enabled,
            responses = withSingleDefault(responses),
        )
    }

    private fun mapResponse(
        response: MockoonResponse,
        location: String,
        issues: MutableList<ImportIssue>,
    ): RouteResponse {
        val body = when (response.bodyType.uppercase()) {
            BODY_FILE -> {
                issues += ImportIssue(location, "Response body comes from an external file — imported empty")
                ""
            }
            BODY_DATABUCKET -> {
                issues += ImportIssue(location, "Response body comes from a data bucket — imported empty")
                ""
            }
            else -> response.body
        }

        return RouteResponse(
            id = IdGenerator.generate("resp"),
            label = response.label.take(MAX_LABEL),
            statusCode = response.statusCode,
            headers = response.headers.toHeaderPairs(),
            body = body,
            latencyMs = response.latency,
            isDefault = response.default,
            rulesOperator = if (response.rulesOperator.uppercase() == "OR") RulesOperator.OR else RulesOperator.AND,
            rules = response.rules.mapNotNull { mapRule(it, location, issues) },
        )
    }

    private fun mapRule(rule: MockoonRule, location: String, issues: MutableList<ImportIssue>): Rule? {
        val target = RULE_TARGETS[rule.target.lowercase()]
        if (target == null) {
            issues += ImportIssue(location, "Rule skipped — target '${rule.target}' is not supported")
            return null
        }
        val operator = when (rule.operator.lowercase()) {
            "equals" -> RuleOperator.EQUALS
            "regex" -> RuleOperator.REGEX
            "regex_i" -> {
                issues += ImportIssue(location, "Rule imported without its case-insensitive flag")
                RuleOperator.REGEX
            }
            else -> {
                issues += ImportIssue(location, "Rule skipped — operator '${rule.operator}' is not supported")
                return null
            }
        }
        return Rule(
            target = target,
            key = rule.modifier,
            operator = operator,
            value = rule.value,
            invert = rule.invert,
        )
    }

    /**
     * Adds a single warning for the route if it contains a template expression the
     * engine does not recognize. Recognition is defined by the engine's own evaluation
     * ([TemplateEngine.recognizes]); no second list is kept — so expressions we support
     * too, like `{{now}}`, are not flagged for nothing. Responses with
     * `disableTemplating` are not expanded by Mockoon anyway, so we do not scan them.
     */
    private fun flagTemplating(route: MockoonRoute, label: String, issues: MutableList<ImportIssue>) {
        val hasUnsupported = route.responses.any { !it.disableTemplating && hasUnsupportedTemplate(it.body) }
        if (hasUnsupported) {
            issues += ImportIssue(
                label,
                "Uses template expressions we don't support (Handlebars/faker) — edit the response body by hand",
            )
        }
    }

    private fun hasUnsupportedTemplate(text: String): Boolean {
        if (!text.contains(OPEN)) return false
        var cursor = 0
        while (true) {
            val start = text.indexOf(OPEN, cursor)
            if (start < 0) return false
            val raw = text.startsWith(RAW_OPEN, start)
            val open = if (raw) RAW_OPEN else OPEN
            val close = if (raw) RAW_CLOSE else CLOSE
            val end = text.indexOf(close, start + open.length)
            if (end < 0) return false
            val expression = text.substring(start + open.length, end).trim()
            if (expression.isNotEmpty() && !TemplateEngine.recognizes(expression)) return true
            cursor = end + close.length
        }
    }

    private fun reportEnvironmentSettings(env: MockoonEnvironment, issues: MutableList<ImportIssue>) {
        val globalHeaders = env.headers.count { it.key.isNotBlank() }
        if (globalHeaders > 0) {
            issues += ImportIssue("environment", "Environment-level default headers were not imported ($globalHeaders)")
        }
        if (env.cors) {
            issues += ImportIssue("environment", "CORS setting was not imported")
        }
        if (env.proxyMode) {
            issues += ImportIssue("environment", "Proxy settings were not imported")
        }
    }

    /** Every route must have exactly one default response (schema rule 7). */
    private fun withSingleDefault(responses: List<RouteResponse>): List<RouteResponse> {
        if (responses.any { it.isDefault }) {
            var seen = false
            return responses.map { response ->
                if (response.isDefault && !seen) {
                    seen = true
                    response
                } else {
                    response.copy(isDefault = false)
                }
            }
        }
        return responses.mapIndexed { index, response -> response.copy(isDefault = index == 0) }
    }

    private fun List<MockoonHeader>.toHeaderPairs(): List<HeaderPair> =
        filter { it.key.isNotBlank() }.map { HeaderPair(it.key, it.value) }

    /** `users/:id` → `/users/:id`. Mockoon's `:param` syntax is the same as the engine's. */
    private fun normalizePath(endpoint: String): String {
        val trimmed = endpoint.trim().trim('/')
        return "/" + trimmed
    }

    /** Mockoon's `endpointPrefix` comes without a leading slash; the engine expects a prefix like `/api`. */
    private fun normalizePrefix(prefix: String?): String {
        val trimmed = prefix?.trim()?.trim('/').orEmpty()
        return if (trimmed.isEmpty()) "" else "/$trimmed"
    }

    private fun routeLabel(method: String, endpoint: String): String {
        val verb = method.uppercase().ifBlank { "?" }
        return "$verb /${endpoint.trimStart('/')}"
    }

    private fun unsupportedTypeReason(type: String): String = when (type.lowercase()) {
        "crud" -> "CRUD routes are not supported"
        "ws", "websocket" -> "WebSocket routes are not supported"
        else -> "Route type '$type' is not supported"
    }

    private val METHODS = HttpMethod.entries.associateBy { it.name.lowercase() }

    private val RULE_TARGETS = mapOf(
        "query" to RuleTarget.QUERY,
        "header" to RuleTarget.HEADER,
        "body" to RuleTarget.BODY,
        "params" to RuleTarget.PATH_PARAM,
    )

    private val LENIENT = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val TYPE_HTTP = "http"
    private const val BODY_FILE = "FILE"
    private const val BODY_DATABUCKET = "DATABUCKET"
    private const val MAX_LABEL = 60

    private const val OPEN = "{{"
    private const val CLOSE = "}}"
    private const val RAW_OPEN = "{{{"
    private const val RAW_CLOSE = "}}}"
}

// --- Mockoon file model (only the fields we read) ---
//
// Plain String instead of enums: when a method/target/type we do not know shows up we
// want to report it, not crash. Unknown fields are dropped via `ignoreUnknownKeys`.

@Serializable
private data class MockoonEnvironment(
    val name: String = "",
    val port: Int = 0,
    val endpointPrefix: String? = null,
    val latency: Int = 0,
    val cors: Boolean = false,
    val proxyMode: Boolean = false,
    val headers: List<MockoonHeader> = emptyList(),
    val routes: List<MockoonRoute> = emptyList(),
)

@Serializable
private data class MockoonRoute(
    val type: String = "http",
    val method: String = "",
    val endpoint: String = "",
    val enabled: Boolean = true,
    val responseMode: String? = null,
    val responses: List<MockoonResponse> = emptyList(),
)

@Serializable
private data class MockoonResponse(
    val statusCode: Int = 200,
    val label: String = "",
    val headers: List<MockoonHeader> = emptyList(),
    val body: String = "",
    val latency: Int = 0,
    val bodyType: String = "INLINE",
    val default: Boolean = false,
    val disableTemplating: Boolean = false,
    val rules: List<MockoonRule> = emptyList(),
    val rulesOperator: String = "OR",
)

@Serializable
private data class MockoonRule(
    val target: String = "",
    val modifier: String = "",
    val value: String = "",
    val operator: String = "",
    val invert: Boolean = false,
)

@Serializable
private data class MockoonHeader(
    val key: String = "",
    val value: String = "",
)

/** A lightweight format probe for [MockoonImport.looksLikeMockoon]. */
@Serializable
private data class Probe(
    val openapi: String? = null,
    val swagger: String? = null,
    val lastMigration: Int? = null,
    val endpointPrefix: String? = null,
    val routes: List<ProbeRoute>? = null,
)

@Serializable
private data class ProbeRoute(val endpoint: String? = null)
