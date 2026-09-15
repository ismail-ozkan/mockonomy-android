package com.mockonomy.server

import com.mockonomy.data.AdminApiSettings
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.data.redactSensitiveHeaders
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The **runtime control surface** of a running mock server — [AdminApi] is a thin HTTP
 * layer on top of it. [MockServer] implements it; engine logic is not rewritten.
 *
 * When an admin request arrives while the server is running, [currentEnvironment] is
 * always set (the server cannot serve without an environment).
 */
internal interface AdminControl {
    val currentEnvironment: Environment?
    val currentState: String?
    suspend fun port(): Int?

    /** Changes the active state by hand. */
    fun setState(state: String)

    /** Resets counters, state and overrides (test isolation). */
    fun resetSession()

    /** Pins a route to a specific response (forced override). */
    fun overrideResponse(routeId: String, responseId: String)

    /** Hot-swaps the environment on the running socket (the target port is ignored). */
    suspend fun activate(environment: Environment)
}

/**
 * The automation HTTP admin API — the product's differentiator.
 *
 * It lives at the server root under `/__mockonomy/...`; it is not affected by
 * `environment.prefix` and is checked **before** mock matching (Ktor matches static
 * routes before the catch-all). Security: while [settings] `enabled` is off every
 * endpoint returns 404; when the optional token is set `X-Mockonomy-Token` is required;
 * read endpoints mask sensitive headers with `redactReads` (on by default).
 */
