package com.mockonomy.server

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ProxyConfig
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
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
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration test of proxy forwarding.
 *
 * Two real Ktor CIO servers come up: (1) the [MockServer] under test and (2) the **stub
 * target** the proxy forwards to. The client sends a request to the mock server; when
 * nothing matches, the proxy forwards the request to the stub target and the stub's
 * response goes back to the client. That way the "no match → forward to the real target →
 * response flows to the client" chain is exercised end to end over a real network — no fakes.
 */
class MockServerProxyTest {

    private val forwarder = ProxyForwarder()
    private val server = MockServer(forwarder = forwarder)
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private lateinit var target: EmbeddedServer<*, *>
    private var targetPort: Int = 0

    @AfterTest
    fun tearDown() {
        if (server.isRunning) server.stop()
        forwarder.close()
        if (::target.isInitialized) target.stop(0, 0)
    }

    // --- helpers ---

    /**
     * A stub target that mirrors the incoming request. For most paths it echoes the
     * request's method, path, query, `X-Test` header and body as JSON and adds an
     * `X-Upstream` response header — so the test can verify what the proxy forwarded.
     * The `/teapot` path returns 418, to test status pass-through.
     */
    private fun startTarget() {
        target = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            routing {
                route("{...}") {
                    handle { respondFromStub(call) }
                }
            }
        }.also { it.start(wait = false) }
        targetPort = runBlocking { target.engine.resolvedConnectors().first().port }
    }

    private suspend fun respondFromStub(call: ApplicationCall) {
        when (call.request.path()) {
            "/teapot" -> {
                call.respondText("teapot", status = HttpStatusCode(418, "I'm a teapot"))
                return
            }
            "/redirect" -> {
                call.response.headers.append(HttpHeaders.Location, "/redirected")
                call.respondText("", status = HttpStatusCode.Found)
                return
            }
            "/gzip" -> {
                val raw = """{"gz":"ok"}"""
                val gzipped = ByteArrayOutputStream().apply {
                    GZIPOutputStream(this).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
                }.toByteArray()
                call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
                call.respondBytes(gzipped, ContentType.Application.Json, HttpStatusCode.OK)
                return
            }
        }
        val body = call.receiveText()
        val echo = """{"method":"${call.request.httpMethod.value}",""" +
            """"path":"${call.request.path()}",""" +
            """"query":"${call.request.queryString()}",""" +
            """"xTest":"${call.request.headers["X-Test"].orEmpty()}",""" +
            """"proxyAuth":"${call.request.headers["Proxy-Authorization"].orEmpty()}",""" +
            """"body":"$body"}"""
        // A hop-by-hop example in the response direction: must not be forwarded to the client.
        call.response.headers.append("Proxy-Authenticate", "Basic realm=stub")
        call.response.headers.append("X-Upstream", "yes")
        call.respondText(echo, ContentType.Application.Json, HttpStatusCode.OK)
    }

    private fun proxyEnvironment(
        enabled: Boolean = true,
        routes: List<Route> = emptyList(),
    ) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-proxy",
        name = "Proxy",
        port = 0,
        headers = emptyList(),
        proxy = ProxyConfig(enabled = enabled, target = "http://127.0.0.1:$targetPort"),
        routes = routes,
    )

    private val pingRoute = Route(
        id = "route-ping",
        method = HttpMethod.GET,
        path = "/ping",
        responses = listOf(
            RouteResponse(id = "resp-ok", statusCode = 200, body = """{"status":"ok"}""", isDefault = true),
        ),
    )

    private fun startServer(environment: Environment): String = runBlocking {
        server.start(environment, port = 0)
        "http://127.0.0.1:${server.resolvedPort()}"
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    // --- tests ---

    @Test
    fun `an unmatched request is forwarded to the proxy target`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        get("$baseUrl/nope").use { response ->
            assertEquals(200, response.code)
            assertEquals("yes", response.header("X-Upstream"))
            assertContains(response.body?.string().orEmpty(), """"path":"/nope"""")
        }
    }

    @Test
    fun `path and query string are joined correctly for the target`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        get("$baseUrl/users/42?active=true").use { response ->
            val body = response.body?.string().orEmpty()
            assertContains(body, """"path":"/users/42"""")
            assertContains(body, """"query":"active=true"""")
        }
    }

    @Test
    fun `POST body is forwarded to the target`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        val request = Request.Builder().url("$baseUrl/submit").post("hello-body".toRequestBody()).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            assertContains(body, """"method":"POST"""")
            assertContains(body, """"body":"hello-body"""")
        }
    }

    @Test
    fun `request header is forwarded to the target`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        val request = Request.Builder().url("$baseUrl/echo").header("X-Test", "abc").build()
        client.newCall(request).execute().use { response ->
            assertContains(response.body?.string().orEmpty(), """"xTest":"abc"""")
        }
    }

    @Test
    fun `target status code passes through to the client`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        get("$baseUrl/teapot").use { response ->
            assertEquals(418, response.code)
        }
    }

    @Test
    fun `with the proxy off an unmatched request still gets 404`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment(enabled = false))

        get("$baseUrl/nope").use { response ->
            assertEquals(404, response.code)
            assertContains(response.body?.string().orEmpty(), "no_match")
            assertNull(response.header("X-Upstream"))
        }
    }

    @Test
    fun `a matching request bypasses the proxy`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment(routes = listOf(pingRoute)))

        get("$baseUrl/ping").use { response ->
            assertEquals(200, response.code)
            // The stub target was never hit: the mock engine produced the response.
            assertNull(response.header("X-Upstream"))
            assertContains(response.body?.string().orEmpty(), """"status":"ok"""")
        }
    }

    // --- headers, compression, errors ---

    @Test
    fun `gzipped response arrives decoded at the client`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        get("$baseUrl/gzip").use { response ->
            assertEquals(200, response.code)
            // Transparent gzip was decoded: the body is plain JSON, Content-Encoding was not forwarded.
            assertEquals("""{"gz":"ok"}""", response.body?.string())
            assertNull(response.header("Content-Encoding"))
        }
    }

    @Test
    fun `request hop-by-hop header is not forwarded to the target`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        val request = Request.Builder()
            .url("$baseUrl/echo")
            .header("Proxy-Authorization", "Basic secret")
            .header("X-Test", "kept")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // The hop-by-hop header was stripped; the normal header was forwarded.
            assertContains(body, """"proxyAuth":""""")
            assertContains(body, """"xTest":"kept"""")
        }
    }

    @Test
    fun `response hop-by-hop header is not forwarded to the client`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        get("$baseUrl/echo").use { response ->
            assertEquals(200, response.code)
            assertNull(response.header("Proxy-Authenticate"))
            // The normal response header was forwarded — the control group.
            assertEquals("yes", response.header("X-Upstream"))
        }
    }

    @Test
    fun `a redirect passes through to the client as is`() {
        startTarget()
        val baseUrl = startServer(proxyEnvironment())

        // The client must not follow either, so we can see that the proxy passed the 302 through.
        val noRedirect = OkHttpClient.Builder()
            .followRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("$baseUrl/redirect").build()
        noRedirect.newCall(request).execute().use { response ->
            assertEquals(302, response.code)
            assertEquals("/redirected", response.header("Location"))
        }
    }

    @Test
    fun `unreachable target returns 502 with an explanation`() {
        // No stub is started; forward to a closed port.
        val baseUrl = startServer(
            proxyEnvironment().copy(proxy = ProxyConfig(enabled = true, target = "http://127.0.0.1:1")),
        )

        val elapsed = kotlin.system.measureTimeMillis {
            get("$baseUrl/anything").use { response ->
                assertEquals(502, response.code)
                val body = response.body?.string().orEmpty()
                assertContains(body, "proxy_failed")
                assertContains(body, "mockonomy_error")
            }
        }
        // No hanging: a refused connection returns long before the timeout.
        assertTrue(elapsed < 10_000, "502 came back too late: ${elapsed}ms")
    }
}
