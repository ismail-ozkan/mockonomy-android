package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentDuplicationTest {

    private val fs = FakeFileSystem()
    private val store = FileBodyStore(fs, "/bodies".toPath())

    private fun environment(response: RouteResponse) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = "Staging",
        port = Environment.DEFAULT_PORT,
        routes = listOf(
            Route(id = "r1", method = HttpMethod.GET, path = "/ping", responses = listOf(response)),
        ),
    )

    @Test
    fun `copy gets new ids at every level`() = runTest {
        val source = environment(RouteResponse(id = "resp-1", statusCode = 200))

        val copy = duplicateEnvironment(source, store)

        assertNotEquals(source.id, copy.id)
        assertNotEquals("r1", copy.routes.single().id)
        assertNotEquals("resp-1", copy.routes.single().responses.single().id)
    }

    @Test
    fun `copy keeps the content`() = runTest {
        val source = environment(RouteResponse(id = "resp-1", statusCode = 418, body = "teapot"))

        val copy = duplicateEnvironment(source, store)

        val response = copy.routes.single().responses.single()
        assertEquals(418, response.statusCode)
        assertEquals("teapot", response.body)
        assertEquals("/ping", copy.routes.single().path)
    }

    @Test
    fun `copy name can be told apart from the source`() = runTest {
        val copy = duplicateEnvironment(environment(RouteResponse(id = "resp-1", statusCode = 200)), store)

        assertEquals("Staging copy", copy.name)
    }

    @Test
    fun `body file is duplicated - source and copy do not share a file`() = runTest {
        val offloaded = store.applyBody(RouteResponse(id = "resp-1", statusCode = 200), "x".repeat(BodyStore.LIMIT + 1))
        val source = environment(offloaded)

        val copy = duplicateEnvironment(source, store)

        val copiedFile = copy.routes.single().responses.single().bodyFile
        assertNotEquals(offloaded.bodyFile, copiedFile)
        assertEquals(store.read(offloaded.bodyFile!!), store.read(copiedFile!!))

        // Deleting the copy must not take the source's body with it — without this
        // separation, copying caused data loss.
        store.deleteBodiesOf(copy)
        assertNull(store.read(copiedFile))
        assertTrue(store.read(offloaded.bodyFile!!) != null)
    }
}
