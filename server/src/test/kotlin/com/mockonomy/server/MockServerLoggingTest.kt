package com.mockonomy.server

import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogSink
import com.mockonomy.engine.SkipReason
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies with real HTTP requests what the server hands to the log store. The store's
 * own logic is tested in `:data`.
 */
class MockServerLoggingTest {

    private class RecordingSink(override val isRecording: Boolean = true) : RequestLogSink {
        val logs = CopyOnWriteArrayList<RequestLog>()
        override fun record(log: RequestLog) {
            logs += log
        }
    }

    /**
     * Recording happens **after** the response is written; the client may already have the
     * response while the server coroutine has not reached that line yet. So the test waits —
     * the behavior actually under test is "recording does not delay the response".
     */
    private fun awaitLog(): RequestLog {
        val deadline = System.nanoTime() + AWAIT_TIMEOUT_NS
        while (sink.logs.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(AWAIT_POLL_MS)
        }
        return sink.logs.single()
    }

    private val sink = RecordingSink()
    private val server = MockServer(logSink = sink)
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @AfterTest
    fun tearDown() {
        if (server.isRunning) server.stop()
    }

    private fun environment(routes: List<Route>) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-log",
        name = "Log",
        port = 0,
        headers = listOf(HeaderPair("Content-Type", "application/json")),
        routes = routes,
    )

    private val usersRoute = Route(
        id = "route-users",
        method = HttpMethod.GET,
        path = "/users/:id",
        responses = listOf(
            RouteResponse(
                id = "resp-missing",
                statusCode = 404,
                body = """{"error":"not found"}""",
                rules = listOf(Rule(RuleTarget.QUERY, "simulate", RuleOperator.EQUALS, "missing")),
            ),
            RouteResponse(
                id = "resp-ok",
                statusCode = 200,
                body = """{"id":"{{path.id}}"}""",
                isDefault = true,
            ),
        ),
    )

    private fun start(routes: List<Route> = listOf(usersRoute)): String = runBlocking {
        server.start(environment(routes), port = 0)
        "http://127.0.0.1:${server.resolvedPort()}"
    }

    @Test
    fun `a served request is recorded`() {
        val baseUrl = start()

        client.newCall(Request.Builder().url("$baseUrl/users/7?simulate=missing").build())
            .execute().use { assertEquals(404, it.code) }

        val log = awaitLog()
        assertEquals("env-log", log.environmentId)
        assertEquals("GET", log.method)
        assertEquals("/users/7", log.path)
        assertEquals("simulate=missing", log.query)
        assertEquals(404, log.statusCode)
        assertEquals("""{"error":"not found"}""", log.responseBody)
        assertEquals("route-users", log.matchedRouteId)
        assertEquals("resp-missing", log.matchedResponseId)
        assertTrue(log.timestampMs > 0)
    }

    @Test
    fun `the trace comes with the record`() {
        val baseUrl = start(
            routes = listOf(
                Route(
                    id = "route-me",
                    method = HttpMethod.GET,
                    path = "/users/me",
                    responses = listOf(RouteResponse(id = "resp-me", statusCode = 200, isDefault = true)),
                ),
                usersRoute,
            ),
        )

        client.newCall(Request.Builder().url("$baseUrl/users/7").build()).execute().use { it.code }

        val trace = assertNotNull(awaitLog().trace)
        assertEquals("route-users", trace.matchedRouteId)
        assertEquals("resp-ok", trace.matchedResponseId)
        assertTrue(trace.fallbackUsed)
        assertEquals(
            SkipReason.PATH_MISMATCH,
            trace.attemptedRoutes.single { it.routeId == "route-me" }.skipReason,
        )
    }

    @Test
    fun `request body and headers are recorded`() {
        val baseUrl = start(
            routes = listOf(
                Route(
                    id = "route-post",
                    method = HttpMethod.POST,
                    path = "/users",
                    responses = listOf(RouteResponse(id = "resp-created", statusCode = 201, isDefault = true)),
                ),
            ),
        )

        val request = Request.Builder()
            .url("$baseUrl/users")
            .header("X-Debug", "yes")
            .post("""{"name":"ada"}""".toRequestBody())
            .build()
        client.newCall(request).execute().use { assertEquals(201, it.code) }

        val log = awaitLog()
        assertEquals("""{"name":"ada"}""", log.requestBody)
        assertTrue(log.requestHeaders.any { it.key == "X-Debug" && it.value == "yes" })
        assertEquals("", log.query)
    }

    @Test
    fun `an unmatched request is recorded too`() {
        val baseUrl = start()

        client.newCall(Request.Builder().url("$baseUrl/nope").build())
            .execute().use { assertEquals(404, it.code) }

        val log = awaitLog()
        assertEquals(404, log.statusCode)
        assertNull(log.matchedRouteId)
        assertNotNull(log.trace)
    }

    @Test
    fun `with recording off neither a record nor a trace is produced`() {
        val quietSink = RecordingSink(isRecording = false)
        val quietServer = MockServer(logSink = quietSink)
        val baseUrl = runBlocking {
            quietServer.start(environment(listOf(usersRoute)), port = 0)
            "http://127.0.0.1:${quietServer.resolvedPort()}"
        }

        try {
            client.newCall(Request.Builder().url("$baseUrl/users/7").build())
                .execute().use { assertEquals(200, it.code) }
            Thread.sleep(AWAIT_POLL_MS * 5)
            assertTrue(quietSink.logs.isEmpty())
        } finally {
            quietServer.stop()
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_NS = 5_000_000_000L
        const val AWAIT_POLL_MS = 10L
    }
}
