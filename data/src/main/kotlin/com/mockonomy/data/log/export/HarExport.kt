package com.mockonomy.data.log.export

import com.mockonomy.data.log.RequestLog
import com.mockonomy.engine.model.HeaderPair
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Turns log entries into a HAR 1.2 file.
 *
 * Criterion: the file must open without errors in a HAR viewer (e.g. the "Import HAR"
 * button in Chrome DevTools). So all required fields of the specification are
 * written; unknown sizes use `-1`, following HAR's own convention.
 *
 * **Known limitation:** `queryString` pairs are written raw, without percent-decoding.
 * The log keeps the query string raw, and decoding it here would create a second copy
 * of the engine's decoder.
 */
@OptIn(ExperimentalTime::class)
object HarExport {

    // encodeDefaults: HAR's required fields must be present even at their default
    // values. explicitNulls: optional fields are omitted instead of written as `null` —
    // some viewers treat `"postData": null` as a broken entry rather than an empty body.
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun of(logs: List<RequestLog>, appVersion: String): String {
        val har = Har(
            log = HarLog(
                creator = HarCreator(name = "Mockonomy", version = appVersion),
                // Viewers draw the timeline trusting the order; the screen lists newest
                // first, the file goes oldest first.
                entries = logs.sortedBy { it.timestampMs }.map(::entry),
            ),
        )
        return json.encodeToString(Har.serializer(), har)
    }

    private fun entry(log: RequestLog) = HarEntry(
        startedDateTime = Instant.fromEpochMilliseconds(log.timestampMs).toString(),
        time = log.durationMs.toDouble(),
        request = HarRequest(
            method = log.method,
            url = url(log),
            headers = log.requestHeaders.map { HarNameValue(it.key, it.value) },
            queryString = queryPairs(log.query),
            postData = log.requestBody?.let {
                HarPostData(mimeType = contentType(log.requestHeaders), text = it)
            },
        ),
        response = HarResponse(
            status = log.statusCode,
            headers = log.responseHeaders.map { HarNameValue(it.key, it.value) },
            content = HarContent(
                size = log.responseBody.length.toLong(),
                mimeType = contentType(log.responseHeaders),
                text = log.responseBody,
            ),
        ),
        timings = HarTimings(send = 0.0, wait = log.durationMs.toDouble(), receive = 0.0),
        // Presenting a truncated body silently as the full body would mislead whoever
        // reads the file. `comment` is HAR's own field.
        comment = when {
            log.requestBodyTruncated && log.responseBodyTruncated ->
                "Request and response bodies were truncated by Mockonomy's log."
            log.requestBodyTruncated -> "Request body was truncated by Mockonomy's log."
            log.responseBodyTruncated -> "Response body was truncated by Mockonomy's log."
            else -> null
        },
    )

    private fun url(log: RequestLog): String {
        val host = log.requestHeaders
            .firstOrNull { it.key.equals("Host", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: "127.0.0.1"
        val query = if (log.query.isEmpty()) "" else "?${log.query}"
        return "http://$host${log.path}$query"
    }

    private fun contentType(headers: List<HeaderPair>): String =
        headers.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()

    private fun queryPairs(query: String): List<HarNameValue> =
        if (query.isEmpty()) {
            emptyList()
        } else {
            query.split('&').filter { it.isNotEmpty() }.map { pair ->
                val index = pair.indexOf('=')
                if (index < 0) {
                    HarNameValue(pair, "")
                } else {
                    HarNameValue(pair.substring(0, index), pair.substring(index + 1))
                }
            }
        }
}

// --- HAR 1.2 schema: only the fields we write ---

@Serializable
private data class Har(val log: HarLog)

@Serializable
private data class HarLog(
    val version: String = "1.2",
    val creator: HarCreator,
    val entries: List<HarEntry>,
)

@Serializable
private data class HarCreator(val name: String, val version: String)

@Serializable
private data class HarEntry(
    val startedDateTime: String,
    /** Total time in milliseconds. */
    val time: Double,
    val request: HarRequest,
    val response: HarResponse,
    val cache: HarCache = HarCache(),
    val timings: HarTimings,
    val comment: String? = null,
)

@Serializable
private data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val cookies: List<HarNameValue> = emptyList(),
    val headers: List<HarNameValue>,
    val queryString: List<HarNameValue>,
    val postData: HarPostData? = null,
    /** Unknown — HAR asks for `-1` in that case. */
    val headersSize: Long = -1,
    val bodySize: Long = -1,
)

@Serializable
private data class HarResponse(
    val status: Int,
    val statusText: String = "",
    val httpVersion: String = "HTTP/1.1",
    val cookies: List<HarNameValue> = emptyList(),
    val headers: List<HarNameValue>,
    val content: HarContent,
    val redirectURL: String = "",
    val headersSize: Long = -1,
    val bodySize: Long = -1,
)

@Serializable
private data class HarContent(val size: Long, val mimeType: String, val text: String)

@Serializable
private data class HarPostData(val mimeType: String, val text: String)

/** HAR requires it, but we have no cache: an empty object. */
@Serializable
private class HarCache

@Serializable
private data class HarTimings(val send: Double, val wait: Double, val receive: Double)

@Serializable
private data class HarNameValue(val name: String, val value: String)
