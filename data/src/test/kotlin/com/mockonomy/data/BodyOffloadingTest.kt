package com.mockonomy.data

import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyOffloadingTest {

    private val fs = FakeFileSystem()
    private val root = "/bodies".toPath()
    private val store = FileBodyStore(fs, root)

    private fun response(id: String = "resp-a") = RouteResponse(id = id, statusCode = 200)

    private fun large() = "x".repeat(BodyStore.LIMIT + 1)

    @Test
    fun `body under the threshold stays inline`() = runTest {
        val result = store.applyBody(response(), """{"a":1}""")

        assertEquals(BodyType.INLINE, result.bodyType)
        assertEquals("""{"a":1}""", result.body)
        assertNull(result.bodyFile)
    }

    @Test
    fun `body over the threshold moves to a file`() = runTest {
        val body = large()

        val result = store.applyBody(response(), body)

        assertEquals(BodyType.FILE, result.bodyType)
        assertEquals("resp-a.json", result.bodyFile)
        assertEquals("", result.body)
        assertEquals(body, store.read("resp-a.json"))
    }

    @Test
    fun `shrinking body goes back inline and the file is deleted`() = runTest {
        val offloaded = store.applyBody(response(), large())

        val backInline = store.applyBody(offloaded, "small")

        assertEquals(BodyType.INLINE, backInline.bodyType)
        assertEquals("small", backInline.body)
        assertNull(backInline.bodyFile)
        assertNull(store.read("resp-a.json"))
    }

    @Test
    fun `loadBody reads the body from the file`() = runTest {
        val offloaded = store.applyBody(response(), large())

        assertEquals(large(), store.loadBody(offloaded))
    }

    @Test
    fun `loadBody returns empty for a missing file and does not crash`() = runTest {
        val orphan = response().copy(bodyType = BodyType.FILE, bodyFile = "missing.json")

        assertEquals("", store.loadBody(orphan))
    }

    @Test
    fun `hydrate pulls file bodies inline`() = runTest {
        val offloaded = store.applyBody(response(), large())
        val environment = environmentWith(offloaded)

        val hydrated = store.hydrate(environment)

        val result = hydrated.routes.single().responses.single()
        assertEquals(BodyType.INLINE, result.bodyType)
        assertEquals(large(), result.body)
        assertNull(result.bodyFile)
    }

    @Test
    fun `hydrate returns the same object when there are no files`() = runTest {
        val environment = environmentWith(response().copy(body = "inline"))

        assertTrue(environment === store.hydrate(environment))
    }

    @Test
    fun `body files are deleted with the environment`() = runTest {
        val offloaded = store.applyBody(response(), large())

        store.deleteBodiesOf(environmentWith(offloaded))

        assertNull(store.read("resp-a.json"))
    }

    private fun environmentWith(response: RouteResponse) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = "A",
        port = Environment.DEFAULT_PORT,
        routes = listOf(
            Route(id = "r1", method = HttpMethod.GET, path = "/a", responses = listOf(response)),
        ),
    )
}
