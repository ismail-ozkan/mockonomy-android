package com.mockonomy.autostart

import com.mockonomy.AppReadiness
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.engine.model.Environment
import com.mockonomy.fakeMockServerController
import com.mockonomy.server.MockServerController
import com.mockonomy.server.ServerLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The CI/ADB autostart path (key/id resolution, idempotent). */
class AutostartHandlerTest {

    private class FakeLauncher : ServerLauncher {
        var startedId: String? = null
        val stoppedIds = mutableListOf<String>()
        override fun start(environmentId: String) { startedId = environmentId }
        override fun stop(environmentId: String) { stoppedIds.add(environmentId) }
        override fun stopAll() = Unit
    }

    private class FakeStore(private val envs: List<Environment>) : EnvironmentStore {
        override val saved: Flow<String> = emptyFlow()
        override suspend fun list(): List<EnvironmentSummary> = envs.map { EnvironmentSummary.Valid(it) }
        override suspend fun load(id: String): Environment? = envs.firstOrNull { it.id == id }
        override suspend fun save(environment: Environment) = Unit
        override suspend fun delete(id: String) = Unit
    }

    private fun env(id: String, key: String?) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = id, key = key, name = id, port = 0, routes = emptyList(),
    )

    private fun makeReadiness() = AppReadiness().apply { markReady() }



    @Test
    fun `finds by key and starts`() = runBlocking {
        val launcher = FakeLauncher()
        AutostartHandler(FakeStore(listOf(env("env-a", "auth"))), controller(), launcher, makeReadiness())
            .handle("auth")
        assertEquals("env-a", launcher.startedId)
    }

    @Test
    fun `finds by id too`() = runBlocking {
        val launcher = FakeLauncher()
        AutostartHandler(FakeStore(listOf(env("env-a", null))), controller(), launcher, makeReadiness())
            .handle("env-a")
        assertEquals("env-a", launcher.startedId)
    }

    @Test
    fun `an unknown key is silently ignored`() = runBlocking {
        val launcher = FakeLauncher()
        AutostartHandler(FakeStore(listOf(env("env-a", "auth"))), controller(), launcher, makeReadiness())
            .handle("ghost")
        assertNull(launcher.startedId)
    }

    @Test
    fun `an already running env is idempotent — does not restart`() = runBlocking {
        val launcher = FakeLauncher()
        val controller = controller()
        controller.start(env("env-a", "auth"), port = 0)
        try {
            AutostartHandler(FakeStore(listOf(env("env-a", "auth"))), controller, launcher, makeReadiness())
                .handle("auth")
            assertNull(launcher.startedId)
        } finally {
            controller.stopAll()
        }
    }

    private fun controller(): MockServerController = fakeMockServerController()
}
