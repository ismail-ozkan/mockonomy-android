package com.mockonomy.ui.screens.environmentlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockonomy.AppReadiness
import com.mockonomy.data.BodyStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.ExampleEnvironment
import com.mockonomy.data.deleteBodiesOf
import com.mockonomy.data.duplicateEnvironment
import com.mockonomy.data.exportEnvironment
import com.mockonomy.data.hasSensitiveHeaders
import com.mockonomy.data.IdGenerator
import com.mockonomy.data.OnboardingStore
import com.mockonomy.data.SchemaTooNewException
import com.mockonomy.engine.model.Environment
import com.mockonomy.server.MockServerController
import com.mockonomy.server.RunningServer
import com.mockonomy.server.ServerFailure
import com.mockonomy.server.ServerLauncher
import com.mockonomy.ui.screens.toggleEnvironmentRunning
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The environment list (the root). The single source of truth is [EnvironmentStore]; this
 * ViewModel only writes UI events to it and reads back with [refresh] (`EnvironmentStore`
 * works with suspend functions, so this is a StateFlow refreshed by hand after mutations,
 * not a Flow).
 *
 * Servers are started/stopped through [ServerLauncher] — not directly with a `Context`,
 * because [MockServerService] must only be started through itself, not from an
 * Activity/ViewModel (background restrictions).
 */
@HiltViewModel
class EnvironmentListViewModel @Inject constructor(
    private val environmentStore: EnvironmentStore,
    private val onboardingStore: OnboardingStore,
    private val bodyStore: BodyStore,
    private val serverController: MockServerController,
    private val serverLauncher: ServerLauncher,
    private val readiness: AppReadiness,
) : ViewModel() {

    private val _environments = MutableStateFlow<List<EnvironmentSummary>>(emptyList())
    val environments: StateFlow<List<EnvironmentSummary>> = _environments.asStateFlow()

    /**
     * `false` until the first `list()` call finishes. The UI should only draw the empty state
     * while this is `true` — otherwise, with large environment files (seen on a device with
     * ~8 MB), `MAX_SPLASH_HOLD_MS` runs out, the screen opens while `environments` is still
     * empty and the "No environments yet" flash appears. It stays `true` after `refresh()`:
     * the signal means "the first read finished", not a state.
     */
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()

    /**
     * Running servers (there can be several). The badge on a list row looks for its id in
     * here; the strip at the top draws each server separately.
     */
    val runningServers: StateFlow<List<RunningServer>> = serverController.running

    /** Start failures — including port conflicts. One-off: the screen shows a snackbar. */
    val failures: SharedFlow<ServerFailure> = serverController.failures

    /**
     * Rows of the "SERVING" strip at the top: the address and (if any) state machine of each
     * running environment. Combined into a single flow so the screen does not have to match
     * three separate flows by hand.
     */
    val servingRows: StateFlow<List<ServingRow>> = combine(
        environments,
        serverController.running,
        serverController.activeStates,
    ) { summaries, servers, activeStates ->
        servers.map { server ->
            val environment = summaries.filterIsInstance<EnvironmentSummary.Valid>()
                .firstOrNull { it.environment.id == server.environmentId }
                ?.environment
            ServingRow(
                environmentId = server.environmentId,
                name = server.environmentName,
                address = "127.0.0.1:${server.port}",
                states = environment?.states ?: emptyList(),
                activeState = activeStates[server.environmentId],
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Changes an environment's active state by hand (quick action). */
    fun setActiveState(environmentId: String, state: String) =
        serverController.setActiveState(environmentId, state)

    /**
     * [refresh] waits for this: a list read before seeding finishes comes back empty and the
     * screen's first frame would be wrong.
     */
    private val seeding: Job

    init {
        seeding = seedExampleOnFirstLaunch()
    }

    /**
     * On first launch the example environment comes preloaded — a user facing an empty list
     * cannot understand what the product does.
     *
     * The decision looks at a persistent flag, not at "is the list empty": giving the example
     * back on every launch to a user who deliberately deleted it would make deleting pointless.
     */
    private fun seedExampleOnFirstLaunch(): Job =
        viewModelScope.launch {
            if (!onboardingStore.exampleSeeded.first()) {
                environmentStore.save(ExampleEnvironment.create())
                onboardingStore.markExampleSeeded()
            }
            _environments.value = environmentStore.list()
            _initialLoadComplete.value = true
            // The splash screen stays until here: before this line the list is empty and the
            // screen would briefly show the "No environments yet" empty state.
            readiness.markReady()
        }

    fun refresh() {
        viewModelScope.launch {
            seeding.join()
            _environments.value = environmentStore.list()
        }
    }

    fun toggleRunning(id: String) = toggleEnvironmentRunning(id, serverController, serverLauncher)

    fun create(name: String) {
        viewModelScope.launch {
            environmentStore.save(
                Environment(
                    schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
                    id = IdGenerator.generate("env"),
                    name = name,
                    port = Environment.DEFAULT_PORT,
                    routes = emptyList(),
                ),
            )
            refresh()
        }
    }

    /** The empty state's "load example" action — to bring back a deleted example. */
    fun loadExample() {
        viewModelScope.launch {
            environmentStore.save(ExampleEnvironment.create())
            refresh()
        }
    }

    fun rename(id: String, newName: String) {
        viewModelScope.launch {
            val environment = environmentStore.load(id) ?: return@launch
            environmentStore.save(environment.copy(name = newName))
            refresh()
        }
    }

    fun duplicate(id: String) {
        viewModelScope.launch {
            val source = environmentStore.load(id) ?: return@launch
            environmentStore.save(duplicateEnvironment(source, bodyStore))
            refresh()
        }
    }

    /**
     * The export JSON. The composable writes it to the file picked via SAF (the same pattern
     * as the HAR flow). `null` if not found.
     */
    suspend fun exportJson(id: String, redact: Boolean): String? {
        val environment = environmentStore.load(id) ?: return null
        return exportEnvironment(environment, bodyStore, redact)
    }

    /** For the export dialog: is there any header to mask? */
    suspend fun containsSensitiveHeaders(id: String): Boolean =
        environmentStore.load(id)?.let { hasSensitiveHeaders(it) } ?: false

    /** Imports a JSON file and reports the result. */
    fun importEnvironment(json: String, onDone: (ImportOutcome) -> Unit) {
        viewModelScope.launch {
            val outcome = try {
                val environment = com.mockonomy.data.importEnvironment(json, bodyStore)
                environmentStore.save(environment)
                refresh()
                ImportOutcome.Success(environment.name)
            } catch (error: SchemaTooNewException) {
                ImportOutcome.Error("This file was made by a newer version of the app.")
            } catch (error: Exception) {
                ImportOutcome.Error("This file isn't a valid environment export.")
            }
            onDone(outcome)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            if (serverController.isRunning(id)) serverLauncher.stop(id)
            // Bodies moved to files must go together with the environment; otherwise orphan
            // files pile up under bodies/.
            environmentStore.load(id)?.let { bodyStore.deleteBodiesOf(it) }
            environmentStore.delete(id)
            refresh()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** A single row of the "SERVING" strip at the top — a running environment. */
data class ServingRow(
    val environmentId: String,
    val name: String,
    val address: String,
    val states: List<String>,
    val activeState: String?,
)

/** The import result — the screen reports it with a snackbar. */
sealed interface ImportOutcome {
    data class Success(val name: String) : ImportOutcome
    data class Error(val message: String) : ImportOutcome
}
