package com.mockonomy.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemaMigrationTest {

    /** A hand-written v1 file (none of the new fields). */
    private val v1Json = """
        {
          "schemaVersion": 1,
          "id": "env-auth",
          "name": "Auth API",
          "port": 8080,
          "prefix": "api/v1",
          "headers": [ { "key": "Content-Type", "value": "application/json" } ],
          "routes": [
            {
              "id": "r1", "method": "GET", "path": "/users/:id",
              "responses": [
                { "id": "resp1", "statusCode": 200, "body": "{}", "isDefault": true }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `v1 is upgraded to v2 without data loss`() {
        val migrated = SchemaMigration.migrate(EnvironmentJson.decode(v1Json))

        assertEquals(2, migrated.schemaVersion)
        // v1 data as it was
        assertEquals("Auth API", migrated.name)
        assertEquals("api/v1", migrated.prefix)
        assertEquals(HeaderPair("Content-Type", "application/json"), migrated.headers.single())
        val response = migrated.routes.single().responses.single()
        assertEquals(200, response.statusCode)
        assertTrue(response.isDefault)
    }

    @Test
    fun `new fields are at their defaults for v1`() {
        val migrated = SchemaMigration.migrate(EnvironmentJson.decode(v1Json))

        assertNull(migrated.key)
        assertTrue(migrated.states.isEmpty())
        assertNull(migrated.initialState)
        val route = migrated.routes.single()
        assertNull(route.key)
        assertEquals(SequentialBehavior.STAY_ON_LAST, route.sequentialBehavior)
        val response = route.responses.single()
        assertNull(response.key)
        assertNull(response.whenState)
        assertNull(response.setState)
    }

    @Test
    fun `v1 read and written as v2 has schemaVersion 2 in the file`() {
        val encoded = EnvironmentJson.encode(SchemaMigration.migrate(EnvironmentJson.decode(v1Json)))
        assertTrue(encoded.contains("\"schemaVersion\": 2"))
    }

    @Test
    fun `the current version is returned as is, not copied`() {
        val current = Environment(
            schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
            id = "x", name = "X", port = 8080, routes = emptyList(),
        )
        // Already current, so it is left untouched — no needless copy.
        assertSame(current, SchemaMigration.migrate(current))
    }

    @Test
    fun `migration is idempotent`() {
        val once = SchemaMigration.migrate(EnvironmentJson.decode(v1Json))
        assertEquals(once, SchemaMigration.migrate(once))
    }

    @Test
    fun `an unknown old version fails`() {
        val broken = Environment(
            schemaVersion = 0, id = "x", name = "X", port = 8080, routes = emptyList(),
        )
        assertFailsWith<IllegalArgumentException> { SchemaMigration.migrate(broken) }
    }
}
