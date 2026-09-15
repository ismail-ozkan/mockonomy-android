package com.mockonomy.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.server.MockServerController
import com.mockonomy.server.RunningServer
import com.mockonomy.ui.screens.setup.DiagnosticStep.Outcome
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The setup guide and the "Test connection" diagnostics.
 *
 * The notification permission comes from outside as [notificationsEnabled]: the permission
 * state is the Android framework's job, and the ViewModel does not need to depend on a
 * `Context`.
 */
@HiltViewModel
class SetupGuideViewModel @Inject constructor(
    private val environmentStore: EnvironmentStore,
    private val serverController: MockServerController,
    private val probe: LoopbackProbe,
) : ViewModel() {

    private val _report = MutableStateFlow(DiagnosticsReport(steps = emptyList()))
    val report: StateFlow<DiagnosticsReport> = _report.asStateFlow()

    fun runDiagnostics(notificationsEnabled: Boolean) {
        viewModelScope.launch {
            _report.value = DiagnosticsReport(steps = emptyList(), running = true)
            val steps = mutableListOf<DiagnosticStep>()

            // With multiple servers the diagnostics run separately for EACH running server.
            // Looking at only one would tell a user whose second server is dead that
            // "everything is fine".
            val servers = serverController.running.value
            if (servers.isEmpty()) {
                steps += notRunningStep()
                _report.value = DiagnosticsReport(steps.toList(), running = true)
            }
            servers.forEach { server ->
                steps += serverStep(server)
                _report.value = DiagnosticsReport(steps.toList(), running = true)

                steps += portStep(server.port)
                _report.value = DiagnosticsReport(steps.toList(), running = true)

                steps += requestStep(server)
                _report.value = DiagnosticsReport(steps.toList(), running = true)
            }

            steps += notificationStep(notificationsEnabled)
            _report.value = DiagnosticsReport(steps.toList(), running = false)
        }
    }

    private fun serverStep(server: RunningServer) = DiagnosticStep(
        title = "${server.environmentName} is running",
        outcome = Outcome.Passed,
        detail = "Serving on 127.0.0.1:${server.port}",
    )

    private fun notRunningStep() = DiagnosticStep(
        title = "No server is running",
        outcome = Outcome.Failed,
        detail = "Start an environment from the list, then run this check again.",
    )

    private suspend fun portStep(port: Int) = if (probe.isPortOpen(port)) {
        DiagnosticStep(
            title = "Port $port is listening",
            outcome = Outcome.Passed,
            detail = "A connection to 127.0.0.1:$port succeeded.",
        )
    } else {
        DiagnosticStep(
            title = "Port $port is not reachable",
            outcome = Outcome.Failed,
            detail = "Another app may be holding the port. Try a different port in environment settings.",
        )
    }

    /**
     * The sample request goes to the environment's **own** first GET route. With a fixed
     * `/ping`, an environment full of the user's own routes would always return 404 and the
     * diagnostics would be misleading.
     */
    private suspend fun requestStep(server: RunningServer): DiagnosticStep {
        val port = server.port
        val path = firstGetPath(server.environmentId)
            ?: return DiagnosticStep(
                title = "No route to try",
                outcome = Outcome.Warned,
                detail = "This environment has no enabled GET route, so the request check was skipped.",
            )

        return when (val status = probe.statusCodeOf(port, path)) {
            null -> DiagnosticStep(
                title = "Request to $path failed",
                outcome = Outcome.Failed,
                detail = "The server did not answer. Try stopping and starting it again.",
            )

            in 200..399 -> DiagnosticStep(
                title = "GET $path answered $status",
                outcome = Outcome.Passed,
                detail = "The engine matched the route and returned a response.",
            )

            else -> DiagnosticStep(
                title = "GET $path answered $status",
                outcome = Outcome.Warned,
                detail = "The server is up but this route did not match. Check the method and path.",
            )
        }
    }

    private suspend fun firstGetPath(environmentId: String): String? {
        val environment: Environment = environmentStore.load(environmentId) ?: return null
        // A parameterized path (`/users/:id`) requested as is would not match, and the
        // diagnostics would wrongly say "route does not match". A parameterless one is picked.
        val route = environment.routes.firstOrNull {
            it.enabled && it.method == HttpMethod.GET && ':' !in it.path
        } ?: return null
        return environment.prefix + route.path
    }

    private fun notificationStep(enabled: Boolean) = if (enabled) {
        DiagnosticStep(
            title = "Notifications are allowed",
            outcome = Outcome.Passed,
            detail = "You can stop the server from the notification.",
        )
    } else {
        DiagnosticStep(
            title = "Notifications are blocked",
            outcome = Outcome.Warned,
            detail = "The server still runs, but you can only stop it from this app.",
        )
    }
}
