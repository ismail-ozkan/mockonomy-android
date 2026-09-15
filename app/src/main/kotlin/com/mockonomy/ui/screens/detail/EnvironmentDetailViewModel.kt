package com.mockonomy.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.IdGenerator
import com.mockonomy.data.ProxyPrivacyStore
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.server.MockServerController
import com.mockonomy.server.ServerFailure
import com.mockonomy.server.ServerLauncher
import com.mockonomy.ui.editing.EnvironmentDocument
import com.mockonomy.ui.navigation.MockonomyDestination
import com.mockonomy.ui.screens.routes.withDefaultResponse
import com.mockonomy.ui.screens.settings.withInitialState
import com.mockonomy.ui.screens.settings.withStateAdded
import com.mockonomy.ui.screens.settings.withStateDeleted
import com.mockonomy.ui.screens.settings.withStateRenamed
import com.mockonomy.ui.screens.routes.withRouteAdded
import com.mockonomy.ui.screens.routes.withRouteDeleted
import com.mockonomy.ui.screens.routes.withRouteEnabled
import com.mockonomy.ui.screens.routes.withRouteMoved
import com.mockonomy.ui.screens.routes.withRouteTransformed
import com.mockonomy.ui.screens.toggleEnvironmentRunning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the whole `{environmentId}` node — the Routes and Settings tabs share **the
 * same** instance (`hiltViewModel()` is tied to the NavBackStackEntry).
 *
 * There used to be a separate ViewModel for settings; once the routes tab arrived both
 * would write the same `Environment` object from their own copy, and the last writer
 * overwrote the other's change. Ownership moved to this single class — the fix was
 * deleting the second one, not adding an abstraction.
 *
 * File-level synchronization is [EnvironmentDocument]'s job.
 */
@HiltViewModel
class EnvironmentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    environmentStore: EnvironmentStore,
    private val serverLauncher: ServerLauncher,
    private val proxyPrivacyStore: ProxyPrivacyStore,
    private val serverController: MockServerController,
) : ViewModel() {

    private val environmentId =
        savedStateHandle.toRoute<MockonomyDestination.EnvironmentDetail>().environmentId

    private val document = EnvironmentDocument(
        environmentId = environmentId,
        environmentStore = environmentStore,
        scope = viewModelScope,
    )

    val environment: StateFlow<Environment?> = document.state

    /** While `environment == null`: is it still being read, or is the file gone? */
    val loaded: StateFlow<Boolean> = document.loaded

    /** For Start/Stop in the app bar: is this environment's server running? */
    val isRunning: StateFlow<Boolean> = serverController.running
        .map { servers -> servers.any { it.environmentId == environmentId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * The live active state of this environment's state machine. `null` when its server
     * stops.
     */
    val activeState: StateFlow<String?> = serverController.activeStates
        .map { it[environmentId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * This environment's server errors. The port is edited **here**, so a hot reload rejected
     * because of a conflicting or out-of-range port must show up here too — if it were only
     * shown on the list screen, the user would see nothing while in settings and would not
     * understand why the server stayed on the old port.
     */
    val failures: Flow<ServerFailure> = serverController.failures
        .filter { it.environmentId == environmentId }

    /** Changes the active state by hand — the control QA uses most during manual testing. */
    fun setActiveState(state: String) = serverController.setActiveState(environmentId, state)

    /** Whether the proxy recording privacy warning has been shown before. */
    val proxyRecordingWarningShown: StateFlow<Boolean> = proxyPrivacyStore.recordingWarningShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    /** Marks the warning as shown; it does not appear again. */
    fun markProxyRecordingWarningShown() {
        viewModelScope.launch { proxyPrivacyStore.markRecordingWarningShown() }
    }

    fun update(transform: (Environment) -> Environment) = document.update(transform)

    // --- State machine — the logic lives in `settings/StateEdits.kt`, pure ---

    /** Adds a new empty state; the user types its name afterwards. */
    fun addState() = document.updateNow { it.withStateAdded() }

    /** Renames a state; whenState/setState/initialState references are updated together. */
    fun renameState(index: Int, name: String) = document.update { it.withStateRenamed(index, name) }

    /** Deletes a state; references to it are cleared. */
    fun deleteState(index: Int) = document.updateNow { it.withStateDeleted(index) }

    /** Picks the initial state. */
    fun setInitialState(state: String?) = document.updateNow { it.withInitialState(state) }

    fun toggleRunning() = toggleEnvironmentRunning(environmentId, serverController, serverLauncher)

    // --- Route operations — the logic lives in `RouteEdits.kt`, pure and testable ---

    /**
     * An empty route is useless; it comes with a ready 200 response so quick add really
     * produces a working mock.
     */
    fun addRoute(method: HttpMethod, path: String) = document.updateNow {
        it.withRouteAdded(
            routeId = IdGenerator.generate("route"),
            responseId = IdGenerator.generate("resp"),
            method = method,
            path = path,
        )
    }

    fun setRouteEnabled(routeId: String, enabled: Boolean) =
        document.updateNow { it.withRouteEnabled(routeId, enabled) }

    fun deleteRoute(routeId: String) = document.updateNow { it.withRouteDeleted(routeId) }

    fun moveRoute(routeId: String, delta: Int) = document.updateNow { it.withRouteMoved(routeId, delta) }

    /** The swap button in the route list — switches the active response without changing screens. */
    fun setActiveResponse(routeId: String, responseId: String) = document.updateNow {
        it.withRouteTransformed(routeId) { route -> route.withDefaultResponse(responseId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
