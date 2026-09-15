package com.mockonomy.ui.screens.logs

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mockonomy.BuildConfig
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.IdGenerator
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.data.log.RequestLogSummary
import com.mockonomy.data.log.export.HarExport
import com.mockonomy.engine.PathParameterization
import com.mockonomy.ui.navigation.MockonomyDestination
import com.mockonomy.ui.screens.routes.findRouteFromLog
import com.mockonomy.ui.screens.routes.httpMethodOf
import com.mockonomy.ui.screens.routes.withResponseFromLog
import com.mockonomy.ui.screens.routes.withRouteFromLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the request log screen. The list comes from Room's `Flow`; there is no separate
 * mechanism for the live stream — when a new entry is written the query re-emits itself.
 *
 * Filtering happens on the screen, not here: the text the user typed survives process death
 * via `rememberSaveable`, and filtering 1000 entries in memory is not a measurable cost.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val logStore: RequestLogStore,
    private val environmentStore: EnvironmentStore,
) : ViewModel() {

    private val environmentId =
        savedStateHandle.toRoute<MockonomyDestination.EnvironmentDetail>().environmentId

    /**
     * `false` until Room's flow emits for the first time. The UI should only draw the empty
     * state ("No requests yet") while this is `true` — otherwise the empty `emptyList()`
     * initial value would flash "no requests" for a frame.
     */
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()

    val logs: StateFlow<List<RequestLogSummary>> = logStore.observeSummaries(environmentId)
        .onEach { _initialLoadComplete.value = true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun clear() {
        viewModelScope.launch { logStore.clear(environmentId) }
    }

    /**
     * The HAR 1.2 form of the entries visible on screen.
     *
     * There is no separate selection mode for "selected entries": **the filter is the
     * selection.** When the user taps the `4xx` chip and exports, they get only the errors,
     * without marking rows one by one.
     */
    suspend fun buildHar(ids: List<String>): String =
        HarExport.of(logStore.findAll(ids), appVersion = BuildConfig.VERSION_NAME)

    /**
     * Adds mocks to this environment from the selected logs (bulk).
     *
     * The bulk flow is **automatic** (no dialog as in the single flow — that would be bad UX
     * with many entries): every concrete path is parameterized with [PathParameterization],
     * and if the same method+path already exists (including those added in this run) a new
     * response is added to that route; otherwise a new route is created. That way `/users/42`
     * and `/users/99` collect as two responses on a single `/users/:userId` route.
     *
     * Everything is written with a single [EnvironmentStore.save] — one atomic write instead
     * of n file writes for n entries. There is no target picker: the list already belongs to
     * this environment. [onDone] reports the number of processed entries.
     */
    fun createMocksFromLogs(ids: List<String>, onDone: (count: Int) -> Unit) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val environment = environmentStore.load(environmentId) ?: return@launch
            // Keep the selection order: use the order of `ids` the user marked, not the order
            // `findAll` returns.
            val logsById = logStore.findAll(ids).associateBy { it.id }
            val logs = ids.mapNotNull { logsById[it] }

            val updated = logs.fold(environment) { env, log ->
                val path = PathParameterization.suggest(log.path).suggestedPath
                val existing = env.findRouteFromLog(httpMethodOf(log.method), path)
                if (existing != null) {
                    env.withResponseFromLog(existing.id, IdGenerator.generate("resp"), log)
                } else {
                    env.withRouteFromLog(IdGenerator.generate("route"), IdGenerator.generate("resp"), log, path)
                }
            }

            environmentStore.save(updated)
            onDone(logs.size)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
