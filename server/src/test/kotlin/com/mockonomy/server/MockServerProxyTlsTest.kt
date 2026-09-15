package com.mockonomy.server

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.ProxyConfig
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Integration test of the TLS exemption ([ProxyConfig.ignoreTlsErrors]).
 *
 * An HTTPS stub target with a self-signed certificate comes up. With the exemption off
 * the proxy cannot verify the certificate and returns 502; with it on the certificate is
 * accepted and the real response reaches the client. This is the common scenario of
 * internal staging servers, and it proves the exemption **only works when deliberately
 * turned on**.
 */
class MockServerProxyTlsTest {

    private val forwarder = ProxyForwarder()
    private val server = MockServer(forwarder = forwarder)
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private lateinit var target: EmbeddedServer<*, *>
    private var targetPort: Int = 0

    @AfterTest
    fun tearDown() {
        if (server.isRunning) server.stop()
        forwarder.close()
        if (::target.isInitialized) target.stop(0, 0)
    }

    private fun startHttpsTarget() {
        val keyStore = buildKeyStore {
            certificate("stub") {
                password = "changeit"
                domains = listOf("127.0.0.1", "localhost")
            }
        }
        target = embeddedServer(
            Netty,
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "stub",
                    keyStorePassword = { "changeit".toCharArray() },
                    privateKeyPassword = { "changeit".toCharArray() },
                ) {
                    port = 0
                    host = "127.0.0.1"
                }
            },
            module = {
                routing {
                    route("{...}") {
                        handle { respondSecure(call) }
                    }
                }
            },
        ).also { it.start(wait = false) }
        targetPort = runBlocking { target.engine.resolvedConnectors().first().port }
    }

    private suspend fun respondSecure(call: ApplicationCall) {
        call.respondText("""{"secure":"${call.request.path()}"}""")
    }

    private fun environment(ignoreTls: Boolean) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-tls",
        name = "Tls",
        port = 0,
        headers = emptyList(),
        proxy = ProxyConfig(
            enabled = true,
            target = "https://127.0.0.1:$targetPort",
            ignoreTlsErrors = ignoreTls,
        ),
        routes = emptyList(),
    )

    private fun startServer(env: Environment): String = runBlocking {
        server.start(env, port = 0)
        "http://127.0.0.1:${server.resolvedPort()}"
    }

    private fun get(url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `with the exemption off a self-signed certificate gives 502`() {
        startHttpsTarget()
        val baseUrl = startServer(environment(ignoreTls = false))

        get("$baseUrl/x").use { response ->
            assertEquals(502, response.code)
            assertContains(response.body?.string().orEmpty(), "proxy_failed")
        }
    }

    @Test
    fun `with the exemption on a self-signed certificate is accepted`() {
        startHttpsTarget()
        val baseUrl = startServer(environment(ignoreTls = true))

        get("$baseUrl/x").use { response ->
            assertEquals(200, response.code)
            assertContains(response.body?.string().orEmpty(), """"secure":"/x"""")
        }
    }
}