class AdminApi(
    private val settings: Flow<AdminApiSettings>,
    private val environmentStore: EnvironmentStore,
    private val logStore: RequestLogStore,
) {

    /** Called from [MockServer.start]: registers admin routes before the catch-all. */
    internal fun install(routing: Routing, control: AdminControl) {
        // Mutations are shared with the ADB broadcast receiver; a single implementation.
        val handler = AutomationCommandHandler(control, environmentStore, logStore)
        routing.route(ADMIN_PREFIX) {
            get("/health") { gated { call.health(control) } }
            get("/environments") { gated { call.listEnvironments(control) } }
            post("/environments/{key}/activate") {
                gated { call.respond(handler.handle(AutomationCommand.Activate(call.parameters["key"].orEmpty()))) }
            }
            put("/routes/{key}/response") { gated { call.overrideResponse(handler) } }
            post("/state") { gated { call.setState(handler) } }
            post("/reset") { gated { call.respond(handler.handle(AutomationCommand.Reset)) } }
            get("/logs") { gated { call.readLogs(control) } }
            delete("/logs") { gated { call.respond(handler.handle(AutomationCommand.ClearLogs)) } }
        }
    }

    // --- security gate ---

    /**
     * [block] only runs when the admin API is on and the token (if any) is correct. When
     * it is off the endpoint answers 404 as if it did not exist; a wrong token gets 401.
     */
    private suspend inline fun io.ktor.server.routing.RoutingContext.gated(block: () -> Unit) {
        val current = settings.first()
        if (!current.enabled) {
            call.respondError(HttpStatusCode.NotFound, "admin_api_disabled", "The automation API is turned off. Enable it in Settings.")
            return
        }
        val token = current.token
        if (token != null && call.request.header(TOKEN_HEADER) != token) {
            call.respondError(HttpStatusCode.Unauthorized, "invalid_token", "Missing or invalid $TOKEN_HEADER header.")
            return
        }
        block()
    }

    // --- endpoints ---

    private suspend fun ApplicationCall.health(control: AdminControl) {
        val env = control.currentEnvironment
        respondJson(
            HealthBody.serializer(),
            HealthBody(
                status = "ok",
                running = env != null,
                environment = env?.name,
                environmentKey = env?.key,
                activeState = control.currentState,
                port = control.port(),
            ),
        )
    }

    private suspend fun ApplicationCall.listEnvironments(control: AdminControl) {
        val activeId = control.currentEnvironment?.id
        val bodies = environmentStore.list()
            .filterIsInstance<EnvironmentSummary.Valid>()
            .map { it.environment.toBody(active = it.environment.id == activeId) }
        respondJson(EnvironmentListBody.serializer(), EnvironmentListBody(bodies))
    }

    /** Resolves the response `key`/`id` from the body (HTTP-specific) and delegates the rest to the handler. */
    private suspend fun ApplicationCall.overrideResponse(handler: AutomationCommandHandler) {
        val routeKey = parameters["key"].orEmpty()
        val request = decode(OverrideRequest.serializer()) ?: return
        val ref = request.key ?: request.id
        if (ref.isNullOrBlank()) {
            respondError(HttpStatusCode.BadRequest, "missing_response_ref", "Provide a response \"key\" or \"id\".")
            return
        }
        respond(handler.handle(AutomationCommand.SetResponse(routeKey, ref)))
    }

    private suspend fun ApplicationCall.setState(handler: AutomationCommandHandler) {
        val request = decode(StateRequest.serializer()) ?: return
        respond(handler.handle(AutomationCommand.SetState(request.state)))
    }

    private suspend fun ApplicationCall.readLogs(control: AdminControl) {
        val env = control.currentEnvironment ?: return respondNoEnvironment()
        val redact = settings.first().redactReads
        val limit = (parameters["limit"]?.toIntOrNull() ?: DEFAULT_LOG_LIMIT).coerceIn(1, MAX_LOG_LIMIT)
        val methodFilter = parameters["method"]?.uppercase()
        val pathFilter = parameters["path"]

        val ids = logStore.observeSummaries(env.id).first()
            .asSequence()
            .filter { methodFilter == null || it.method.uppercase() == methodFilter }
            .filter { pathFilter == null || it.path == pathFilter }
            .take(limit)
            .map { it.id }
            .toList()

        val logs = logStore.findAll(ids)
            .sortedByDescending { it.timestampMs }
            .map { it.toBody(redact) }
        respondJson(LogListBody.serializer(), LogListBody(count = logs.size, logs = logs))
    }

    // --- helpers ---

    /** Maps the shared [AutomationResult] to an HTTP response (code and status are kept). */
    private suspend fun ApplicationCall.respond(result: AutomationResult) = when (result) {
        is AutomationResult.Ok ->
            respondJson(OkBody.serializer(), OkBody(result.message))
        is AutomationResult.NotFound ->
            respondError(HttpStatusCode.NotFound, result.code, result.message, result.available)
        is AutomationResult.BadRequest ->
            respondError(HttpStatusCode.BadRequest, result.code, result.message, result.available)
        is AutomationResult.NoEnvironment ->
            respondError(HttpStatusCode.Conflict, result.code, result.message)
    }

    private suspend fun ApplicationCall.respondNoEnvironment() =
        respondError(HttpStatusCode.Conflict, "no_environment", "No environment is currently running.")

    /** Decodes the body; on invalid JSON writes a 400 and returns `null`. */
    private suspend fun <T> ApplicationCall.decode(serializer: kotlinx.serialization.KSerializer<T>): T? =
        try {
            json.decodeFromString(serializer, receiveText())
        } catch (error: SerializationException) {
            respondError(HttpStatusCode.BadRequest, "invalid_body", "Request body is not valid JSON: ${error.message}")
            null
        }

    private suspend fun <T> ApplicationCall.respondJson(serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        respondText(json.encodeToString(serializer, value), ContentType.Application.Json, HttpStatusCode.OK)

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        error: String,
        message: String,
        available: List<String>? = null,
    ) = respondText(
        json.encodeToString(ErrorBody.serializer(), ErrorBody(error, message, available)),
        ContentType.Application.Json,
        status,
    )

    private fun Environment.toBody(active: Boolean) =
        EnvironmentBody(id = id, key = key, name = name, port = port, active = active)

    private fun RequestLog.toBody(redact: Boolean) = LogBody(
        id = id,
        timestampMs = timestampMs,
        method = method,
        path = path,
        query = query,
        statusCode = statusCode,
        durationMs = durationMs,
        requestHeaders = if (redact) redactSensitiveHeaders(requestHeaders) else requestHeaders,
        requestBody = requestBody,
        responseHeaders = if (redact) redactSensitiveHeaders(responseHeaders) else responseHeaders,
        responseBody = responseBody,
        matchedRouteId = matchedRouteId,
        matchedResponseId = matchedResponseId,
    )

    internal companion object {
        const val ADMIN_PREFIX = "/__mockonomy"
        const val TOKEN_HEADER = "X-Mockonomy-Token"
        const val DEFAULT_LOG_LIMIT = 50
        const val MAX_LOG_LIMIT = 500

        val json = Json {
            prettyPrint = false
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
