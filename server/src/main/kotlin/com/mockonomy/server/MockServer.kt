package com.mockonomy.server

import com.mockonomy.data.log.NoRequestLog
import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogSink
import com.mockonomy.engine.MatchTrace
import com.mockonomy.engine.MockRequest
import com.mockonomy.engine.MockResponse
import com.mockonomy.engine.MockSession
import com.mockonomy.engine.RequestMatcher
import com.mockonomy.engine.UnsupportedMockFeatureException
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The layer that connects the mock engine to an HTTP server.
 *
 * Ktor's routing DSL is **not** used: our routes come from user data at runtime, not
 * at compile time. All we need from the server is to open the socket and hand us the
 * request; [RequestMatcher] does the matching.
 *
 * The server binds to **loopback** only. Since the use case is the same device,
 * binding to `127.0.0.1` instead of `0.0.0.0` keeps the mock server from being
 * exposed by accident on a public Wi-Fi network. This is a default from the start,
 * not a safeguard added later.
 *
 * With a [logSink], every request is recorded. Recording does not delay the response:
 * the server only drops the entry into a queue; writing to disk is another
 * coroutine's job.
 */
class MockServer(
    private val matcher: RequestMatcher = RequestMatcher(),
    private val logSink: RequestLogSink = NoRequestLog,
    private val forwarder: ProxyForwarder = ProxyForwarder(),
    /**
     * The automation admin API. When `null` the `/__mockonomy` endpoints are not
     * registered at all — for callers that do not need the admin API (e.g. plain mock
     * integration tests).
     */
    private val adminApi: AdminApi? = null,
) : AdminControl {

    private var server: EmbeddedServer<*, *>? = null

    /**
     * Called when the admin API activates an environment. [MockServerController], which
     * owns the server lifecycle, uses it to keep the running-server list and the
     * notification in sync. Test-friendly: when `null` the hot swap still happens, only
     * external state is not updated.
     */
    @Volatile
    var onActivated: (suspend (Environment) -> Unit)? = null

    /**
     * The configuration being served. [Environment] is immutable and [RequestMatcher]
     * is stateless, so swapping this reference provides hot reload without locking.
     */
    @Volatile
    private var environment: Environment? = null

    /**
     * Runtime state of the running environment: per-route request counters and the
     * active state of the named state machine. It is reset with `initialState` when the
     * server starts and when a different environment is activated — critical for test
     * isolation (`spec/behavior/sequential-response.md`, `state-machine.md`).
     */
    private val session = MockSession()

    /**
     * An **observable** mirror of the state machine's active state. The state flow lives
     * here so [MockSession] stays pure: it is updated after every request (a setState
     * transition may have happened) and on a manual [setActiveState] call. The UI reads
     * it for the notification and the live indicator.
     */
    private val _activeState = MutableStateFlow<String?>(null)
    val activeState: StateFlow<String?> = _activeState.asStateFlow()

    val isRunning: Boolean get() = server != null

    /**
     * Starts the server and returns immediately.
     *
     * @param port with `0` the operating system picks a free port; the actual port is
     *   available via [resolvedPort]. Tests use this so they do not occupy a fixed port.
     */
    fun start(environment: Environment, port: Int = environment.port) {
        check(server == null) { "MockServer is already running" }

        // Starting the server is a reset point (activation goes through here): counters
        // are reset and the state machine starts from initialState.
        session.reset(environment.initialState)
        _activeState.value = session.state
        this.environment = environment
        server = embeddedServer(CIO, port = port, host = LOOPBACK_HOST) {
            routing {
                // The static admin API routes under `/__mockonomy/*` are registered BEFORE
                // the catch-all; Ktor matches the more specific one, so admin endpoints win
                // over mock matching.
                adminApi?.install(this, this@MockServer)

                // A single tailcard: every method and every path lands here. Ktor does no
                // matching for us — that is RequestMatcher's job.
                route("{...}") {
                    handle { handleCall(call) }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(GRACE_PERIOD_MS, TIMEOUT_MS)
        server = null
        environment = null
        _activeState.value = null
    }

    /**
     * Changes the active state by hand — the control QA uses most during manual testing.
     * Does nothing when the server is not running.
     */
    fun setActiveState(state: String) {
        if (server == null) return
        session.transition(state)
        _activeState.value = session.state
    }

    // --- AdminControl — the admin API's runtime control surface ---

    override val currentEnvironment: Environment? get() = environment
    override val currentState: String? get() = _activeState.value
    override suspend fun port(): Int? = if (server == null) null else resolvedPort()
    override fun setState(state: String) = setActiveState(state)

    override fun resetSession() {
        if (server == null) return
        session.reset(environment?.initialState)
        _activeState.value = session.state
    }

    override fun overrideResponse(routeId: String, responseId: String) {
        session.overrideResponse(routeId, responseId)
    }

    /**
     * Hot-swaps the environment **on the running socket** — the target environment's
     * port is deliberately ignored (the test client is already connected to the running
     * port; changing ports mid-test would cut it off). Switching to a different
     * environment is a reset point: the session is reset with initialState. External
     * state is synchronized by [onActivated].
     */
    override suspend fun activate(environment: Environment) {
        if (server == null) return
        this.environment = environment
        session.reset(environment.initialState)
        _activeState.value = session.state
        onActivated?.invoke(environment)
    }

    /**
     * Updates the configuration.
     *
     * If the port is unchanged, only the [environment] reference is swapped atomically —
     * the socket is not reopened, in-flight requests keep reading the old object and new
     * requests see the new one (no lock needed; see the class KDoc for why). If the port
     * changed, the server is transparently stopped and restarted on the new port.
     */
    suspend fun update(newEnvironment: Environment) {
        checkNotNull(server) { "MockServer is not running" }
        if (newEnvironment.port == resolvedPort()) {
            // A configuration edit of the same environment (hot reload) does not reset
            // counters and state — so a running test sequence is not disturbed. Switching
            // to a different environment is an activation: reset.
            if (newEnvironment.id != environment?.id) {
                session.reset(newEnvironment.initialState)
                _activeState.value = session.state
            }
            environment = newEnvironment
        } else {
            stop()
            start(newEnvironment, newEnvironment.port)
        }
    }

    /** The actual port bound by the operating system. */
    suspend fun resolvedPort(): Int = connector().port

    /** The bound host — used to verify that nothing binds outside loopback. */
    suspend fun resolvedHost(): String = connector().host

    private suspend fun connector() =
        checkNotNull(server) { "MockServer is not running" }.engine.resolvedConnectors().first()

    private suspend fun handleCall(call: ApplicationCall) {
        val current = environment ?: return

        val sink = logSink.takeIf { it.isRecording }
        val startedAtMs = System.currentTimeMillis()
        val startedAtNs = System.nanoTime()

        val request = MockRequest(
            method = call.request.httpMethod.value,
            path = call.request.path(),
            query = call.request.queryParameters.entries().associate { it.key to it.value },
            headers = call.request.headers.entries().associate { it.key to it.value },
            body = call.receiveText().takeIf { it.isNotEmpty() },
        )

        // A trace is only produced while recording is on (`spec/behavior/rules.md` §5) —
        // with recording off the server does not do a single extra line of work.
        //
        // `null` => no route matched; the request is a proxy candidate. Otherwise the
        // response to serve is decided — an unsupported feature error also belongs to a
        // *matching* route, so it does not go to the proxy.
        var trace: MatchTrace? = null
        val matchedResponse: MockResponse? = try {
            if (sink == null) {
                matcher.match(current, request, session = session)?.response
            } else {
                val traced = matcher.explain(current, request, session = session).also { trace = it.trace }
                traced.response.takeIf { traced.trace.matchedResponseId != null }
            }
        } catch (error: UnsupportedMockFeatureException) {
            // Otherwise the client sees an opaque 500 and never learns why.
            unsupportedFeatureResponse(error)
        }

        // The request may have applied a setState; update the live indicator.
        if (_activeState.value != session.state) _activeState.value = session.state

        // No match + proxy on => forward the request to the real target. The proxy
        // response does not go through the mock engine; it is streamed to the client as
        // is, so binary bodies stay intact. It is recorded in the request log only when
        // global recording is on (`sink != null`) AND `recordUnmatched` is on.
        val proxy = current.proxy
        if (matchedResponse == null && proxy != null && proxy.enabled && proxy.target.isNotBlank()) {
            val outcome = forwarder.forward(proxy, request, call)
            if (sink != null && proxy.recordUnmatched) {
                sink.record(proxyLog(current, request, call, outcome, startedAtMs, startedAtNs))
            }
            return
        }

        // No match + proxy off => synthetic 404. `serve` is only called on this rare
        // path; when there is a match the response is already ready above.
        val response = matchedResponse ?: matcher.serve(current, request, session = session)

        if (response.latencyMs > 0) {
            delay(response.latencyMs.toLong())
        }
        writeResponse(call, response)

        // AFTER the response is written: the cost of recording must not count toward the
        // time the client measures. `record` does not suspend; it enqueues and returns.
        sink?.record(
            RequestLog(
                environmentId = current.id,
                timestampMs = startedAtMs,
                method = request.method,
                path = request.path,
                query = call.request.queryString(),
                requestHeaders = request.headers.toHeaderPairs(),
                requestBody = request.body,
                statusCode = response.statusCode,
                responseHeaders = response.headers,
                responseBody = response.body,
                // The duration the client sees, including artificial latency.
                durationMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLI,
                matchedRouteId = trace?.matchedRouteId,
                matchedResponseId = trace?.matchedResponseId,
                trace = trace,
            ),
        )
    }

    private fun Map<String, List<String>>.toHeaderPairs(): List<HeaderPair> =
        flatMap { (key, values) -> values.map { HeaderPair(key, it) } }

    /**
     * Turns a proxied request/response into a request log entry. There is no match, so
     * `matchedRouteId/ResponseId` and `trace` are empty; creating a mock from the entry
     * still works from method/path/status/body.
     */
    private fun proxyLog(
        environment: Environment,
        request: MockRequest,
        call: ApplicationCall,
        outcome: ProxyOutcome,
        startedAtMs: Long,
        startedAtNs: Long,
    ): RequestLog = RequestLog(
        environmentId = environment.id,
        timestampMs = startedAtMs,
        method = request.method,
        path = request.path,
        query = call.request.queryString(),
        requestHeaders = request.headers.toHeaderPairs(),
        requestBody = request.body,
        statusCode = outcome.statusCode,
        responseHeaders = outcome.responseHeaders,
        responseBody = outcome.responseBody,
        durationMs = (System.nanoTime() - startedAtNs) / NANOS_PER_MILLI,
        matchedRouteId = null,
        matchedResponseId = null,
        trace = null,
    ).truncated(RequestLog.BODY_LIMIT)

    private suspend fun writeResponse(call: ApplicationCall, response: MockResponse) {
        // Ktor manages Content-Type and Content-Length; adding them by hand fails.
        val contentType = response.headers
            .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
            ?.let { runCatching { ContentType.parse(it.value) }.getOrNull() }
            ?: ContentType.Text.Plain

        response.headers
            .filterNot { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
            .filterNot { it.key.equals(HttpHeaders.ContentLength, ignoreCase = true) }
            .forEach { (key, value) ->
                // safeOnly = false: a mock tool must be able to produce unusual headers
                // for realism. The decision is the user's.
                call.response.headers.append(key, value, safeOnly = false)
            }

        call.respondText(
            text = response.body,
            contentType = contentType,
            status = HttpStatusCode.fromValue(response.statusCode),
        )
    }

    private fun unsupportedFeatureResponse(error: UnsupportedMockFeatureException) = MockResponse(
        statusCode = HttpStatusCode.NotImplemented.value,
        headers = listOf(HeaderPair(HttpHeaders.ContentType, ContentType.Application.Json.toString())),
        body = """{"error":"unsupported_feature","message":${jsonString(error.message.orEmpty())}}""",
        latencyMs = 0,
    )

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val GRACE_PERIOD_MS = 500L
        const val TIMEOUT_MS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
