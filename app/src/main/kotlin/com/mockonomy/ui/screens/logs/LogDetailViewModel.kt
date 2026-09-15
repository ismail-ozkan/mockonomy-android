package com.mockonomy.ui.screens.logs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.IdGenerator
import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.ui.navigation.MockonomyDestination
import com.mockonomy.ui.screens.routes.findRouteFromLog
import com.mockonomy.ui.screens.routes.httpMethodOf
import com.mockonomy.ui.screens.routes.routeSignature
import com.mockonomy.ui.screens.routes.withDefaultResponseFromLog
import com.mockonomy.ui.screens.routes.withResponseFromLog
import com.mockonomy.ui.screens.routes.withRouteFromLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the log detail screen. The entry does not change — it is read once, no flow is
 * observed.
 *
 * The environment is read too, because the trace only carries ids: the response's name,
 * status code and `AND`/`OR` choice live in the configuration. The user may have deleted
 * the response meanwhile; in that case the explanation shows the id.
 */
@HiltViewModel
class LogDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    logStore: RequestLogStore,
    private val environmentStore: EnvironmentStore,
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<MockonomyDestination.LogDetail>()

    val currentEnvironmentId: String get() = destination.environmentId

    private val _log = MutableStateFlow<RequestLog?>(null)
    val log: StateFlow<RequestLog?> = _log.asStateFlow()

    /** While `log == null`: is it still being read, or was the entry pruned? */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Valid target environments for "Create mock". Corrupt files are left out — we cannot
     * write a mock to a file we cannot read.
     */
    private val _targets = MutableStateFlow<List<MockTarget>>(emptyList())
    val targets: StateFlow<List<MockTarget>> = _targets.asStateFlow()

    init {
        viewModelScope.launch {
            _log.value = logStore.find(destination.logId)
            _loaded.value = true
            _targets.value = environmentStore.list()
                .filterIsInstance<EnvironmentSummary.Valid>()
                .map { summary ->
                    MockTarget(
                        id = summary.environment.id,
                        name = summary.environment.name,
                        routeKeys = summary.environment.routes
                            .map { routeSignature(it.method, it.path) }
                            .toSet(),
                    )
                }
        }
    }

    /**
     * Adds a mock from this log to [targetEnvironmentId] at [path] and saves it, then takes
     * the caller to the edit screen of the route.
     *
     * [path] may be literal or parameterized (`/users/:userId`). [dedup] decides what to do
     * when the same method+path already exists; without a conflict (or if the route is not
     * found) a new route is always created.
     */
    fun createMock(
        targetEnvironmentId: String,
        path: String,
        dedup: DedupMode,
        onCreated: (environmentId: String, routeId: String) -> Unit,
    ) {
        val log = _log.value ?: return
        viewModelScope.launch {
            val target = environmentStore.load(targetEnvironmentId) ?: return@launch
            val existing = target.findRouteFromLog(httpMethodOf(log.method), path)

            val (updated, routeId) = when {
                existing != null && dedup == DedupMode.AddResponse ->
                    target.withResponseFromLog(existing.id, IdGenerator.generate("resp"), log) to existing.id
                existing != null && dedup == DedupMode.UpdateResponse ->
                    target.withDefaultResponseFromLog(existing.id, log) to existing.id
                else -> {
                    val newId = IdGenerator.generate("route")
                    target.withRouteFromLog(newId, IdGenerator.generate("resp"), log, path) to newId
                }
            }

            environmentStore.save(updated)
            onCreated(targetEnvironmentId, routeId)
        }
    }
}

/**
 * An environment shown in the "Create mock" target picker. [routeKeys] are the signatures
 * of existing routes ([routeSignature]), used for conflict detection.
 */
data class MockTarget(val id: String, val name: String, val routeKeys: Set<String>)

/** How to create a mock from an entry when the same method+path already exists. */
enum class DedupMode { NewRoute, AddResponse, UpdateResponse }
