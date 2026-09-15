package com.mockonomy.automation

import com.mockonomy.server.AutomationCommand
import com.mockonomy.automation.AutomationBroadcastReceiver.Companion.ACTION_ACTIVATE
import com.mockonomy.automation.AutomationBroadcastReceiver.Companion.ACTION_CLEAR_LOGS
import com.mockonomy.automation.AutomationBroadcastReceiver.Companion.ACTION_RESET
import com.mockonomy.automation.AutomationBroadcastReceiver.Companion.ACTION_SET_RESPONSE
import com.mockonomy.automation.AutomationBroadcastReceiver.Companion.ACTION_SET_STATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Parsing intents into commands (pure, without Android). */
class AutomationCommandParseTest {

    private fun parse(action: String?, vararg extras: Pair<String, String>): AutomationCommand? {
        val map = extras.toMap()
        return parseAutomationCommand(action) { map[it] }
    }

    @Test
    fun `ACTIVATE with env and an optional state`() {
        assertEquals(AutomationCommand.Activate("auth"), parse(ACTION_ACTIVATE, "env" to "auth"))
        assertEquals(
            AutomationCommand.Activate("auth", "login_fail"),
            parse(ACTION_ACTIVATE, "env" to "auth", "state" to "login_fail"),
        )
    }

    @Test
    fun `ACTIVATE without env is null`() {
        assertNull(parse(ACTION_ACTIVATE))
        assertNull(parse(ACTION_ACTIVATE, "env" to "  "))
    }

    @Test
    fun `SET_RESPONSE requires route and response`() {
        assertEquals(
            AutomationCommand.SetResponse("get-user", "not-found"),
            parse(ACTION_SET_RESPONSE, "route" to "get-user", "response" to "not-found"),
        )
        assertNull(parse(ACTION_SET_RESPONSE, "route" to "get-user"))
        assertNull(parse(ACTION_SET_RESPONSE, "response" to "not-found"))
    }

    @Test
    fun `SET_STATE requires state`() {
        assertEquals(AutomationCommand.SetState("logged_in"), parse(ACTION_SET_STATE, "state" to "logged_in"))
        assertNull(parse(ACTION_SET_STATE))
    }

    @Test
    fun `RESET and CLEAR_LOGS need no extras`() {
        assertEquals(AutomationCommand.Reset, parse(ACTION_RESET))
        assertEquals(AutomationCommand.ClearLogs, parse(ACTION_CLEAR_LOGS))
    }

    @Test
    fun `unknown action is null`() {
        assertNull(parse("com.mockonomy.automation.NOPE"))
        assertNull(parse(null))
    }
}
