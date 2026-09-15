package com.mockonomy.ui.screens.setup

import com.mockonomy.fakeMockServerController
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.server.MockServerController
import com.mockonomy.ui.screens.environmentlist.FakeEnvironmentStore
import com.mockonomy.ui.screens.setup.DiagnosticStep.Outcome
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MockServerController` is real but not started: that is enough to test the diagnostics'
 * "server is not running" branch. The network side uses [FakeLoopbackProbe] — without
 * opening real sockets.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupGuideViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var environmentStore: FakeEnvironmentStore
    private lateinit var probe: FakeLoopbackProbe

    private class FakeLoopbackProbe(
        var portOpen: Boolean = true,
        var status: Int? = 200,
    ) : LoopbackProbe {
        var requestedPath: String? = null
        override suspend fun isPortOpen(port: Int) = portOpen
        override suspend fun statusCodeOf(port: Int, path: String): Int? {
            requestedPath = path
            return status
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        environmentStore = FakeEnvironmentStore()
        probe = FakeLoopbackProbe()
        dataStoreScope = CoroutineScope(dispatcher + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        dataStoreScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(controller: MockServerController = fakeMockServerController()) = SetupGuideViewModel(
        environmentStore = environmentStore,
        serverController = controller,
        probe = probe,
    )

    private fun environment(vararg routes: Route) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = "A",
        port = Environment.DEFAULT_PORT,
        routes = routes.toList(),
    )

    private fun route(path: String, method: HttpMethod = HttpMethod.GET, enabled: Boolean = true) = Route(
        id = "r-$path",
        method = method,
        path = path,
        enabled = enabled,
        responses = listOf(RouteResponse(id = "resp", statusCode = 200)),
    )

    @Test
    fun `when the server is not running, port and request checks are never attempted`() = runTest(dispatcher) {
        val model = viewModel()

        model.runDiagnostics(notificationsEnabled = true)
        dispatcher.scheduler.advanceUntilIdle()

        val steps = model.report.value.steps
        assertEquals(2, steps.size)
        assertEquals(Outcome.Failed, steps.first().outcome)
        assertEquals(null, probe.requestedPath)
    }

    @Test
    fun `without notification permission there is a warning but the flow continues`() = runTest(dispatcher) {
        val model = viewModel()

        model.runDiagnostics(notificationsEnabled = false)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Outcome.Warned, model.report.value.steps.last().outcome)
    }

    @Test
    fun `on a running server all four checks run and are all green when they pass`() = runTest(dispatcher) {
        val controller = fakeMockServerController()
        controller.start(environment(route("/ping")), port = 0)
        environmentStore.save(environment(route("/ping")))
        dispatcher.scheduler.advanceUntilIdle()

        val model = viewModel(controller)
        model.runDiagnostics(notificationsEnabled = true)
        dispatcher.scheduler.advanceUntilIdle()

        val steps = model.report.value.steps
        assertEquals(4, steps.size)
        assertTrue(steps.all { it.outcome == Outcome.Passed })
        assertEquals("/ping", probe.requestedPath)
        controller.stopAll()
    }

    @Test
    fun `a parameterized route is not picked for the sample request`() = runTest(dispatcher) {
        val controller = fakeMockServerController()
        val env = environment(route("/users/:id"), route("/health"))
        controller.start(env, port = 0)
        environmentStore.save(env)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel(controller).runDiagnostics(notificationsEnabled = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("/health", probe.requestedPath)
        controller.stopAll()
    }

    @Test
    fun `without a route to try there is a warning, not a failure`() = runTest(dispatcher) {
        val controller = fakeMockServerController()
        val env = environment(route("/health", enabled = false))
        controller.start(env, port = 0)
        environmentStore.save(env)
        dispatcher.scheduler.advanceUntilIdle()

        val model = viewModel(controller)
        model.runDiagnostics(notificationsEnabled = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Outcome.Warned, model.report.value.steps[2].outcome)
        controller.stopAll()
    }

    @Test
    fun `when the server is up but the route does not match it is a warning, not an error`() = runTest(dispatcher) {
        probe.status = 404
        val controller = fakeMockServerController()
        val env = environment(route("/health"))
        controller.start(env, port = 0)
        environmentStore.save(env)
        dispatcher.scheduler.advanceUntilIdle()

        val model = viewModel(controller)
        model.runDiagnostics(notificationsEnabled = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(Outcome.Warned, model.report.value.steps[2].outcome)
        controller.stopAll()
    }

    @Test
    fun `when the port is not listening a clear fix is suggested`() = runTest(dispatcher) {
        probe.portOpen = false
        val controller = fakeMockServerController()
        val env = environment(route("/health"))
        controller.start(env, port = 0)
        dispatcher.scheduler.advanceUntilIdle()

        val model = viewModel(controller)
        model.runDiagnostics(notificationsEnabled = true)
        dispatcher.scheduler.advanceUntilIdle()

        val portStep = model.report.value.steps[1]
        assertEquals(Outcome.Failed, portStep.outcome)
        assertTrue("different port" in portStep.detail)
        controller.stopAll()
    }
}
