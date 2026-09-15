package com.mockonomy.ui.screens.routes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mockonomy.data.BodyStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.IdGenerator
import com.mockonomy.engine.model.Route
import com.mockonomy.ui.editing.EnvironmentDocument
import com.mockonomy.ui.navigation.MockonomyDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Route editing.
 *
 * It belongs to its own back stack entry, so it does not share an instance with the detail
 * screen; since both write the same file, they are synchronized through [EnvironmentDocument].
 */
@HiltViewModel
class RouteEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    environmentStore: EnvironmentStore,
    private val bodyStore: BodyStore,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<MockonomyDestination.RouteEdit>()

    private val document = EnvironmentDocument(
        environmentId = destination.environmentId,
        environmentStore = environmentStore,
        scope = viewModelScope,
    )

    /** The route being edited — `null` if it was deleted or cannot be found. */
    val route: StateFlow<Route?> = document.state
        .map { environment -> environment?.routes?.firstOrNull { it.id == destination.routeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * For the path preview: the full served address is built with the environment's port and
     * prefix. It answers "at which URL will this route be served" before saving — the most
     * common confusion about prefix and port.
     */
    val servedOrigin: StateFlow<String> = document.state
        .map { environment ->
            environment?.let { "127.0.0.1:${it.port}${it.prefix}" } ?: ""
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    /**
     * For the conflict warning: is there *another* route with the same method + path? It is
     * not blocked, only warned about — with rules it is a legitimate use.
     */
    val hasConflict: StateFlow<Boolean> = document.state
        .map { it?.hasRouteConflict(destination.routeId) ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun update(transform: (Route) -> Route) =
        document.update { it.withRouteTransformed(destination.routeId, transform) }

    /** Structural changes such as adding/deleting a response are written without waiting. */
    fun updateNow(transform: (Route) -> Route) =
        document.updateNow { it.withRouteTransformed(destination.routeId, transform) }

    fun addResponse() = updateNow { it.withResponseAdded(IdGenerator.generate("resp")) }

    fun deleteResponse(responseId: String) {
        // The body may have been moved to a file; when the response goes, the file must go too.
        route.value
            ?.responses?.firstOrNull { it.id == responseId }
            ?.bodyFile
            ?.let { fileName -> viewModelScope.launch { bodyStore.delete(fileName) } }

        updateNow { it.withResponseDeleted(responseId) }
    }

    fun selectDefaultResponse(responseId: String) = updateNow { it.withDefaultResponse(responseId) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
