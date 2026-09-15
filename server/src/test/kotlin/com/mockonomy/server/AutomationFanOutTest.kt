package com.mockonomy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADB automation commands apply to all running servers; the contract of this combiner:
 * "success if it ran on at least one, skipped servers stay visible".
 */
class AutomationFanOutTest {

    @Test
    fun `if applied on one server the result is a success and skipped servers show in the message`() {
        val result = combineAutomationResults(
            listOf(
                "Auth" to AutomationResult.Ok("Active state set to 'login_fail'."),
                "Payments" to AutomationResult.BadRequest(
                    "unknown_state",
                    "State 'login_fail' is not defined in 'Payments'.",
                    listOf("ok", "declined"),
                ),
            ),
        )

        val ok = assertIs<AutomationResult.Ok>(result)
        assertTrue(ok.message.startsWith("Applied to 1 of 2 servers."), ok.message)
        assertTrue("Auth" in ok.message)
        assertTrue("Skipped 1" in ok.message && "Payments" in ok.message, ok.message)
    }

    @Test
    fun `if applied on none the first error is returned and options are merged`() {
        val result = combineAutomationResults(
            listOf(
                "Auth" to AutomationResult.BadRequest("unknown_state", "…", listOf("logged_in", "shared")),
                "Payments" to AutomationResult.BadRequest("unknown_state", "…", listOf("declined", "shared")),
            ),
        )

        val bad = assertIs<AutomationResult.BadRequest>(result)
        assertEquals("unknown_state", bad.code)
        assertEquals(listOf("logged_in", "shared", "declined"), bad.available)
    }

    @Test
    fun `a single result from a single server is passed through as is`() {
        val result = combineAutomationResults(
            listOf("Auth" to AutomationResult.NotFound("route_not_found", "missing", listOf("greet"))),
        )

        val notFound = assertIs<AutomationResult.NotFound>(result)
        assertEquals("route_not_found", notFound.code)
        assertEquals(listOf("greet"), notFound.available)
    }
}
