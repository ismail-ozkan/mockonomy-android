package com.mockonomy.server

/**
 * An **automation operation** on a running mock server. Transport-neutral: both the HTTP
 * admin API ([AdminApi]) and the ADB broadcast receiver produce these commands and hand
 * them to [AutomationCommandHandler] — the logic lives in one place, with no drift
 * between the two surfaces.
 *
 * Resolving `key`/`id` (addressing environments/routes/responses) is the handler's job.
 */
sealed interface AutomationCommand {
    /** Activate an environment (hot swap on the running socket); optional initial state. */
    data class Activate(val environmentKey: String, val state: String? = null) : AutomationCommand

    /** Pin a route to a specific response (forced override). */
    data class SetResponse(val routeKey: String, val responseRef: String) : AutomationCommand

    /** Change the active state. */
    data class SetState(val state: String) : AutomationCommand

    /** Reset counters, state and overrides (test isolation). */
    data object Reset : AutomationCommand

    /** Clear the logs of the running environment. */
    data object ClearLogs : AutomationCommand
}

/**
 * The result of an [AutomationCommand]. [code] is the wire error code (used as is in the
 * HTTP JSON and in the ADB result data); [available] lists the existing options for an
 * unknown key/state (makes debugging easier).
 */
sealed interface AutomationResult {
    val message: String

    data class Ok(override val message: String) : AutomationResult

    data class NotFound(
        val code: String,
        override val message: String,
        val available: List<String>,
    ) : AutomationResult

    data class BadRequest(
        val code: String,
        override val message: String,
        val available: List<String> = emptyList(),
    ) : AutomationResult

    data class NoEnvironment(
        override val message: String = "No environment is currently running.",
    ) : AutomationResult {
        val code: String = "no_environment"
    }
}
