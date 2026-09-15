package com.mockonomy.data.log.export

import com.mockonomy.data.log.RequestLog
import com.mockonomy.engine.model.HeaderPair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HarExportTest {

    private fun log(
        id: String = "log-1",
        timestampMs: Long = 1_770_000_000_000,
        method: String = "GET",
        path: String = "/users/7",
        query: String = "",
        requestBody: String? = null,
        responseBody: String = """{"id":"7"}""",
        statusCode: Int = 200,
        requestBodyTruncated: Boolean = false,
        responseBodyTruncated: Boolean = false,
    ) = RequestLog(
        id = id,
        environmentId = "env-1",
        timestampMs = timestampMs,
        method = method,
        path = path,
        query = query,
        requestHeaders = listOf(HeaderPair("Host", "127.0.0.1:8080")),
        requestBody = requestBody,
        requestBodyTruncated = requestBodyTruncated,
        statusCode = statusCode,
        responseHeaders = listOf(HeaderPair("Content-Type", "application/json")),
        responseBody = responseBody,
        responseBodyTruncated = responseBodyTruncated,
        durationMs = 12,
        matchedRouteId = "route-1",
        matchedResponseId = "resp-1",
        trace = null,
    )

    private fun harOf(vararg logs: RequestLog): JsonObject =
        Json.parseToJsonElement(HarExport.of(logs.toList(), appVersion = "0.1.0")).jsonObject["log"]!!.jsonObject

    @Test
    fun `required top-level fields are written`() {
        val har = harOf(log())

        assertEquals("1.2", har["version"]!!.jsonPrimitive.content)
        assertEquals("Mockonomy", har["creator"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", har["creator"]!!.jsonObject["version"]!!.jsonPrimitive.content)
        assertEquals(1, har["entries"]!!.jsonArray.size)
    }

    @Test
    fun `request and response fields are filled`() {
        val entry = harOf(log(query = "simulate=missing")).entries().single()
        val request = entry["request"]!!.jsonObject
        val response = entry["response"]!!.jsonObject

        assertEquals("GET", request["method"]!!.jsonPrimitive.content)
        assertEquals(
            "http://127.0.0.1:8080/users/7?simulate=missing",
            request["url"]!!.jsonPrimitive.content,
        )
        assertEquals("HTTP/1.1", request["httpVersion"]!!.jsonPrimitive.content)
        assertEquals(200, response["status"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "application/json",
            response["content"]!!.jsonObject["mimeType"]!!.jsonPrimitive.content,
        )
        assertEquals(
            """{"id":"7"}""",
            response["content"]!!.jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `fields viewers expect are complete`() {
        val entry = harOf(log()).entries().single()

        // HAR 1.2 required fields — if one is missing Chrome DevTools rejects the file.
        listOf("startedDateTime", "time", "request", "response", "cache", "timings").forEach {
            assertTrue(it in entry, "missing field $it")
        }
        listOf("send", "wait", "receive").forEach {
            assertTrue(it in entry["timings"]!!.jsonObject, "missing timings.$it")
        }
        listOf("cookies", "headers", "queryString", "headersSize", "bodySize").forEach {
            assertTrue(it in entry["request"]!!.jsonObject, "missing request.$it")
        }
        listOf("cookies", "headers", "content", "redirectURL", "headersSize", "bodySize").forEach {
            assertTrue(it in entry["response"]!!.jsonObject, "missing response.$it")
        }
    }

    @Test
    fun `time is an ISO 8601 timestamp`() {
        val entry = harOf(log(timestampMs = 1_770_000_000_000)).entries().single()

        assertEquals("2026-02-02T02:40:00Z", entry["startedDateTime"]!!.jsonPrimitive.content)
        assertEquals(12.0, entry["time"]!!.jsonPrimitive.content.toDouble())
        assertEquals(12.0, entry["timings"]!!.jsonObject["wait"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `query pairs are parsed`() {
        val request = harOf(log(query = "a=1&b=2&flag")).entries().single()["request"]!!.jsonObject
        val pairs = request["queryString"]!!.jsonArray.map {
            it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["value"]!!.jsonPrimitive.content
        }

        assertEquals(listOf("a" to "1", "b" to "2", "flag" to ""), pairs)
    }

    @Test
    fun `request without a body has no postData`() {
        assertNull(harOf(log()).entries().single()["request"]!!.jsonObject["postData"])
    }

    @Test
    fun `request with a body has postData`() {
        val postData = harOf(log(method = "POST", requestBody = """{"a":1}"""))
            .entries().single()["request"]!!.jsonObject["postData"]!!.jsonObject

        assertEquals("""{"a":1}""", postData["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncated body is marked with a comment`() {
        val entry = harOf(log(responseBodyTruncated = true)).entries().single()

        assertTrue(entry["comment"]!!.jsonPrimitive.content.contains("Response body was truncated"))
    }

    @Test
    fun `entries are sorted oldest first`() {
        val har = harOf(
            log(id = "newer", timestampMs = 2_000, path = "/b"),
            log(id = "older", timestampMs = 1_000, path = "/a"),
        )

        val paths = har.entries().map { it["request"]!!.jsonObject["url"]!!.jsonPrimitive.content }
        assertTrue(paths[0].endsWith("/a"), "the older entry should come first: $paths")
    }

    private fun JsonObject.entries() = this["entries"]!!.jsonArray.map { it.jsonObject }
}
