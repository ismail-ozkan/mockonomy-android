package com.mockonomy.ui.screens.routes

import com.mockonomy.data.log.RequestLog
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse

/**
 * Pure versions of route list edits.
 *
 * ViewModels get their ids with `SavedStateHandle.toRoute()`; that relies on the Android
 * `Bundle`, so it does not work in a plain JVM test. Moving the logic here makes it
 * testable — id generation is left to the caller so the result is deterministic.
 */

/**
 * A new route is appended to the **end** of the list: in matching "the first route in the
 * list wins"; adding it at the start would silently change existing behavior.
 */
internal fun Environment.withRouteAdded(
    routeId: String,
    responseId: String,
    method: HttpMethod,
    path: String,
): Environment = copy(
    routes = routes + Route(
        id = routeId,
        method = method,
        path = path,
        responses = listOf(
            RouteResponse(id = responseId, statusCode = DEFAULT_STATUS_CODE, body = DEFAULT_BODY, isDefault = true),
        ),
    ),
)

/**
 * Creates a persistent mock route from a recorded request ("record & replay"). A real
 * response that was proxied and logged can then be served even while the backend is down.
 *
 * Like [withRouteAdded], the route is appended to the **end** of the list — the first match
 * wins, so it does not shadow existing routes. The response body/headers are taken from the
 * log as they are; only connection-specific noise is dropped with [stripNoisyHeaders]. Ids
 * come from the caller (the determinism discipline of this file).
 *
 * Without [path] the log's concrete path is used; a parameterized path (`/users/:userId`)
 * can be passed.
 */
internal fun Environment.withRouteFromLog(
    routeId: String,
    responseId: String,
    log: RequestLog,
    path: String = log.path,
): Environment = copy(
    routes = routes + Route(
        id = routeId,
        method = httpMethodOf(log.method),
        path = path,
        responses = listOf(responseFromLog(responseId, log, isDefault = true)),
    ),
)

/**
 * The existing route with the same method + path (deduplication). Used for conflict
 * detection when creating a mock from a recorded request.
 */
internal fun Environment.findRouteFromLog(method: HttpMethod, path: String): Route? =
    routes.firstOrNull { it.method == method && it.path == path }

/**
 * Adds **a new (non-default) response** from the log to an existing route (deduplication
 * option 1 — different scenarios collect under the same route).
 */
internal fun Environment.withResponseFromLog(routeId: String, responseId: String, log: RequestLog): Environment =
    copy(routes = routes.map { route ->
        if (route.id == routeId) {
            route.copy(responses = route.responses + responseFromLog(responseId, log, isDefault = false))
        } else {
            route
        }
    })

/**
 * Updates the **default response** of an existing route from the log (deduplication option
 * 2). The id and label are kept; status/headers/body are refreshed. Nothing changes if there
 * is no default.
 */
internal fun Environment.withDefaultResponseFromLog(routeId: String, log: RequestLog): Environment =
    copy(routes = routes.map { route ->
        if (route.id != routeId) return@map route
        val defaultId = route.responses.firstOrNull { it.isDefault }?.id ?: return@map route
        route.copy(
            responses = route.responses.map { response ->
                if (response.id == defaultId) {
                    responseFromLog(response.id, log, isDefault = true).copy(label = response.label)
                } else {
                    response
                }
            },
        )
    })

private fun responseFromLog(id: String, log: RequestLog, isDefault: Boolean): RouteResponse = RouteResponse(
    id = id,
    statusCode = log.statusCode,
    headers = stripNoisyHeaders(log.responseHeaders),
    body = log.responseBody,
    isDefault = isDefault,
)

/**
 * Response headers that are specific to the connection/host and meaningless or harmful to
 * carry into a mock. `Content-Length` in particular: the old length from the log would not
 * match the mock body and would break the response — the server computes the right one.
 */
private val NOISY_RESPONSE_HEADERS = setOf("date", "server", "connection", "content-length")

private fun stripNoisyHeaders(headers: List<HeaderPair>): List<HeaderPair> =
    headers.filterNot { it.key.lowercase() in NOISY_RESPONSE_HEADERS }

/** An unknown/non-standard method (TRACE, CONNECT…) falls back to GET in the mock. */
internal fun httpMethodOf(method: String): HttpMethod =
    runCatching { HttpMethod.valueOf(method.uppercase()) }.getOrDefault(HttpMethod.GET)

/** The signature that identifies routes for conflict detection. */
internal fun routeSignature(method: HttpMethod, path: String): String = "${method.name} $path"

internal fun Environment.withRouteEnabled(routeId: String, enabled: Boolean): Environment =
    copy(routes = routes.map { if (it.id == routeId) it.copy(enabled = enabled) else it })

internal fun Environment.withRouteDeleted(routeId: String): Environment =
    copy(routes = routes.filterNot { it.id == routeId })

/** [delta] -1 moves up, +1 down. Nothing changes if it would move past the list bounds. */
internal fun Environment.withRouteMoved(routeId: String, delta: Int): Environment {
    val from = routes.indexOfFirst { it.id == routeId }
    val to = from + delta
    if (from < 0 || to !in routes.indices) return this

    val reordered = routes.toMutableList()
    reordered.add(to, reordered.removeAt(from))
    return copy(routes = reordered)
}

internal fun Environment.withRouteTransformed(routeId: String, transform: (Route) -> Route): Environment =
    copy(routes = routes.map { if (it.id == routeId) transform(it) else it })

/**
 * Is there *another* route with the same method + path? This is for a warning, not for
 * blocking — with rules it is a legitimate use.
 */
internal fun Environment.hasRouteConflict(routeId: String): Boolean {
    val current = routes.firstOrNull { it.id == routeId } ?: return false
    return routes.any { it.id != current.id && it.method == current.method && it.path == current.path }
}

internal fun Route.withResponseAdded(responseId: String): Route = copy(
    responses = responses + RouteResponse(
        id = responseId,
        statusCode = DEFAULT_STATUS_CODE,
        body = DEFAULT_BODY,
        // The first response becomes the default; the user picks among the later ones.
        isDefault = responses.isEmpty(),
    ),
)

/**
 * If the deleted response was the default, the default moves to the first response left in
 * the list. Otherwise the route could not serve anything (schema rule 7: at most one default
 * per route — but at least one is needed too).
 */
internal fun Route.withResponseDeleted(responseId: String): Route {
    val remaining = responses.filterNot { it.id == responseId }
    val lostDefault = responses.any { it.id == responseId && it.isDefault }
    if (!lostDefault || remaining.isEmpty()) return copy(responses = remaining)

    return copy(
        responses = remaining.mapIndexed { index, response -> response.copy(isDefault = index == 0) },
    )
}

/** Active response selection — per schema rule 7 exactly one becomes the default. */
internal fun Route.withDefaultResponse(responseId: String): Route =
    copy(responses = responses.map { it.copy(isDefault = it.id == responseId) })

private const val DEFAULT_STATUS_CODE = 200
private const val DEFAULT_BODY = "{}"
