package com.mockonomy.ui.screens.routes

import com.mockonomy.data.log.RequestLog
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouteEditsTest {

    private fun route(id: String, path: String = "/$id", method: HttpMethod = HttpMethod.GET) = Route(
        id = id,
        method = method,
        path = path,
        responses = listOf(RouteResponse(id = "resp-$id", statusCode = 200)),
    )

    private fun environment(vararg routes: Route) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = "A",
        port = Environment.DEFAULT_PORT,
        routes = routes.toList(),
    )

    // --- adding ---

    @Test
    fun `a new route is appended to the end - order matters because the first match wins`() {
        val result = environment(route("r1")).withRouteAdded("r2", "resp-r2", HttpMethod.POST, "/new")

        assertEquals(listOf("r1", "r2"), result.routes.map { it.id })
    }

    @Test
    fun `a new route comes with a working default response`() {
        val added = environment().withRouteAdded("r1", "resp-r1", HttpMethod.GET, "/ping").routes.single()

        val response = added.responses.single()
        assertEquals(200, response.statusCode)
        assertTrue(response.isDefault)
        assertTrue(added.enabled)
    }

    // --- ordering ---

    @Test
    fun `moving up changes the order`() {
        val result = environment(route("r1"), route("r2"), route("r3")).withRouteMoved("r3", -1)

        assertEquals(listOf("r1", "r3", "r2"), result.routes.map { it.id })
    }

    @Test
    fun `moving down changes the order`() {
        val result = environment(route("r1"), route("r2")).withRouteMoved("r1", 1)

        assertEquals(listOf("r2", "r1"), result.routes.map { it.id })
    }

    @Test
    fun `moving past the list bounds does nothing`() {
        val original = environment(route("r1"), route("r2"))

        assertEquals(original, original.withRouteMoved("r1", -1))
        assertEquals(original, original.withRouteMoved("r2", 1))
    }

    @Test
    fun `moving a missing route does not crash`() {
        val original = environment(route("r1"))

        assertEquals(original, original.withRouteMoved("missing", 1))
    }

    // --- disabling and deleting ---

    @Test
    fun `disabling only affects the target route`() {
        val result = environment(route("r1"), route("r2")).withRouteEnabled("r1", enabled = false)

        assertFalse(result.routes.first { it.id == "r1" }.enabled)
        assertTrue(result.routes.first { it.id == "r2" }.enabled)
    }

    @Test
    fun `deleting removes only the target`() {
        val result = environment(route("r1"), route("r2")).withRouteDeleted("r1")

        assertEquals(listOf("r2"), result.routes.map { it.id })
    }

    // --- conflicts ---

    @Test
    fun `same method and path count as a conflict`() {
        val env = environment(route("r1", "/users"), route("r2", "/users"))

        assertTrue(env.hasRouteConflict("r1"))
        assertTrue(env.hasRouteConflict("r2"))
    }

    @Test
    fun `same path with a different method is not a conflict`() {
        val env = environment(
            route("r1", "/users", HttpMethod.GET),
            route("r2", "/users", HttpMethod.POST),
        )

        assertFalse(env.hasRouteConflict("r1"))
    }

    @Test
    fun `a single route does not conflict with itself`() {
        assertFalse(environment(route("r1", "/users")).hasRouteConflict("r1"))
    }

    // --- response operations ---

    @Test
    fun `the first response becomes the default, later ones do not`() {
        val empty = route("r1").copy(responses = emptyList())

        val first = empty.withResponseAdded("resp-1").responses.single()
        assertTrue(first.isDefault)

        val second = empty.withResponseAdded("resp-1").withResponseAdded("resp-2").responses.last()
        assertFalse(second.isDefault)
    }

    @Test
    fun `deleting a response removes only the target`() {
        val withTwo = route("r1").withResponseAdded("resp-2")

        assertEquals(listOf("resp-r1"), withTwo.withResponseDeleted("resp-2").responses.map { it.id })
    }

    @Test
    fun `deleting the default response moves the default to the first remaining one`() {
        val withTwo = route("r1").copy(
            responses = listOf(
                RouteResponse(id = "resp-1", statusCode = 200, isDefault = true),
                RouteResponse(id = "resp-2", statusCode = 500),
            ),
        )

        val remaining = withTwo.withResponseDeleted("resp-1").responses

        assertEquals(listOf("resp-2"), remaining.map { it.id })
        assertTrue(remaining.single().isDefault)
    }

    @Test
    fun `deleting a non-default does not change the default`() {
        val withTwo = route("r1").copy(
            responses = listOf(
                RouteResponse(id = "resp-1", statusCode = 200, isDefault = true),
                RouteResponse(id = "resp-2", statusCode = 500),
            ),
        )

        val remaining = withTwo.withResponseDeleted("resp-2").responses

        assertTrue(remaining.single().isDefault)
    }

    @Test
    fun `selecting the active response makes exactly one the default`() {
        val withTwo = route("r1").copy(
            responses = listOf(
                RouteResponse(id = "resp-1", statusCode = 200, isDefault = true),
                RouteResponse(id = "resp-2", statusCode = 500),
            ),
        )

        val switched = withTwo.withDefaultResponse("resp-2").responses

        assertEquals(listOf(false, true), switched.map { it.isDefault })
    }

    @Test
    fun `a route transformation does not touch the others`() {
        val result = environment(route("r1"), route("r2"))
            .withRouteTransformed("r2") { it.copy(path = "/changed") }

        assertEquals("/r1", result.routes.first { it.id == "r1" }.path)
        assertEquals("/changed", result.routes.first { it.id == "r2" }.path)
    }

    // --- mock from a log ---

    private fun log(
        method: String = "GET",
        path: String = "/users/7",
        statusCode: Int = 200,
        responseBody: String = """{"id":7}""",
        responseHeaders: List<HeaderPair> = emptyList(),
    ) = RequestLog(
        environmentId = "env-a",
        timestampMs = 0,
        method = method,
        path = path,
        query = "",
        requestHeaders = emptyList(),
        requestBody = null,
        statusCode = statusCode,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        durationMs = 0,
        matchedRouteId = null,
        matchedResponseId = null,
        trace = null,
    )

    @Test
    fun `a route built from a log carries method, path, status and body and is appended`() {
        val result = environment(route("r1")).withRouteFromLog("r2", "resp-r2", log(method = "POST"))

        assertEquals(listOf("r1", "r2"), result.routes.map { it.id })
        val created = result.routes.last()
        assertEquals(HttpMethod.POST, created.method)
        assertEquals("/users/7", created.path)
        val response = created.responses.single()
        assertEquals(200, response.statusCode)
        assertEquals("""{"id":7}""", response.body)
        assertTrue(response.isDefault)
    }

    @Test
    fun `a route built from a log drops noisy headers`() {
        val headers = listOf(
            HeaderPair("Content-Type", "application/json"),
            HeaderPair("Date", "Mon, 17 Aug 2026 10:00:00 GMT"),
            HeaderPair("server", "nginx"),
            HeaderPair("Connection", "keep-alive"),
            HeaderPair("Content-Length", "8"),
            HeaderPair("X-Custom", "keep-me"),
        )

        val response = environment()
            .withRouteFromLog("r1", "resp-r1", log(responseHeaders = headers))
            .routes.single().responses.single()

        assertEquals(
            listOf("Content-Type" to "application/json", "X-Custom" to "keep-me"),
            response.headers.map { it.key to it.value },
        )
    }

    @Test
    fun `an unknown method falls back to GET`() {
        val created = environment()
            .withRouteFromLog("r1", "resp-r1", log(method = "TRACE"))
            .routes.single()

        assertEquals(HttpMethod.GET, created.method)
    }

    // --- deduplication + parameterized path ---

    @Test
    fun `with a path override the route is built with the parameterized path`() {
        val created = environment()
            .withRouteFromLog("r1", "resp-r1", log(path = "/users/42"), path = "/users/:userId")
            .routes.single()

        assertEquals("/users/:userId", created.path)
    }

    @Test
    fun `findRouteFromLog finds the same method and path`() {
        val env = environment(route("r1", path = "/users/:userId", method = HttpMethod.GET))

        assertEquals("r1", env.findRouteFromLog(HttpMethod.GET, "/users/:userId")?.id)
        assertEquals(null, env.findRouteFromLog(HttpMethod.POST, "/users/:userId"))
        assertEquals(null, env.findRouteFromLog(HttpMethod.GET, "/other"))
    }

    @Test
    fun `withResponseFromLog adds a non-default response to an existing route`() {
        val env = environment(route("r1"))
            .withResponseFromLog("r1", "resp-new", log(statusCode = 404, responseBody = "not found"))

        val responses = env.routes.single { it.id == "r1" }.responses
        assertEquals(2, responses.size)
        val added = responses.last()
        assertEquals(404, added.statusCode)
        assertEquals("not found", added.body)
        assertFalse(added.isDefault, "the added response must not be the default")
    }

    @Test
    fun `withDefaultResponseFromLog updates the default response and keeps id and label`() {
        val base = route("r1").copy(
            responses = listOf(
                RouteResponse(id = "d", label = "Primary", statusCode = 200, body = "old", isDefault = true),
            ),
        )
        val env = environment(base)
            .withDefaultResponseFromLog("r1", log(statusCode = 201, responseBody = "new"))

        val response = env.routes.single().responses.single()
        assertEquals("d", response.id)
        assertEquals("Primary", response.label)
        assertEquals(201, response.statusCode)
        assertEquals("new", response.body)
        assertTrue(response.isDefault)
    }
}
