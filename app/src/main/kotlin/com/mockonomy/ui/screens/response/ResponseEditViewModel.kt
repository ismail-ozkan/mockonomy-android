package com.mockonomy.ui.screens.response

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mockonomy.data.BodyStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.applyBody
import com.mockonomy.data.loadBody
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.ui.editing.EnvironmentDocument
import com.mockonomy.ui.navigation.MockonomyDestination
import com.mockonomy.ui.screens.routes.withRouteTransformed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Response editing.
 *
 * The body is kept **separately** from the response itself ([body]): bodies over 100 KB live
 * in a file and `RouteResponse.body` is empty. The text the editor shows comes from here in
 * both cases; where it is stored is not something the user needs to know.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ResponseEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    environmentStore: EnvironmentStore,
    private val bodyStore: BodyStore,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<MockonomyDestination.ResponseEdit>()

    private val document = EnvironmentDocument(
        environmentId = destination.environmentId,
        environmentStore = environmentStore,
        scope = viewModelScope,
    )

    val response: StateFlow<RouteResponse?> = document.state
        .map { environment -> environment.findResponse() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The environment's defined states. They feed the whenState/setState dropdowns; if empty,
     * the state fields are not shown under Advanced at all.
     */
    val states: StateFlow<List<String>> = document.state
        .map { it?.states ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** The app bar's second line — tells which route's response is being edited. */
    val routeSummary: StateFlow<String> = document.state
        .map { environment ->
            environment?.routes?.firstOrNull { it.id == destination.routeId }
                ?.let { "${it.method.name} ${it.path}" }
                .orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    private val _body = MutableStateFlow("")
    val body: StateFlow<String> = _body.asStateFlow()

    /** So the user sees what is going on while loading from a file/clipboard. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** For why `DROP_OLDEST`, see [EnvironmentDocument] — the same trap. */
    private val bodyWrites = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        // The body is read only once, on open: after that the text in the editor is the single
        // source of truth. Reading from the file again would erase what the user is typing.
        viewModelScope.launch {
            _body.value = bodyStore.loadBody(response.filterNotNull().first())
        }

        viewModelScope.launch {
            bodyWrites.debounce(BODY_DEBOUNCE_MS).collect { persistBody(it, immediate = false) }
        }
    }

    fun updateResponse(transform: (RouteResponse) -> RouteResponse) {
        document.update { environment -> environment.mapResponse(transform) }
    }

    fun updateBody(text: String) {
        _body.value = text
        bodyWrites.tryEmit(text)
    }

    /**
     * Large text coming from a file/clipboard. No debounce wait: the user may load a 1 MB body
     * and go back immediately.
     */
    fun replaceBody(text: String) {
        _body.value = text
        viewModelScope.launch {
            _loading.value = true
            persistBody(text, immediate = true)
            _loading.value = false
        }
    }

    /**
     * The threshold decision is not here but in `applyBody`: whether the body lives inline or
     * in a file under `bodies/` is decided in a single place.
     */
    private suspend fun persistBody(text: String, immediate: Boolean) {
        val current = response.value ?: return
        val stored = bodyStore.applyBody(current, text)
        val updated = (document.state.value ?: return).mapResponse { stored }

        if (immediate) document.replace(updated) else document.update { updated }
    }

    private fun Environment?.findResponse(): RouteResponse? = this
        ?.routes?.firstOrNull { it.id == destination.routeId }
        ?.responses?.firstOrNull { it.id == destination.responseId }

    private fun Environment.mapResponse(transform: (RouteResponse) -> RouteResponse): Environment =
        withRouteTransformed(destination.routeId) { route ->
            route.copy(
                responses = route.responses.map { if (it.id == destination.responseId) transform(it) else it },
            )
        }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val BODY_DEBOUNCE_MS = 500L
    }
}
