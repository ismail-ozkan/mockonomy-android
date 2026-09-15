package com.mockonomy.server

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ResponseMode
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sends real HTTP requests to a real Ktor CIO server — no mocks, stubs or Ktor test
 * engine.
 *
 * The server starts with `port = 0`; the operating system picks a free port. That way
 * tests do not occupy a fixed port and do not collide on CI.
 */
class MockServerIntegrationTest {

    private val server = MockServer()
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @AfterTest
    fun tearDown() {
        if (server.isRunning) server.stop()
    }

    // --- helpers ---

    private fun environment(
        prefix: String = "",
        defaultLatencyMs: Int = 0,
        headers: List<HeaderPair> = listOf(HeaderPair("Content-Type", "application/json")),
        routes: List<Route>,
    ) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-test",
        name = "Test",
        port = 0,
        prefix = prefix,
        defaultLatencyMs = defaultLatencyMs,
        headers = headers,
        routes = routes,
    )

    private fun pingRoute(
        method: HttpMethod = HttpMethod.GET,
        path: String = "/ping",
        responseMode: ResponseMode = ResponseMode.RULES,
        response: RouteResponse = RouteResponse(
            id = "resp-ok",
            statusCode = 200,
            body = """{"status":"ok"}""",
            isDefault = true,
        ),
    ) = Route(
        id = "route-ping",
        method = method,
        path = path,
        responseMode = responseMode,
        responses = listOf(response),
    )

    private fun startAndGetBaseUrl(environment: Environment): String = runBlocking {
        server.start(environment, port = 0)
        "http://127.0.0.1:${server.resolvedPort()}"
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    // --- tests ---

    @Test
    fun `active state is published live - initial, setState and manual`() {
        val env = environment(
            routes = listOf(
                Route(
                    id = "route-login",
                    method = HttpMethod.POST,
                    path = "/login",
                    responses = listOf(
                        RouteResponse(
                            id = "r-ok", statusCode = 200,
                            whenState = "out", setState = "in", isDefault = true,
                        ),
                    ),
                ),
            ),
        ).copy(states = listOf("out", "in"), initialState = "out")

        val baseUrl = startAndGetBaseUrl(env)
        // Initially: initialState is published
        assertEquals("out", server.activeState.value)

        // A request that applies setState → the live state is updated
        client.newCall(
            Request.Builder().url("$baseUrl/login").post("".toRequestBody()).build(),
        ).execute().use { assertEquals(200, it.code) }
        assertEquals("in", server.activeState.value)

        // manual change
        server.setActiveState("out")
        assertEquals("out", server.activeState.value)

        // cleared when stopped
        server.stop()
        assertEquals(null, server.activeState.value)
    }

    @Test
    fun `server comes up and returns the mock response`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))

        get("$baseUrl/ping").use { response ->
            assertEquals(200, response.code)
            assertEquals("""{"status":"ok"}""", response.body?.string())
            assertContains(response.header("Content-Type").orEmpty(), "application/json")
        }
    }

    @Test
    fun `binds only to the loopback address`() {
        // The mock server must never be exposed by accident on a public network
        startAndGetBaseUrl(environment(routes = listOf(pingRoute())))
        assertEquals("127.0.0.1", runBlocking { server.resolvedHost() })
    }

    @Test
    fun `an unmatched request gets the 404 body from the spec`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))

        get("$baseUrl/nope").use { response ->
            assertEquals(404, response.code)
            assertEquals(
                """{"error":"no_match","message":"No route matched GET /nope"}""",
                response.body?.string(),
            )
        }
    }

    @Test
    fun `method mismatch returns 404`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))

        val request = Request.Builder()
            .url("$baseUrl/ping")
            .post("".toRequestBody())
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(404, response.code)
        }
    }

    @Test
    fun `prefix is applied`() {
        val baseUrl = startAndGetBaseUrl(
            environment(prefix = "api/v1", routes = listOf(pingRoute())),
        )

        get("$baseUrl/api/v1/ping").use { assertEquals(200, it.code) }
        get("$baseUrl/ping").use { assertEquals(404, it.code) }
    }

    @Test
    fun `query string does not break path matching`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))
        get("$baseUrl/ping?verbose=true&page=2").use { assertEquals(200, it.code) }
    }

    @Test
    fun `custom headers reach the client`() {
        val baseUrl = startAndGetBaseUrl(
            environment(
                headers = listOf(
                    HeaderPair("Content-Type", "application/json"),
                    HeaderPair("X-Env-Header", "from-environment"),
                ),
                routes = listOf(
                    pingRoute(
                        response = RouteResponse(
                            id = "resp-ok",
                            statusCode = 200,
                            headers = listOf(HeaderPair("X-Response-Header", "from-response")),
                            body = "{}",
                            isDefault = true,
                        ),
                    ),
                ),
            ),
        )

        get("$baseUrl/ping").use { response ->
            assertEquals("from-environment", response.header("X-Env-Header"))
            assertEquals("from-response", response.header("X-Response-Header"))
        }
    }

    @Test
    fun `latency is applied before the response is written`() {
        val baseUrl = startAndGetBaseUrl(
            environment(defaultLatencyMs = 300, routes = listOf(pingRoute())),
        )

        val elapsed = kotlin.system.measureTimeMillis {
            get("$baseUrl/ping").use { assertEquals(200, it.code) }
        }

        assertTrue(elapsed >= 300, "latency was not applied: ${elapsed}ms")
    }

    @Test
    fun `unsupported feature returns 501 with an explanation`() {
        val baseUrl = startAndGetBaseUrl(
            environment(routes = listOf(pingRoute(responseMode = ResponseMode.RANDOM))),
        )

        get("$baseUrl/ping").use { response ->
            assertEquals(501, response.code)
            val body = response.body?.string().orEmpty()
            assertContains(body, "unsupported_feature")
            assertContains(body, "RANDOM")
        }
    }

    // --- hot reload ---

    @Test
    fun `after update a new route responds without a restart`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))
        val boundPort = runBlocking { server.resolvedPort() }

        get("$baseUrl/nope").use { assertEquals(404, it.code) }

        val secondRoute = Route(
            id = "route-nope",
            method = HttpMethod.GET,
            path = "/nope",
            responses = listOf(RouteResponse(id = "resp-nope", statusCode = 200, body = "{}", isDefault = true)),
        )
        runBlocking {
            server.update(
                environment(routes = listOf(pingRoute(), secondRoute)).copy(port = boundPort),
            )
        }

        // The same port is still listening — no restart happened.
        assertEquals(boundPort, runBlocking { server.resolvedPort() })
        get("$baseUrl/nope").use { assertEquals(200, it.code) }
    }

    @Test
    fun `after a body update the next request gets the new body`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))
        val boundPort = runBlocking { server.resolvedPort() }

        get("$baseUrl/ping").use { assertEquals("""{"status":"ok"}""", it.body?.string()) }

        runBlocking {
            server.update(
                environment(
                    routes = listOf(
                        pingRoute(
                            response = RouteResponse(
                                id = "resp-updated",
                                statusCode = 200,
                                body = """{"status":"updated"}""",
                                isDefault = true,
                            ),
                        ),
                    ),
                ).copy(port = boundPort),
            )
        }

        get("$baseUrl/ping").use { assertEquals("""{"status":"updated"}""", it.body?.string()) }
    }

    @Test
    fun `server restarts when update changes the port`() {
        val baseUrl = startAndGetBaseUrl(environment(routes = listOf(pingRoute())))
        val oldPort = runBlocking { server.resolvedPort() }

        // port = 0: asks for a new port; a collision with the old one is statistically impossible.
        runBlocking { server.update(environment(routes = listOf(pingRoute())).copy(port = 0)) }

        val newPort = runBlocking { server.resolvedPort() }
        assertTrue(newPort != oldPort, "port did not change: $oldPort")

        // The old port no longer listens.
        assertTrue(runCatching { get(baseUrl).close() }.isFailure, "old port is still open: $oldPort")
        get("http://127.0.0.1:$newPort/ping").use { assertEquals(200, it.code) }
    }

    @Test
    fun `calling update on a stopped server throws`() {
        assertTrue(
            runCatching { runBlocking { server.update(environment(routes = listOf(pingRoute()))) } }.isFailure,
        )
    }

    @Test
    fun `port is released after stopping`() {
        val environment = environment(routes = listOf(pingRoute()))
        val baseUrl = startAndGetBaseUrl(environment)
        get("$baseUrl/ping").use { assertEquals(200, it.code) }

        server.stop()
        assertEquals(false, server.isRunning)

        // The same object must be restartable
        val secondUrl = startAndGetBaseUrl(environment)
        get("$secondUrl/ping").use { assertEquals(200, it.code) }
    }
}
