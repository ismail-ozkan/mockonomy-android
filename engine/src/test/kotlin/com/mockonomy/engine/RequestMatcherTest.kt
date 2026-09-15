package com.mockonomy.engine

import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ResponseMode
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RequestMatcherTest {

    private val matcher = RequestMatcher()

    private fun environment(
        prefix: String = "",
        defaultLatencyMs: Int = 0,
        headers: List<HeaderPair> = emptyList(),
        routes: List<Route>,
    ) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-test",
        name = "Test",
        port = Environment.DEFAULT_PORT,
        prefix = prefix,
        defaultLatencyMs = defaultLatencyMs,
        headers = headers,
        routes = routes,
    )

    private fun route(
        method: HttpMethod = HttpMethod.GET,
        path: String = "/ping",
        enabled: Boolean = true,
        responseMode: ResponseMode = ResponseMode.RULES,
        responses: List<RouteResponse> = listOf(response()),
    ) = Route(
        id = "route-$method-$path",
        method = method,
        path = path,
        enabled = enabled,
        responseMode = responseMode,
        responses = responses,
    )

    private fun response(
        id: String = "resp",
        statusCode: Int = 200,
        body: String = """{"status":"ok"}""",
        headers: List<HeaderPair> = emptyList(),
        latencyMs: Int = 0,
        isDefault: Boolean = true,
        bodyType: BodyType = BodyType.INLINE,
    ) = RouteResponse(
        id = id,
        statusCode = statusCode,
        headers = headers,
        bodyType = bodyType,
        body = body,
        latencyMs = latencyMs,
        isDefault = isDefault,
    )

    private fun get(path: String) = MockRequest(method = "GET", path = path)

    // --- basic matching ---

    @Test
    fun `responds when method and path match exactly`() {
        val env = environment(routes = listOf(route()))
        val result = matcher.serve(env, get("/ping"))

        assertEquals(200, result.statusCode)
        assertEquals("""{"status":"ok"}""", result.body)
    }

    @Test
    fun `does not match when the method differs`() {
        val env = environment(routes = listOf(route()))
        assertNull(matcher.match(env, MockRequest(method = "POST", path = "/ping")))
    }

    @Test
    fun `does not match when the path differs`() {
        val env = environment(routes = listOf(route()))
        assertNull(matcher.match(env, get("/pong")))
    }

    @Test
    fun `matches after the prefix is stripped`() {
        val env = environment(prefix = "api/v1", routes = listOf(route()))
        assertEquals(200, matcher.serve(env, get("/api/v1/ping")).statusCode)
    }

    // --- spec/behavior/matching.md counterparts ---

    @Test
    fun `no match without the prefix`() {
        val env = environment(prefix = "api/v1", routes = listOf(route()))
        assertNull(matcher.match(env, get("/ping")))
    }

    @Test
    fun `prefix is compared per segment`() {
        // with prefix "api", /apixyz must not match
        val env = environment(prefix = "api", routes = listOf(route()))
        assertNull(matcher.match(env, get("/apixyz/ping")))
    }

    @Test
    fun `trailing slash is meaningless`() {
        val env = environment(routes = listOf(route(path = "/ping")))
        assertEquals(200, matcher.serve(env, get("/ping/")).statusCode)
    }

    @Test
    fun `method is case-insensitive`() {
        val env = environment(routes = listOf(route()))
        assertEquals(200, matcher.serve(env, MockRequest(method = "get", path = "/ping")).statusCode)
    }

    @Test
    fun `path is case-sensitive`() {
        val env = environment(routes = listOf(route(path = "/Search")))
        assertNull(matcher.match(env, get("/search")))
    }

    @Test
    fun `does not match when the segment count differs`() {
        val env = environment(routes = listOf(route(path = "/users")))
        assertNull(matcher.match(env, get("/users/42")))
    }

    @Test
    fun `disabled route is skipped`() {
        val env = environment(routes = listOf(route(enabled = false)))
        assertNull(matcher.match(env, get("/ping")))
    }

    @Test
    fun `the isDefault response is selected`() {
        val env = environment(
            routes = listOf(
                route(
                    responses = listOf(
                        response(id = "first", statusCode = 500, isDefault = false),
                        response(id = "default", statusCode = 201, isDefault = true),
                    ),
                ),
            ),
        )
        assertEquals(201, matcher.serve(env, get("/ping")).statusCode)
    }

    @Test
    fun `without isDefault the first response is selected`() {
        val env = environment(
            routes = listOf(
                route(
                    responses = listOf(
                        response(id = "first", statusCode = 500, isDefault = false),
                        response(id = "second", statusCode = 201, isDefault = false),
                    ),
                ),
            ),
        )
        assertEquals(500, matcher.serve(env, get("/ping")).statusCode)
    }

    @Test
    fun `latencies are added up`() {
        val env = environment(
            defaultLatencyMs = 100,
            routes = listOf(route(responses = listOf(response(latencyMs = 50)))),
        )
        assertEquals(150, matcher.serve(env, get("/ping")).latencyMs)
    }

    @Test
    fun `response headers override environment headers`() {
        val env = environment(
            headers = listOf(
                HeaderPair("Content-Type", "application/json"),
                HeaderPair("X-Env", "kept"),
            ),
            routes = listOf(
                route(
                    responses = listOf(
                        response(headers = listOf(HeaderPair("content-type", "text/plain"))),
                    ),
                ),
            ),
        )

        val headers = matcher.serve(env, get("/ping")).headers
        assertEquals(
            listOf(HeaderPair("X-Env", "kept"), HeaderPair("content-type", "text/plain")),
            headers,
        )
    }

    // --- 404 fallback ---

    @Test
    fun `returns the default 404 when nothing matches`() {
        val env = environment(routes = listOf(route()))
        val result = matcher.serve(env, MockRequest(method = "POST", path = "/ping"))

        assertEquals(404, result.statusCode)
        assertEquals(listOf(HeaderPair("Content-Type", "application/json")), result.headers)
        assertEquals(
            """{"error":"no_match","message":"No route matched POST /ping"}""",
            result.body,
        )
    }

    @Test
    fun `404 does not get environment latency or headers`() {
        val env = environment(
            defaultLatencyMs = 500,
            headers = listOf(HeaderPair("X-Env", "should-not-appear")),
            routes = emptyList(),
        )
        val result = matcher.serve(env, get("/nope"))

        assertEquals(0, result.latencyMs)
        assertEquals(listOf(HeaderPair("Content-Type", "application/json")), result.headers)
    }

    @Test
    fun `404 body is valid json even if the path contains quotes`() {
        val env = environment(routes = emptyList())
        val result = matcher.serve(env, get("""/a"b"""))

        // Concatenating strings by hand would produce broken JSON
        assertContains(result.body, """\"""")
    }

    @Test
    fun `a method outside the schema does not crash`() {
        val env = environment(routes = listOf(route()))
        val result = matcher.serve(env, MockRequest(method = "TRACE", path = "/ping"))
        assertEquals(404, result.statusCode)
    }

    // --- unsupported features ---

    @Test
    fun `unsupported responseMode fails loudly`() {
        // SEQUENTIAL is supported; RANDOM is not yet.
        val env = environment(routes = listOf(route(responseMode = ResponseMode.RANDOM)))
        val error = assertFailsWith<UnsupportedMockFeatureException> {
            matcher.serve(env, get("/ping"))
        }
        assertContains(error.message.orEmpty(), "RANDOM")
    }

    @Test
    fun `unsupported bodyType fails loudly`() {
        val env = environment(
            routes = listOf(route(responses = listOf(response(bodyType = BodyType.FILE)))),
        )
        val error = assertFailsWith<UnsupportedMockFeatureException> {
            matcher.serve(env, get("/ping"))
        }
        assertContains(error.message.orEmpty(), "FILE")
    }

    @Test
    fun `an unrelated unsupported route does not break other requests`() {
        // rejectUnsupported only looks at the *selected* route: /ping must be served
        // normally even if an unrelated RANDOM route sits in the environment.
        val env = environment(
            routes = listOf(
                route(path = "/other", responseMode = ResponseMode.RANDOM),
                route(path = "/ping"),
            ),
        )
        assertEquals(200, matcher.serve(env, get("/ping")).statusCode)
    }
}
