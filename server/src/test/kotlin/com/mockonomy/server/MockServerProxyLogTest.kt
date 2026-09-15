package com.mockonomy.server

import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogSink
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ProxyConfig
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recording proxy responses in the request log.
 *
 * A proxy response is only logged while global recording is on ([RequestLogSink.isRecording])
 * **and** `recordUnmatched` is on. The [RecordingSink] here records in memory; the real
 * Room store is tested separately in `:data`.
 */
class MockServerProxyLogTest {

    private class RecordingSink(override val isRecording: Boolean = true) : RequestLogSink {
        val records = CopyOnWriteArrayList<RequestLog>()
        override fun record(log: RequestLog) {
            records += log
        }
    }

    private val forwarder = ProxyForwarder()
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private var server: MockServer? = null
    private lateinit var target: EmbeddedServer<*, *>
    private var targetPort: Int = 0

    @AfterTest
    fun tearDown() {
        server?.let { if (it.isRunning) it.stop() }
        forwarder.close()
        if (::target.isInitialized) target.stop(0, 0)
    }

    private fun startTarget() {
        target = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                route("{...}") {
                    handle {
                        call.response.headers.append("X-Upstream", "yes")
                        call.respondText("""{"from":"${call.request.path()}"}""", ContentType.Application.Json)
                    }
                }
            }
        }.also { it.start(wait = false) }
        targetPort = runBlocking { target.engine.resolvedConnectors().first().port }
    }

    private fun proxyEnvironment(recordUnmatched: Boolean, target: String = "http://127.0.0.1:$targetPort") =
        Environment(
            schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
            id = "env-log",
            name = "Log",
            port = 0,
            headers = emptyList(),
            proxy = ProxyConfig(enabled = true, target = target, recordUnmatched = recordUnmatched),
            routes = listOf(
                Route(
                    id = "route-ping",
                    method = HttpMethod.GET,
                    path = "/ping",
                    responses = listOf(RouteResponse(id = "r", statusCode = 200, body = "{}", isDefault = true)),
                ),
            ),
        )

    private fun start(env: Environment, sink: RecordingSink): String = runBlocking {
        val s = MockServer(logSink = sink, forwarder = forwarder)
        server = s
        s.start(env, port = 0)
        "http://127.0.0.1:${s.resolvedPort()}"
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    /**
     * Recording happens **after** the response has flowed to the client; the client may
     * already have the response while the server coroutine has not reached that line yet
     * (this failed on a slow CI runner). Same wait as [MockServerLoggingTest].
     */
    private fun awaitLog(sink: RecordingSink): RequestLog {
        val deadline = System.nanoTime() + AWAIT_TIMEOUT_NS
        while (sink.records.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(AWAIT_POLL_MS)
        }
        return sink.records.single()
    }

    @Test
    fun `proxy response is logged while recordUnmatched is on`() {
        startTarget()
        val sink = RecordingSink()
        val baseUrl = start(proxyEnvironment(recordUnmatched = true), sink)

        get("$baseUrl/users/7").use { assertEquals(200, it.code) }

        val log = awaitLog(sink)
        assertEquals("GET", log.method)
        assertEquals("/users/7", log.path)
        assertEquals(200, log.statusCode)
        assertContains(log.responseBody, """"from":"/users/7"""")
        // No match: the proxy source is recognizable by an empty trace/route.
        assertEquals(null, log.matchedRouteId)
        assertEquals(null, log.trace)
    }

    @Test
    fun `proxy response is not logged while recordUnmatched is off`() {
        startTarget()
        val sink = RecordingSink()
        val baseUrl = start(proxyEnvironment(recordUnmatched = false), sink)

        get("$baseUrl/users/7").use { assertEquals(200, it.code) }

        assertTrue(sink.records.isEmpty(), "nothing should be recorded while recordUnmatched is off")
    }

    @Test
    fun `a matching request lands in the normal log, not the proxy log`() {
        startTarget()
        val sink = RecordingSink()
        val baseUrl = start(proxyEnvironment(recordUnmatched = true), sink)

        get("$baseUrl/ping").use { assertEquals(200, it.code) }

        // A matching request is logged by the normal flow; it did not go to the proxy, so it has a trace.
        val log = awaitLog(sink)
        assertEquals("/ping", log.path)
        assertTrue(log.matchedRouteId != null, "a matching request should carry matchedRouteId")
    }

    @Test
    fun `unreachable target is logged as 502`() {
        val sink = RecordingSink()
        val baseUrl = start(
            proxyEnvironment(recordUnmatched = true, target = "http://127.0.0.1:1"),
            sink,
        )

        get("$baseUrl/anything").use { assertEquals(502, it.code) }

        val log = awaitLog(sink)
        assertEquals(502, log.statusCode)
        assertContains(log.responseBody, "proxy_failed")
    }

    private companion object {
        const val AWAIT_TIMEOUT_NS = 5_000_000_000L
        const val AWAIT_POLL_MS = 10L
    }
}
