package com.mockonomy.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mockonomy.data.AdminApiSettingsStore
import com.mockonomy.data.log.NoRequestLog
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [MockServerController] uses no Android API, so it can be tested without an emulator —
 * the service is only responsible for the notification and the lifecycle.
 */
class MockServerControllerTest {

    private val environmentStore = FakeEnvironmentStore()
    private val controller = newController(environmentStore)

    @AfterTest
    fun tearDown() = runBlocking {
        controller.stopAll()
    }

    @Test
    fun `starting moves to the running state`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0), port = 0)

        val server = controller.running.value.single()
        assertEquals("Skeleton", server.environmentName)
        assertTrue(server.port > 0, "the operating system should have bound a port: ${server.port}")
        assertTrue(controller.isRunning)
        assertTrue(controller.isRunning(server.environmentId))
    }

    @Test
    fun `stopping removes it from the list`() = runBlocking {
        val environment = SkeletonEnvironment.create(port = 0)
        controller.start(environment, port = 0)
        controller.stop(environment.id)

        assertEquals(emptyList(), controller.running.value)
        assertEquals(false, controller.isRunning)
    }

    // --- multiple servers ---

    @Test
    fun `two environments run at the same time on their own ports`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-a", name = "A"), port = 0)
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-b", name = "B"), port = 0)

        val running = controller.running.value
        assertEquals(listOf("A", "B"), running.map { it.environmentName })
        assertTrue(running[0].port != running[1].port, "separate sockets should have bound separate ports")
    }

    @Test
    fun `stopping one leaves the other running`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-a", name = "A"), port = 0)
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-b", name = "B"), port = 0)

        controller.stop("env-a")

        assertEquals(listOf("B"), controller.running.value.map { it.environmentName })
    }

    @Test
    fun `stopAll stops all of them`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-a"), port = 0)
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-b"), port = 0)

        controller.stopAll()

        assertEquals(emptyList(), controller.running.value)
    }

    @Test
    fun `a second environment cannot start on the same port and no socket is opened`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-a", name = "A"), port = 0)
        val takenPort = controller.running.value.single().port

        val failure = awaitFailure {
            controller.start(
                SkeletonEnvironment.create(port = takenPort).copy(id = "env-b", name = "B"),
                port = takenPort,
            )
        }

        assertEquals("Port $takenPort is already used by \"A\".", failure.reason)
        assertEquals("env-b", failure.environmentId)
        assertEquals(listOf("A"), controller.running.value.map { it.environmentName })
    }

    /**
     * Seen on a device: typing port 80918 in settings made hot reload throw
     * `IllegalArgumentException` in Ktor's accept coroutine and the process crashed. The
     * range check must happen before a socket is opened.
     */
    @Test
    fun `an out-of-range port is rejected before a socket is opened`() = runBlocking {
        val failure = awaitFailure {
            controller.start(SkeletonEnvironment.create(port = 80918), port = 80918)
        }

        assertEquals("Port 80918 is out of range. Use a port between 1024 and 65535.", failure.reason)
        assertEquals(emptyList(), controller.running.value)
    }

    @Test
    fun `update does not move to an out-of-range port and the server stays on its old port`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0), port = 0)
        val before = controller.running.value.single()

        val failure = awaitFailure {
            controller.update(SkeletonEnvironment.create(port = 80918))
        }

        assertTrue(failure.reason.startsWith("Port 80918 is out of range."), failure.reason)
        assertEquals(before.port, controller.running.value.single().port)
    }

    @Test
    fun `a port in use is not silently swallowed`() = runBlocking {
        val occupier = newController()
        occupier.start(SkeletonEnvironment.create(port = 0), port = 0)
        val takenPort = occupier.running.value.single().port

        try {
            val failure = awaitFailure {
                controller.start(SkeletonEnvironment.create(port = takenPort), port = takenPort)
            }
            // When another *app* holds the port, the conflict only shows up at bind time.
            assertEquals(
                "Port $takenPort is already in use by another app. Choose a different port.",
                failure.reason,
            )
            assertEquals(false, controller.isRunning)
        } finally {
            occupier.stopAll()
        }
    }

    @Test
    fun `starting again while running does not break the state`() = runBlocking {
        val environment = SkeletonEnvironment.create(port = 0)
        controller.start(environment, port = 0)
        val first = controller.running.value.single()

        controller.start(environment, port = 0)

        assertEquals(listOf(first), controller.running.value)
    }

    // --- hot reload ---

    @Test
    fun `update while running updates the state with the new name`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0), port = 0)
        val before = controller.running.value.single()

        controller.update(SkeletonEnvironment.create(port = before.port).copy(name = "Renamed"))

        val after = controller.running.value.single()
        assertEquals("Renamed", after.environmentName)
        assertEquals(before.port, after.port, "the same port was given, it should not have restarted")
    }

    @Test
    fun `update does not move onto another server's port and the server stays on its old port`() = runBlocking {
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-a", name = "A"), port = 0)
        controller.start(SkeletonEnvironment.create(port = 0).copy(id = "env-b", name = "B"), port = 0)
        val (a, b) = controller.running.value

        val failure = awaitFailure {
            controller.update(SkeletonEnvironment.create(port = a.port).copy(id = "env-b", name = "B"))
        }

        assertTrue(failure.reason.startsWith("Port ${a.port} is already used by \"A\"."))
        assertEquals(b.port, controller.running.value.first { it.environmentId == "env-b" }.port)
    }

    @Test
    fun `update does nothing while the server is not running`() = runBlocking {
        controller.update(SkeletonEnvironment.create(port = 0))

        assertEquals(emptyList(), controller.running.value)
        assertEquals(false, controller.isRunning)
    }

    // --- automation commands apply to all running servers ---

    @Test
    fun `setState applies to the server that defines it and skips the other`() = runBlocking {
        controller.start(stateful("env-auth", "Auth", listOf("ok", "login_fail")), port = 0)
        controller.start(stateful("env-pay", "Payments", listOf("ok")), port = 0)

        val result = controller.runAutomationCommand(AutomationCommand.SetState("login_fail"))

        val ok = assertIs<AutomationResult.Ok>(result)
        assertTrue("Applied to 1 of 2 servers." in ok.message, ok.message)
        // The mirror runs in a separate coroutine; wait for the value to arrive.
        withTimeout(TIMEOUT_MS) {
            controller.activeStates.first { it["env-auth"] == "login_fail" }
        }
        assertEquals("ok", controller.activeStates.value["env-pay"])
    }

    @Test
    fun `an automation command is rejected while no server is running`() = runBlocking<Unit> {
        val result = controller.runAutomationCommand(AutomationCommand.Reset)

        assertIs<AutomationResult.NoEnvironment>(result)
    }

    @Test
    fun `activate is ambiguous with more than one server`() = runBlocking {
        environmentStore.save(stateful("env-third", "Third", listOf("ok")).copy(port = 9999))
        controller.start(stateful("env-auth", "Auth", listOf("ok")), port = 0)
        controller.start(stateful("env-pay", "Payments", listOf("ok")), port = 0)

        val result = controller.runAutomationCommand(AutomationCommand.Activate("env-third"))

        val bad = assertIs<AutomationResult.BadRequest>(result)
        assertEquals("ambiguous_activate_target", bad.code)
    }

    @Test
    fun `activate switches the running socket when there is a single server`() = runBlocking {
        environmentStore.save(stateful("env-third", "Third", listOf("ok")).copy(port = 9999))
        controller.start(stateful("env-auth", "Auth", listOf("ok")), port = 0)
        val port = controller.running.value.single().port

        val result = controller.runAutomationCommand(AutomationCommand.Activate("env-third"))

        assertIs<AutomationResult.Ok>(result)
        val running = controller.running.value.single()
        assertEquals("Third", running.environmentName)
        assertEquals(port, running.port, "hot swap must not change the port")
    }

    private fun stateful(id: String, name: String, states: List<String>) =
        SkeletonEnvironment.create(port = 0).copy(
            id = id,
            name = name,
            states = states,
            initialState = states.first(),
        )

    /**
     * [MockServerController.failures] is a one-off event stream: an event emitted without a
     * subscriber is lost. `UNDISPATCHED` guarantees the collector subscribes **before the
     * action** (otherwise the test races).
     */
    private companion object {
        const val TIMEOUT_MS = 2_000L
    }

    private suspend fun awaitFailure(action: suspend () -> Unit): ServerFailure = coroutineScope {
        val failure = async(start = CoroutineStart.UNDISPATCHED) { controller.failures.first() }
        action()
        // Without a timeout, the test would hang forever if the failure were never emitted.
        withTimeout(TIMEOUT_MS) { failure.await() }
    }
}

/**
 * The controller's lifecycle behavior must not change because of the admin API
 * dependencies: env/log are fakes, settings use a temporary DataStore.
 */
private fun newController(environmentStore: FakeEnvironmentStore = FakeEnvironmentStore()): MockServerController {
    fun tempDataStore() = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("ctrl-test", ".preferences_pb").apply { deleteOnExit() } },
    )
    return MockServerController(
        logSink = NoRequestLog,
        environmentStore = environmentStore,
        logStore = FakeRequestLogStore(),
        adminSettings = AdminApiSettingsStore(tempDataStore()),
    )
}
