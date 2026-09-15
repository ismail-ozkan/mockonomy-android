package com.mockonomy.data.log.export

import com.mockonomy.data.log.RequestLog
import com.mockonomy.engine.model.HeaderPair
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurlCommandTest {

    private fun log(
        method: String = "GET",
        path: String = "/users/7",
        query: String = "",
        headers: List<HeaderPair> = listOf(HeaderPair("Host", "127.0.0.1:8080")),
        body: String? = null,
    ) = RequestLog(
        environmentId = "env-1",
        timestampMs = 1_000,
        method = method,
        path = path,
        query = query,
        requestHeaders = headers,
        requestBody = body,
        statusCode = 200,
        responseHeaders = emptyList(),
        responseBody = "",
        durationMs = 1,
        matchedRouteId = null,
        matchedResponseId = null,
        trace = null,
    )

    @Test
    fun `GET command stays minimal`() {
        val command = CurlCommand.of(log(query = "simulate=missing"))

        assertEquals(
            "curl \\\n  'http://127.0.0.1:8080/users/7?simulate=missing' \\\n  -H 'Host: 127.0.0.1:8080'",
            command,
        )
        // -X GET is curl's default; writing it is noise.
        assertFalse(command.contains("-X"))
    }

    @Test
    fun `methods other than GET are written`() {
        assertContains(CurlCommand.of(log(method = "POST", path = "/auth/login")), "-X POST")
    }

    @Test
    fun `body goes with --data-raw`() {
        val command = CurlCommand.of(log(method = "POST", body = """{"name":"ada"}"""))
        assertContains(command, """--data-raw '{"name":"ada"}'""")
    }

    @Test
    fun `single quote is escaped the shell way`() {
        val command = CurlCommand.of(log(method = "POST", body = """{"name":"o'brien"}"""))

        assertContains(command, """--data-raw '{"name":"o'\''brien"}'""")
    }

    @Test
    fun `characters that could trigger shell expansion stay inside single quotes`() {
        val command = CurlCommand.of(log(method = "POST", body = "\$HOME `whoami` \\n"))

        // Inside single quotes the shell interprets none of them; no escaping must be added.
        assertContains(command, "--data-raw '\$HOME `whoami` \\n'")
    }

    @Test
    fun `Content-Length is dropped`() {
        val command = CurlCommand.of(
            log(
                method = "POST",
                headers = listOf(HeaderPair("Host", "127.0.0.1:8080"), HeaderPair("Content-Length", "14")),
                body = """{"name":"ada"}""",
            ),
        )

        assertFalse(command.contains("Content-Length"), "curl should compute the length itself")
    }

    @Test
    fun `loopback is assumed without a Host header`() {
        assertContains(CurlCommand.of(log(headers = emptyList())), "'http://127.0.0.1/users/7'")
    }

    @Test
    fun `command lines are joined with backslashes`() {
        val command = CurlCommand.of(log(headers = listOf(HeaderPair("A", "1"), HeaderPair("B", "2"))))

        assertTrue(command.lines().size == 4, "each part should be on its own line: $command")
        assertTrue(command.lines().dropLast(1).all { it.endsWith(" \\") })
    }
}
