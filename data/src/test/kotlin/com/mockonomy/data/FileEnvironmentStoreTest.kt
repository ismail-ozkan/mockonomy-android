package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs with `okio.fakefilesystem.FakeFileSystem` without ever touching the real disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileEnvironmentStoreTest {

    private val fs = FakeFileSystem()
    private val root = "/environments".toPath()
    private val store = FileEnvironmentStore(fs, root)

    private fun environment(id: String = "env-a", name: String = "A", port: Int = 8080) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = id,
        name = name,
        port = port,
        routes = emptyList(),
    )

    // --- basic storage ---

    @Test
    fun `save then load returns exactly the same object`() = runTest {
        val original = environment()
        store.save(original)

        assertEquals(original, store.load("env-a"))
    }

    @Test
    fun `no temp file is left after an atomic write`() = runTest {
        store.save(environment())

        val filesInRoot = fs.list(root)
        assertEquals(listOf("env-a.json"), filesInRoot.map { it.name })
    }

    @Test
    fun `corrupt json shows up as corrupt in the list and the app does not crash`() = runTest {
        store.save(environment(id = "good"))
        fs.createDirectories(root)
        fs.write(root.resolve("broken.json")) { writeUtf8("{ not valid json") }

        val summaries = store.list()

        assertEquals(2, summaries.size)
        val broken = summaries.single { it.id == "broken" }
        assertIs<EnvironmentSummary.Corrupt>(broken)
        val good = summaries.single { it.id == "good" }
        assertIs<EnvironmentSummary.Valid>(good)
    }

    @Test
    fun `unsupported schema version is marked corrupt`() = runTest {
        fs.createDirectories(root)
        // Written by hand: simulates a schemaVersion produced by a future app version
        // that this version does not understand yet.
        fs.write(root.resolve("future.json")) {
            writeUtf8(
                """{"schemaVersion":99,"id":"future","name":"F","port":8080,"routes":[]}""",
            )
        }

        val summary = store.list().single()
        assertIs<EnvironmentSummary.Corrupt>(summary)
        assertContains(summary.reason, "99")
        assertNull(store.load("future"))
    }

    @Test
    fun `invalid low schema version is marked corrupt and does not crash`() = runTest {
        fs.createDirectories(root)
        // schemaVersion 0/negative: a value migration does not understand. It must be
        // marked as a corrupt file and must not crash the app (spec/schema, "Corrupt file behavior").
        fs.write(root.resolve("weird.json")) {
            writeUtf8("""{"schemaVersion":0,"id":"weird","name":"W","port":8080,"routes":[]}""")
        }

        val summary = store.list().single()
        assertIs<EnvironmentSummary.Corrupt>(summary)
        assertNull(store.load("weird"))
    }

    @Test
    fun `an old v1 file is migrated to v2 on read without data loss`() = runTest {
        fs.createDirectories(root)
        // A hand-written v1 file — device state left over from an older app version.
        fs.write(root.resolve("legacy.json")) {
            writeUtf8(
                """
                {
                  "schemaVersion": 1,
                  "id": "legacy",
                  "name": "Legacy API",
                  "port": 8080,
                  "prefix": "api/v1",
                  "routes": [
                    {
                      "id": "r1", "method": "GET", "path": "/ping",
                      "responses": [ { "id": "resp1", "statusCode": 200, "body": "pong" } ]
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val loaded = store.load("legacy")!!

        assertEquals(Environment.CURRENT_SCHEMA_VERSION, loaded.schemaVersion)
        // v1 data is preserved
        assertEquals("Legacy API", loaded.name)
        assertEquals("api/v1", loaded.prefix)
        assertEquals("pong", loaded.routes.single().responses.single().body)
        // v2 fields are at their defaults
        assertNull(loaded.key)
        assertTrue(loaded.states.isEmpty())
        assertNull(loaded.initialState)
    }

    @Test
    fun `saving with the same id overwrites the file`() = runTest {
        store.save(environment(name = "First"))
        store.save(environment(name = "Second"))

        val loaded = store.load("env-a")
        assertEquals("Second", loaded?.name)
        assertEquals(1, fs.list(root).size)
    }

    @Test
    fun `loading a missing id returns null`() = runTest {
        assertNull(store.load("nope"))
    }

    @Test
    fun `delete removes the file`() = runTest {
        store.save(environment())
        store.delete("env-a")

        assertNull(store.load("env-a"))
        assertTrue(fs.list(root).isEmpty())
    }

    @Test
    fun `deleting a missing id does not throw`() = runTest {
        store.delete("nope") // idempotent — no assertFailsWith needed, not crashing is enough
    }

    @Test
    fun `list is sorted by id and excludes temp files`() = runTest {
        store.save(environment(id = "env-b"))
        store.save(environment(id = "env-a"))
        fs.createDirectories(root)
        fs.write(root.resolve("orphan.json.tmp")) { writeUtf8("interrupted write") }

        val ids = store.list().map { it.id }
        assertEquals(listOf("env-a", "env-b"), ids)
    }

    @Test
    fun `list is empty in an empty directory and does not crash`() = runTest {
        assertTrue(store.list().isEmpty())
    }

    // --- hot-reload trigger ---

    @Test
    fun `save emits the saved id`() = runTest {
        val emitted = mutableListOf<String>()
        val collector = launch { store.saved.toList(emitted) }
        runCurrent()

        store.save(environment(id = "env-a"))
        store.save(environment(id = "env-b"))
        runCurrent()
        collector.cancel()

        assertEquals(listOf("env-a", "env-b"), emitted)
    }

}
