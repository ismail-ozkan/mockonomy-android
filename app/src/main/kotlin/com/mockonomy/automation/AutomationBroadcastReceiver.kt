package com.mockonomy.automation

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mockonomy.data.AdminApiSettingsStore
import com.mockonomy.server.AutomationCommand
import com.mockonomy.server.AutomationResult
import com.mockonomy.server.MockServerController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Automation setup from the ADB shell — covers what the HTTP API cannot reach: setting up
 * a scenario from the shell **before the test process starts**.
 *
 * ```bash
 * adb shell am broadcast -a com.mockonomy.automation.ACTIVATE \
 *   -n com.mockonomy/.automation.AutomationBroadcastReceiver --es env auth --es state login_fail
 * ```
 *
 * Operations use **the same** [AutomationCommandHandler] as the HTTP admin API
 * ([MockServerController.runAutomationCommand]), so the two never diverge. They sit behind
 * the same "automation" setting (`AdminApiSettingsStore.enabled`) — when it is off nothing
 * happens.
 *
 * **Known limitation:** on Android 12+ starting a foreground service from the background
 * is restricted; starting the server from a broadcast may not work reliably. So the
 * receiver does not start the server — it **configures** a running one. The path that
 * works: open the app first (the server starts), then configure it with broadcasts.
 *
 * `exported="true"` (manifest) is required for adb access on Android 12+. Security: any
 * app on the device can send broadcasts too, which is why the automation setting is off
 * by default and the user turns it on deliberately.
 */
@AndroidEntryPoint
class AutomationBroadcastReceiver : BroadcastReceiver() {

    @Inject lateinit var controller: MockServerController

    @Inject lateinit var settings: AdminApiSettingsStore

    // The receiver's own process-lifetime scope: keeps the work suspended by goAsync running.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val command = parseAutomationCommand(intent.action) { intent.getStringExtra(it) }
        val pending = goAsync()
        scope.launch {
            try {
                when {
                    !settings.settings.first().enabled ->
                        pending.finishWith(RESULT_DISABLED, "Automation API is turned off. Enable it in Settings.")
                    command == null ->
                        pending.finishWith(
                            RESULT_BAD_COMMAND,
                            "Unknown action or missing required extras: ${intent.action}",
                        )
                    else -> {
                        val result = controller.runAutomationCommand(command)
                        val code = if (result is AutomationResult.Ok) RESULT_OK else RESULT_REJECTED
                        pending.finishWith(code, result.describe())
                    }
                }
            } catch (error: Exception) {
                pending.finishWith(RESULT_BAD_COMMAND, error.message ?: error::class.simpleName.orEmpty())
            }
        }
    }

    private fun PendingResult.finishWith(code: Int, data: String) {
        setResult(code, data, null)
        finish()
    }

    companion object {
        const val ACTION_ACTIVATE = "com.mockonomy.automation.ACTIVATE"
        const val ACTION_SET_RESPONSE = "com.mockonomy.automation.SET_RESPONSE"
        const val ACTION_SET_STATE = "com.mockonomy.automation.SET_STATE"
        const val ACTION_RESET = "com.mockonomy.automation.RESET"
        const val ACTION_CLEAR_LOGS = "com.mockonomy.automation.CLEAR_LOGS"

        /** The `result=` in `am broadcast` output — a CI script can check it. */
        const val RESULT_OK = Activity.RESULT_OK // -1 (distinguishable from the default 0)
        const val RESULT_REJECTED = 1 // the operation failed (not found / bad / no environment)
        const val RESULT_DISABLED = 2 // the automation setting is off
        const val RESULT_BAD_COMMAND = 3 // unknown action or missing extra
    }
}

/** Also puts the error code into the data so it shows up in the `am broadcast` output. */
private fun AutomationResult.describe(): String = when (this) {
    is AutomationResult.Ok -> message
    is AutomationResult.NotFound -> "$code: $message" + available.joinAvailable()
    is AutomationResult.BadRequest -> "$code: $message" + available.joinAvailable()
    is AutomationResult.NoEnvironment -> "$code: $message"
}

private fun List<String>.joinAvailable(): String = if (isEmpty()) "" else " (available: ${joinToString(", ")})"

/**
 * Turns the Intent into a transport-neutral [AutomationCommand]; `null` for an unknown
 * action or a missing required extra. A pure function — testable without Android
 * ([com.mockonomy.automation.AutomationCommandParseTest]).
 */
internal fun parseAutomationCommand(action: String?, extra: (String) -> String?): AutomationCommand? = when (action) {
    AutomationBroadcastReceiver.ACTION_ACTIVATE ->
        extra("env")?.takeIf { it.isNotBlank() }
            ?.let { AutomationCommand.Activate(it, extra("state")?.takeIf { s -> s.isNotBlank() }) }

    AutomationBroadcastReceiver.ACTION_SET_RESPONSE -> {
        val route = extra("route")?.takeIf { it.isNotBlank() }
        val response = extra("response")?.takeIf { it.isNotBlank() }
        if (route != null && response != null) AutomationCommand.SetResponse(route, response) else null
    }

    AutomationBroadcastReceiver.ACTION_SET_STATE ->
        extra("state")?.takeIf { it.isNotBlank() }?.let { AutomationCommand.SetState(it) }

    AutomationBroadcastReceiver.ACTION_RESET -> AutomationCommand.Reset
    AutomationBroadcastReceiver.ACTION_CLEAR_LOGS -> AutomationCommand.ClearLogs
    else -> null
}
