package com.mockonomy.server

import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse

/** Addressing: `key` → `id` (either may match). The engine does not know `key`; it is resolved here. */
internal fun Iterable<Environment>.resolveEnvironment(key: String): Environment? =
    firstOrNull { it.key == key || it.id == key }

internal fun Environment.resolveRoute(key: String): Route? =
    routes.firstOrNull { it.key == key || it.id == key }

internal fun Route.resolveResponse(ref: String): RouteResponse? =
    responses.firstOrNull { it.key == ref || it.id == ref }

/** The readable identity of a route/response (`key` if set, otherwise `id`). */
internal fun Route.ref(): String = key ?: id
internal fun RouteResponse.ref(): String = key ?: id
internal fun Environment.ref(): String = key ?: id

/**
 * The **single implementation** of automation operations. The HTTP admin API
 * ([AdminApi]) and the ADB broadcast receiver use the same [AutomationCommandHandler], so
 * "activate/setResponse/setState/reset/clearLogs" behavior never diverges between the
 * two surfaces.
 *
 * [control] is the runtime control surface of the running server. **The gate
 * (enabled/token) lives in the adapters, not here** — the HTTP side also checks
 * `X-Mockonomy-Token`, the ADB side only looks at `enabled`; moving that into the shared
 * logic would blur the contract.
 */
internal class AutomationCommandHandler(
    private val control: AdminControl,
    private val environmentStore: EnvironmentStore,
    private val logStore: RequestLogStore,
) {

    suspend fun handle(command: AutomationCommand): AutomationResult = when (command) {
        is AutomationCommand.Activate -> activate(command)
        is AutomationCommand.SetResponse -> setResponse(command)
        is AutomationCommand.SetState -> setState(command)
        AutomationCommand.Reset -> reset()
        AutomationCommand.ClearLogs -> clearLogs()
    }

    private suspend fun activate(command: AutomationCommand.Activate): AutomationResult {
        val environments = environmentStore.list()
            .filterIsInstance<EnvironmentSummary.Valid>()
            .map { it.environment }
        val target = environments.resolveEnvironment(command.environmentKey)
            ?: return AutomationResult.NotFound(
                "environment_not_found",
                "No environment with key or id '${command.environmentKey}'.",
                environments.map { it.ref() },
            )
        if (control.currentEnvironment == null) {
            // Known limitation: a broadcast/HTTP call cannot reliably start the server. The
            // app has to be opened and the server started first.
            return AutomationResult.NoEnvironment(
                "No server is running. Start it first — automation cannot start the server reliably.",
            )
        }
        command.state?.let { requested ->
            if (requested !in target.states) {
                return AutomationResult.BadRequest(
                    "unknown_state",
                    "State '$requested' is not defined in '${target.name}'.",
                    target.states,
                )
            }
        }
        control.activate(target)
        command.state?.let { control.setState(it) }
        return AutomationResult.Ok("Activated '${target.name}'.")
    }

    private fun setResponse(command: AutomationCommand.SetResponse): AutomationResult {
        val env = control.currentEnvironment ?: return AutomationResult.NoEnvironment()
        val route = env.resolveRoute(command.routeKey)
            ?: return AutomationResult.NotFound(
                "route_not_found",
                "No route with key or id '${command.routeKey}' in '${env.name}'.",
                env.routes.map { it.ref() },
            )
        val response = route.resolveResponse(command.responseRef)
            ?: return AutomationResult.NotFound(
                "response_not_found",
                "No response with key or id '${command.responseRef}' in route '${route.ref()}'.",
                route.responses.map { it.ref() },
            )
        control.overrideResponse(route.id, response.id)
        return AutomationResult.Ok("Route '${route.ref()}' pinned to response '${response.ref()}'.")
    }

    private fun setState(command: AutomationCommand.SetState): AutomationResult {
        val env = control.currentEnvironment ?: return AutomationResult.NoEnvironment()
        if (command.state !in env.states) {
            return AutomationResult.BadRequest(
                "unknown_state",
                "State '${command.state}' is not defined in '${env.name}'.",
                env.states,
            )
        }
        control.setState(command.state)
        return AutomationResult.Ok("Active state set to '${command.state}'.")
    }

    private fun reset(): AutomationResult {
        control.currentEnvironment ?: return AutomationResult.NoEnvironment()
        control.resetSession()
        return AutomationResult.Ok("Request counters, active state and response overrides were reset.")
    }

    private suspend fun clearLogs(): AutomationResult {
        val env = control.currentEnvironment ?: return AutomationResult.NoEnvironment()
        logStore.clear(env.id)
        return AutomationResult.Ok("Logs cleared for '${env.name}'.")
    }
}
