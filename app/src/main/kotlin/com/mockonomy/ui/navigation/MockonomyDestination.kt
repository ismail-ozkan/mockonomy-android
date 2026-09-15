package com.mockonomy.ui.navigation

import kotlinx.serialization.Serializable

/**
 * The app's navigation tree:
 *
 * ```
 * Root: Environment list
 *   └── {environmentId}                    → EnvironmentDetail
 *         ├─ Routes   → {routeId}          → RouteEdit
 *         │              → {responseId}    → ResponseEdit
 *         ├─ Logs     → {logId}            → LogDetail
 *         └─ Settings
 * ```
 *
 * Routes/Logs/Settings are **not** separate nav destinations — they are inner tab state
 * inside `EnvironmentDetail` (see `EnvironmentDetailScreen`). Switching tabs does not add
 * a back stack entry; this matches how most mobile apps handle tabs and makes the back
 * button work screen by screen, not tab by tab.
 *
 * Only **ids** travel between screens, never objects — each screen reads the relevant
 * data again by id from `EnvironmentStore` in its own ViewModel.
 */
sealed interface MockonomyDestination {

    @Serializable
    data object EnvironmentList : MockonomyDestination

    /** Setup guide and connection diagnostics. */
    @Serializable
    data object SetupGuide : MockonomyDestination

    /** Import (OpenAPI and Mockoon). */
    @Serializable
    data object Import : MockonomyDestination

    /** Automation (admin API) settings. */
    @Serializable
    data object AutomationSettings : MockonomyDestination

    /** App settings — theme preference, version. */
    @Serializable
    data object Settings : MockonomyDestination

    /** Open source licenses — reached from Settings. */
    @Serializable
    data object OpenSourceLicenses : MockonomyDestination

    /** The full text of a single license; `assetPath` is a `licenses/` file in the assets. */
    @Serializable
    data class LicenseText(val title: String, val assetPath: String) : MockonomyDestination

    @Serializable
    data class EnvironmentDetail(val environmentId: String) : MockonomyDestination

    @Serializable
    data class RouteEdit(val environmentId: String, val routeId: String) : MockonomyDestination

    /** Log detail and match explanation. */
    @Serializable
    data class LogDetail(val environmentId: String, val logId: String) : MockonomyDestination

    @Serializable
    data class ResponseEdit(
        val environmentId: String,
        val routeId: String,
        val responseId: String,
    ) : MockonomyDestination
}
