package com.mockonomy.server

import com.mockonomy.engine.MockRequest
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.ProxyConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.queryString
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Forwards unmatched requests to a real target.
 *
 * When [RequestMatcher] cannot match a request, [MockServer] brings in the proxy: the
 * request is forwarded to [ProxyConfig.target] and the returned response is streamed to
 * the client.
 *
 * The proxy response deliberately does **not** go through the mock engine (and
 * [com.mockonomy.engine.MockResponse]) — `MockResponse.body` is a `String` and would
 * corrupt binary content (images, protobuf). Instead the response is read as raw bytes and
 * written directly to the [ApplicationCall], so binary bodies pass through intact.
 *
 * **Where proxies usually break:**
 * - **Hop-by-hop headers** (see [HOP_BY_HOP]) are not forwarded in either direction; they
 *   are connection-specific, and forwarding them blindly is the source of the hardest
 *   proxy bugs to diagnose.
 * - **Compression:** the client library's transparent gzip is relied on (no
 *   `Accept-Encoding` is added to the outgoing request by hand). The response arrives
 *   decoded; `Content-Encoding` and `Content-Length` are not forwarded to the client, the
 *   Ktor server recomputes them.
 * - **Redirects** pass through as they are ([HttpClient] `followRedirects = false`): a
 *   mock tool's job is to reflect reality; the app under test must handle the redirect
 *   itself.
 * - **TLS exemption:** when [ProxyConfig.ignoreTlsErrors] is on, a separate client that
 *   accepts self-signed certificates is used (legitimate for internal staging; off by
 *   default).
 * - **Failure modes:** unreachable target, DNS, timeout, TLS — none of them hang; all of
 *   them become a synthetic `502` with an explanatory body.
 */
