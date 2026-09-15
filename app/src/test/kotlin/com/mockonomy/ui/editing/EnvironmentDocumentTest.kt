package com.mockonomy.ui.editing

import com.mockonomy.engine.model.Environment
import com.mockonomy.ui.screens.environmentlist.FakeEnvironmentStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EnvironmentDocumentTest {

    private fun environment(name: String = "A", port: Int = 8080) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = name,
        port = port,
        routes = emptyList(),
    )

    @Test
    fun `loads from the store on open`() = runTest {
        val store = FakeEnvironmentStore().apply { save(environment()) }

        val document = EnvironmentDocument("env-a", store, TestScope(testScheduler))
        advanceUntilIdle()

        assertEquals("A", document.state.value?.name)
    }

    @Test
    fun `update does not write to disk until the debounce elapses`() = runTest {
        val store = FakeEnvironmentStore().apply { save(environment()) }
        val document = EnvironmentDocument("env-a", store, TestScope(testScheduler))
        advanceUntilIdle()

        document.update { it.copy(name = "B") }
        advanceTimeBy(200)

        assertEquals("A", store.load("env-a")?.name)

        advanceUntilIdle()
        assertEquals("B", store.load("env-a")?.name)
    }

    @Test
    fun `updateNow writes without waiting`() = runTest {
        val store = FakeEnvironmentStore().apply { save(environment()) }
        val document = EnvironmentDocument("env-a", store, TestScope(testScheduler))
        advanceUntilIdle()

        document.updateNow { it.copy(name = "B") }
        advanceUntilIdle()

        assertEquals("B", store.load("env-a")?.name)
    }

    /**
     * A bug seen on a device: a status code set on the response screen disappeared after
     * going back to the route screen and adding a response — the route screen overwrote it
     * with its stale copy.
     */
    @Test
    fun `a change written elsewhere is reflected in the copy`() = runTest {
        val store = FakeEnvironmentStore().apply { save(environment()) }
        val document = EnvironmentDocument("env-a", store, TestScope(testScheduler))
        advanceUntilIdle()

        // Another screen writes the same file.
        store.save(environment(name = "Written elsewhere"))
        advanceUntilIdle()

        assertEquals("Written elsewhere", document.state.value?.name)
    }

    @Test
    fun `writing over a refreshed copy keeps the change written elsewhere`() = runTest {
        val store = FakeEnvironmentStore().apply { save(environment()) }
        val document = EnvironmentDocument("env-a", store, TestScope(testScheduler))
        advanceUntilIdle()

        store.save(environment(name = "Written elsewhere", port = 9090))
        advanceUntilIdle()

        document.updateNow { it.copy(name = "Written here") }
        advanceUntilIdle()

        val saved = store.load("env-a")
        assertEquals("Written here", saved?.name)
        // The port comes from the version written elsewhere: the stale copy did not overwrite it.
        assertEquals(9090, saved?.port)
    }

    @Test
    fun `reading back our own write does not overwrite what is still being typed`() = runTest {
        val store = FakeEnvironmentStore().apply { save(environment()) }
        val document = EnvironmentDocument("env-a", store, TestScope(testScheduler))
        advanceUntilIdle()

        document.updateNow { it.copy(name = "first") }
        // The user keeps typing before the write finishes.
        document.update { it.copy(name = "first text continued") }
        advanceUntilIdle()

        assertEquals("first text continued", document.state.value?.name)
        assertEquals("first text continued", store.load("env-a")?.name)
    }

    @Test
    fun `loaded becomes true once the read finishes - even without a file`() = runTest {
        val document = EnvironmentDocument("missing", FakeEnvironmentStore(), TestScope(testScheduler))
        advanceUntilIdle()

        assertTrue(document.loaded.value)
        assertNull(document.state.value)
    }
}
