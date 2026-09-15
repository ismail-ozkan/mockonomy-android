package com.mockonomy.ui.screens.environmentlist

import com.mockonomy.AppReadiness
import com.mockonomy.fakeMockServerController
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.OnboardingStore
import com.mockonomy.server.MockServerController
import com.mockonomy.engine.model.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MockServerController` is real (it has no Android dependency) — it is the single source
 * of "is this environment running", so a fake cannot be used; the stop tests really open a
 * socket (port 0). `EnvironmentStore`/`ServerLauncher` are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val readiness = AppReadiness()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var environmentStore: FakeEnvironmentStore
    private lateinit var bodyStore: FakeBodyStore
    private lateinit var serverLauncher: FakeServerLauncher
    private lateinit var serverController: MockServerController
    private lateinit var onboardingStore: OnboardingStore
    /**
     * Lazy: first-launch seeding starts as soon as the ViewModel is created, and tests need to
     * set up the initial state before that.
     */
    private val viewModel: EnvironmentListViewModel by lazy { createViewModel() }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        environmentStore = FakeEnvironmentStore()
        bodyStore = FakeBodyStore()
        serverLauncher = FakeServerLauncher()
        val tempFile = File.createTempFile("active-env-vm-test", ".preferences_pb").apply { delete() }
        tempFile.deleteOnExit()
        // By default DataStore uses its own `Dispatchers.IO` scope; that scope cannot be driven
        // by `advanceUntilIdle()`, so tests reached their assertions before the work finished.
        // We tie the scope to the test dispatcher.
        dataStoreScope = CoroutineScope(dispatcher + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { tempFile })
        onboardingStore = OnboardingStore(dataStore)
        serverController = fakeMockServerController()
    }

    private fun createViewModel() = EnvironmentListViewModel(
        environmentStore = environmentStore,
        onboardingStore = onboardingStore,
        bodyStore = bodyStore,
        serverController = serverController,
        serverLauncher = serverLauncher,
        readiness = readiness,
    )

    /** A user who has already seen the example environment — the list starts empty. */
    private suspend fun givenExampleAlreadySeeded() {
        onboardingStore.markExampleSeeded()
        dispatcher.scheduler.advanceUntilIdle()
    }

    @AfterTest
    fun tearDown() {
        kotlinx.coroutines.runBlocking { serverController.stopAll() }
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    /** Testing the toggle's "stop" branch needs a server that is really running. */
    private suspend fun givenRunning(id: String) {
        serverController.start(environment(id).copy(port = 0), port = 0)
    }

    private fun environment(id: String, name: String = "Env $id") = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = id,
        name = name,
        port = Environment.DEFAULT_PORT,
        routes = emptyList(),
    )

    @Test
    fun `create writes a new environment to the store and refreshes the list`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        viewModel.create("Staging")
        dispatcher.scheduler.advanceUntilIdle()

        val valid = viewModel.environments.value.single() as EnvironmentSummary.Valid
        assertEquals("Staging", valid.environment.name)
    }

    @Test
    fun `duplicate creates a copy with a new id`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a", "Original"))
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.duplicate("env-a")
        dispatcher.scheduler.advanceUntilIdle()

        val ids = viewModel.environments.value.map { it.id }
        assertEquals(2, ids.size)
        assertTrue("env-a" in ids)
    }

    @Test
    fun `toggleRunning starts a stopped environment`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a"))
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleRunning("env-a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("env-a"), serverLauncher.startedIds)
        assertTrue(serverLauncher.stoppedIds.isEmpty())
    }

    @Test
    fun `toggleRunning stops a running environment`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a"))
        givenRunning("env-a")
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleRunning("env-a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("env-a"), serverLauncher.stoppedIds)
        assertTrue(serverLauncher.startedIds.isEmpty())
    }

    /**
     * Environments run side by side: starting a second environment must not stop the first.
     */
    @Test
    fun `toggleRunning does not stop the previous environment when starting a second one`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a"))
        environmentStore.save(environment("env-b"))
        givenRunning("env-a")
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.toggleRunning("env-b")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("env-b"), serverLauncher.startedIds)
        assertTrue(serverLauncher.stoppedIds.isEmpty())
    }

    @Test
    fun `delete stops a running environment first, then deletes it`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a"))
        givenRunning("env-a")
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.delete("env-a")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("env-a"), serverLauncher.stoppedIds)
        assertTrue(viewModel.environments.value.isEmpty())
        assertNull(environmentStore.load("env-a"))
    }

    @Test
    fun `delete does not touch the server when not running`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a"))
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.delete("env-a")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(serverLauncher.stoppedIds.isEmpty())
    }

    @Test
    fun `rename changes the name`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        environmentStore.save(environment("env-a", "Old"))
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.rename("env-a", "New")
        dispatcher.scheduler.advanceUntilIdle()

        val valid = viewModel.environments.value.single() as EnvironmentSummary.Valid
        assertEquals("New", valid.environment.name)
    }

    @Test
    fun `loadExample adds the example to an empty list`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        viewModel.loadExample()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.environments.value.size)
    }

    /**
     * Seen on a device with an 8 MB environment file: a "No environments yet" flash at launch —
     * the splash timeout ran out before `list()` finished and the UI was drawn with an empty
     * list. The screen now waits for `initialLoadComplete` — this signal must be `false` until
     * seeding completes and stay `true` afterwards so `refresh()` does not cause a new flash.
     */
    @Test
    fun `initialLoadComplete is false first and true once seeding is done`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        assertEquals(false, viewModel.initialLoadComplete.value)

        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.initialLoadComplete.value)

        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(true, viewModel.initialLoadComplete.value)
    }

    // --- first-launch seeding ---

    @Test
    fun `the example environment comes preloaded on first launch`() = runTest(dispatcher) {
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val valid = viewModel.environments.value.single() as EnvironmentSummary.Valid
        assertEquals("Example API", valid.environment.name)
        assertTrue(valid.environment.routes.size >= 4)
    }

    /**
     * The import screen creates the environment in ITS OWN ViewModel; since the list only
     * refreshed after its own mutations, that environment never showed up in the list (seen
     * on a device).
     */
    @Test
    fun `an environment created by another screen shows up in the list after refresh`() = runTest(dispatcher) {
        givenExampleAlreadySeeded()
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.environments.value.isEmpty())

        environmentStore.save(environment("env-imported", "Imported"))
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()

        val valid = viewModel.environments.value.single() as EnvironmentSummary.Valid
        assertEquals("Imported", valid.environment.name)
    }

    @Test
    fun `a deleted example does not come back on the second launch`() = runTest(dispatcher) {
        viewModel.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        val seededId = viewModel.environments.value.single().id
        viewModel.delete(seededId)
        dispatcher.scheduler.advanceUntilIdle()

        // A new ViewModel = the app being opened again.
        val reopened = createViewModel()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(reopened.environments.value.isEmpty())
    }
}