class ProxyForwarder(
    secureClient: HttpClient? = null,
) : Closeable {

    /** The normal (certificate-verifying) client. Shared, so the connection pool is shared too. */
    private val secureClient: HttpClient = secureClient ?: buildClient(ignoreTls = false)

    /**
     * A separate client for environments that ask for the TLS exemption — it accepts
     * self-signed certificates. Most users never use it, so it is built on first use.
     */
    private val insecureClientLazy = lazy { buildClient(ignoreTls = true) }

    private fun clientFor(config: ProxyConfig): HttpClient =
        if (config.ignoreTlsErrors) insecureClientLazy.value else secureClient

    /**
     * Forwards the request to the target, writes the response to [call] and returns a
     * [ProxyOutcome] for logging. No error is left hanging: network/DNS/timeout/TLS errors
     * become a 502 via [writeProxyError], which also returns an outcome (a 502 can be
     * logged too).
     */
    suspend fun forward(config: ProxyConfig, request: MockRequest, call: ApplicationCall): ProxyOutcome {
        val targetUrl = buildTargetUrl(config.target, request.path, call.request.queryString())
        return try {
            val upstream: HttpResponse = clientFor(config).request(targetUrl) {
                method = HttpMethod.parse(request.method)
                forwardRequestHeaders(request.headers)
                request.body?.let { body ->
                    setBody(ByteArrayContent(body.toByteArray(Charsets.UTF_8), requestContentType(request.headers)))
                }
            }
            writeUpstreamResponse(call, upstream)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            writeProxyError(call, config.target, error)
        }
    }

    private fun HttpRequestBuilder.forwardRequestHeaders(headers: Map<String, List<String>>) {
        headers.forEach { (name, values) ->
            if (isRequestManagedHeader(name)) return@forEach
            values.forEach { value -> this.headers.append(name, value) }
        }
    }

    private suspend fun writeUpstreamResponse(call: ApplicationCall, upstream: HttpResponse): ProxyOutcome {
        val bytes = upstream.readRawBytes()
        val status = HttpStatusCode.fromValue(upstream.status.value)
        val contentTypeRaw = upstream.headers[HttpHeaders.ContentType]
        val contentType = contentTypeRaw?.let { runCatching { ContentType.parse(it) }.getOrNull() }

        // Headers sent to the client are also collected for the log — Content-Type is added
        // by hand because `respondBytes` sets it separately.
        val loggedHeaders = mutableListOf<HeaderPair>()
        upstream.headers.forEach { name, values ->
            if (isResponseManagedHeader(name)) return@forEach
            values.forEach { value ->
                call.response.headers.append(name, value, safeOnly = false)
                loggedHeaders += HeaderPair(name, value)
            }
        }
        contentTypeRaw?.let { loggedHeaders += HeaderPair(HttpHeaders.ContentType, it) }

        call.respondBytes(bytes = bytes, contentType = contentType, status = status)

        // A binary body does not fit the text log model: the body is only recorded for text
        // content types.
        val isText = contentType?.let { isTextContentType(it) } ?: false
        return ProxyOutcome(
            statusCode = status.value,
            responseHeaders = loggedHeaders,
            responseBody = if (isText) bytes.toString(Charsets.UTF_8) else "",
            bodyOmitted = !isText,
        )
    }

    private suspend fun writeProxyError(call: ApplicationCall, target: String, error: Throwable): ProxyOutcome {
        val reason = error.message ?: error::class.simpleName ?: "unknown error"
        val body = """{"mockonomy_error":"proxy_failed","detail":${jsonString("Could not reach target $target: $reason")}}"""
        call.respondText(body, ContentType.Application.Json, HttpStatusCode.BadGateway)
        return ProxyOutcome(
            statusCode = HttpStatusCode.BadGateway.value,
            responseHeaders = listOf(HeaderPair(HttpHeaders.ContentType, ContentType.Application.Json.toString())),
            responseBody = body,
            bodyOmitted = false,
        )
    }

    override fun close() {
        secureClient.close()
        if (insecureClientLazy.isInitialized()) insecureClientLazy.value.close()
    }

    private companion object {
        /**
         * Connection-specific (hop-by-hop) headers — RFC 7230 §6.1 plus the proxy
         * authentication headers. They are not forwarded in either direction.
         */
        val HOP_BY_HOP = setOf(
            "connection", "keep-alive", "transfer-encoding", "te",
            "trailer", "upgrade", "proxy-authorization", "proxy-authenticate",
        )

        /**
         * Connect/socket timeouts are set explicitly — no request may hang forever. With
         * [ignoreTls] on, a trust manager that accepts every certificate is installed; only
         * when the user deliberately turns it on.
         */
        fun buildClient(ignoreTls: Boolean): HttpClient = HttpClient(OkHttp) {
            // Redirects are handled by the app under test, not by the client.
            followRedirects = false
            expectSuccess = false
            engine {
                config {
                    connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (ignoreTls) {
                        val trustAll = allTrustingManager()
                        sslSocketFactory(insecureSocketFactory(trustAll), trustAll)
                        hostnameVerifier { _, _ -> true }
                    }
                }
            }
        }

        fun allTrustingManager(): X509TrustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        fun insecureSocketFactory(trustManager: X509TrustManager) =
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(trustManager), SecureRandom())
            }.socketFactory

        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val WRITE_TIMEOUT_MS = 30_000L

        /**
         * Ktor client does body framing and Host on the outgoing request itself.
         * `Accept-Encoding` is not forwarded either: if it were, OkHttp would treat it as
         * "added by the user", turn off its transparent gzip and hand us a compressed
         * response — since we strip `Content-Encoding`, the client would see a broken body.
         * Encoding between the client and the mock is the mock's job anyway.
         */
        fun isRequestManagedHeader(name: String): Boolean {
            val lower = name.lowercase()
            return lower in HOP_BY_HOP ||
                lower == "content-type" || lower == "content-length" ||
                lower == "host" || lower == "accept-encoding"
        }

        /**
         * Ktor server does body framing on the response to the client; since transparent
         * gzip was decoded, `Content-Encoding` is not forwarded either.
         */
        fun isResponseManagedHeader(name: String): Boolean {
            val lower = name.lowercase()
            return lower in HOP_BY_HOP ||
                lower == "content-type" || lower == "content-length" || lower == "content-encoding"
        }

        // Text content types that can be recorded. Binary content (image, protobuf,
        // octet-stream) does not fit the log's String body model; only these are recorded.
        fun isTextContentType(type: ContentType): Boolean {
            val main = type.contentType.lowercase()
            val sub = type.contentSubtype.lowercase()
            if (main == "text") return true
            if (main != "application") return false
            return sub == "json" || sub == "xml" || sub.endsWith("+json") || sub.endsWith("+xml")
        }

        fun requestContentType(headers: Map<String, List<String>>): ContentType {
            val raw = headers.entries
                .firstOrNull { it.key.equals(HttpHeaders.ContentType, ignoreCase = true) }
                ?.value?.firstOrNull()
            return raw?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.Json
        }

        fun buildTargetUrl(target: String, path: String, queryString: String): String {
            val base = target.trimEnd('/')
            val normalizedPath = if (path.startsWith("/")) path else "/$path"
            val query = queryString.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
            return base + normalizedPath + query
        }

        /** The error body on the wire must be valid JSON, even if the detail contains quotes. */
        fun jsonString(value: String): String = buildString {
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
    }
}
