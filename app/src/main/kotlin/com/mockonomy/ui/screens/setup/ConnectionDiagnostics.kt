package com.mockonomy.ui.screens.setup

/**
 * A single step of the "Test connection" diagnostics.
 *
 * Every step carries what it is **and** what to do if it fails; saying "failed" on its own
 * does not move the user a step forward.
 */
data class DiagnosticStep(
    val title: String,
    val outcome: Outcome,
    val detail: String,
) {
    sealed interface Outcome {
        data object Pending : Outcome
        data object Running : Outcome
        data object Passed : Outcome

        /** A warning: does not stop the flow, but the user needs to know. */
        data object Warned : Outcome
        data object Failed : Outcome
    }
}

/**
 * The full diagnostics.
 *
 * [clientNotice] is always shown, even on success: these diagnostics run from *inside*
 * Mockonomy and cannot verify the client app's cleartext configuration. A false "everything
 * is fine" message is worse than no diagnostics at all.
 */
data class DiagnosticsReport(
    val steps: List<DiagnosticStep>,
    val running: Boolean = false,
) {
    val clientNotice: String =
        "These checks run inside Mockonomy, so they can't verify your client app. " +
            "If the server side passes but your app still can't connect, apply the setup guide below."
}
