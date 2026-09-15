package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnvironmentRedactionTest {

    private fun env(responseHeaders: List<HeaderPair>, globalHeaders: List<HeaderPair> = emptyList()) =
        Environment(
            schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
            id = "env-a",
            name = "A",
            port = Environment.DEFAULT_PORT,
            headers = globalHeaders,
            routes = listOf(
                Route(
                    id = "r1",
                    method = HttpMethod.GET,
                    path = "/x",
                    responses = listOf(RouteResponse(id = "resp-1", statusCode = 200, headers = responseHeaders)),
                ),
            ),
        )

    @Test
    fun `sensitive header value is masked, others are kept`() {
        val redacted = redactSensitiveHeaders(
            env(
                responseHeaders = listOf(
                    HeaderPair("Set-Cookie", "session=abc123"),
                    HeaderPair("Content-Type", "application/json"),
                ),
                globalHeaders = listOf(HeaderPair("Authorization", "Bearer secret")),
            ),
        )

        assertEquals(REDACTED_VALUE, redacted.headers.single().value)
        val responseHeaders = redacted.routes.single().responses.single().headers
        assertEquals(REDACTED_VALUE, responseHeaders.first { it.key == "Set-Cookie" }.value)
        assertEquals("application/json", responseHeaders.first { it.key == "Content-Type" }.value)
    }

    @Test
    fun `header name matches case-insensitively`() {
        val redacted = redactSensitiveHeaders(env(listOf(HeaderPair("x-api-key", "k"), HeaderPair("X-AUTH-TOKEN", "t"))))

        val headers = redacted.routes.single().responses.single().headers
        assertTrue(headers.all { it.value == REDACTED_VALUE })
    }

    @Test
    fun `hasSensitiveHeaders returns true only when a sensitive header exists`() {
        assertTrue(hasSensitiveHeaders(env(listOf(HeaderPair("Cookie", "a=b")))))
        assertFalse(hasSensitiveHeaders(env(listOf(HeaderPair("Content-Type", "text/plain")))))
    }
}
