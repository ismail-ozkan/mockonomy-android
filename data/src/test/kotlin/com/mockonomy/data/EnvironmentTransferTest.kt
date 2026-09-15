package com.mockonomy.data

import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EnvironmentTransferTest {

    private val fs = FakeFileSystem()
    private val store = FileBodyStore(fs, "/bodies".toPath())

    private fun env(response: RouteResponse, globalHeaders: List<HeaderPair> = emptyList()) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = "Staging",
        port = 8080,
        headers = globalHeaders,
        routes = listOf(Route(id = "r1", method = HttpMethod.GET, path = "/ping", responses = listOf(response))),
    )

    @Test
    fun `export masks sensitive headers when redaction is on`() = runTest {
        val source = env(
            RouteResponse(id = "resp-1", statusCode = 200, headers = listOf(HeaderPair("Set-Cookie", "s=secret"))),
            globalHeaders = listOf(HeaderPair("Authorization", "Bearer tok")),
        )

        val json = exportEnvironment(source, store, redact = true)

        assertContains(json, REDACTED_VALUE)
        assertTrue(!json.contains("Bearer tok"), "the token must not leak into the file")
        assertTrue(!json.contains("s=secret"))
    }

    @Test
    fun `export keeps the value when redaction is off`() = runTest {
        val source = env(RouteResponse(id = "resp-1", statusCode = 200, headers = listOf(HeaderPair("Cookie", "a=b"))))

        val json = exportEnvironment(source, store, redact = false)

        assertContains(json, "a=b")
    }

    @Test
    fun `a large file body comes inline on export`() = runTest {
        val big = "x".repeat(BodyStore.LIMIT + 10)
        val fileName = store.write("resp-1", big)
        val source = env(RouteResponse(id = "resp-1", statusCode = 200, bodyType = BodyType.FILE, bodyFile = fileName))

        val json = exportEnvironment(source, store, redact = true)

        assertContains(json, "\"INLINE\"")
        assertContains(json, big)
    }

    @Test
    fun `import assigns new ids and keeps the content`() = runTest {
        val source = env(RouteResponse(id = "resp-1", statusCode = 418, body = "teapot"))
        val json = exportEnvironment(source, store, redact = true)

        val imported = importEnvironment(json, store)

        assertNotEquals(source.id, imported.id)
        assertNotEquals("r1", imported.routes.single().id)
        assertNotEquals("resp-1", imported.routes.single().responses.single().id)
        assertEquals("Staging", imported.name)
        assertEquals(418, imported.routes.single().responses.single().statusCode)
    }

    @Test
    fun `a large body is moved back into a file on import`() = runTest {
        val big = "y".repeat(BodyStore.LIMIT + 10)
        val source = env(RouteResponse(id = "resp-1", statusCode = 200, body = big))
        val json = exportEnvironment(source, store, redact = false)

        val imported = importEnvironment(json, store)

        val response = imported.routes.single().responses.single()
        assertEquals(BodyType.FILE, response.bodyType)
        assertEquals(big, store.read(response.bodyFile!!))
    }

    @Test
    fun `a newer schema version fails`() = runTest {
        val future = EnvironmentJsonWithVersion(Environment.CURRENT_SCHEMA_VERSION + 1)

        assertFailsWith<SchemaTooNewException> { importEnvironment(future, store) }
    }

    /** Produces valid JSON whose schema version is set higher by hand. */
    private fun EnvironmentJsonWithVersion(version: Int): String {
        val base = env(RouteResponse(id = "resp-1", statusCode = 200))
        return com.mockonomy.engine.model.EnvironmentJson.encode(base.copy(schemaVersion = version))
    }
}
