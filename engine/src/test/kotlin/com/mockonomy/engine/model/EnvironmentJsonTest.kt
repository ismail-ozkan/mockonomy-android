package com.mockonomy.engine.model

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvironmentJsonTest {

    private fun sampleEnvironment() = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-auth",
        name = "Auth API",
        port = 8080,
        prefix = "api/v1",
        headers = listOf(HeaderPair("Content-Type", "application/json")),
        routes = listOf(
            Route(
                id = "route-get-user",
                method = HttpMethod.GET,
                path = "/users/:id",
                responses = listOf(
                    RouteResponse(
                        id = "resp-found",
                        label = "200 - found",
                        statusCode = 200,
                        body = """{"id":"42"}""",
                        isDefault = true,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `round trip is lossless`() {
        val original = sampleEnvironment()
        val restored = EnvironmentJson.decode(EnvironmentJson.encode(original))
        assertEquals(original, restored)
    }

    @Test
    fun `unknown fields are ignored`() {
        // Forward compatibility: a field added in a newer version must not break an older version
        val json = """
            {
              "schemaVersion": 1,
              "id": "env-1",
              "name": "Test",
              "port": 8080,
              "futureFieldFromNewerVersion": { "anything": [1, 2, 3] },
              "routes": []
            }
        """.trimIndent()

        val env = EnvironmentJson.decode(json)
        assertEquals("env-1", env.id)
        assertTrue(env.routes.isEmpty())
    }

    @Test
    fun `a missing required field gives a meaningful error`() {
        val json = """{ "schemaVersion": 1, "id": "env-1", "name": "Test", "routes": [] }"""

        val error = assertFailsWith<SerializationException> { EnvironmentJson.decode(json) }
        assertContains(error.message.orEmpty(), "port")
    }

    @Test
    fun `optional fields get their defaults`() {
        val json = """
            {
              "schemaVersion": 1,
              "id": "env-1",
              "name": "Test",
              "port": 8080,
              "routes": [
                {
                  "id": "r1",
                  "method": "GET",
                  "path": "/ping",
                  "responses": [ { "id": "resp1", "statusCode": 200 } ]
                }
              ]
            }
        """.trimIndent()

        val env = EnvironmentJson.decode(json)
        assertEquals("", env.prefix)
        assertEquals(0, env.defaultLatencyMs)
        assertEquals(false, env.cors)
        assertTrue(env.headers.isEmpty())
        assertNull(env.proxy)

        val route = env.routes.single()
        assertEquals(true, route.enabled)
        assertEquals(ResponseMode.RULES, route.responseMode)

        val response = route.responses.single()
        assertEquals("", response.label)
        assertEquals(BodyType.INLINE, response.bodyType)
        assertEquals("", response.body)
        assertNull(response.bodyFile)
        assertEquals(0, response.latencyMs)
        assertEquals(false, response.isDefault)
        assertEquals(RulesOperator.AND, response.rulesOperator)
        assertTrue(response.rules.isEmpty())
    }

    @Test
    fun `defaults are written too`() {
        // encodeDefaults: the exported file must be complete and readable
        val minimal = Environment(
            schemaVersion = 1,
            id = "env-1",
            name = "Test",
            port = 8080,
            routes = emptyList(),
        )

        val encoded = EnvironmentJson.encode(minimal)
        assertContains(encoded, "\"prefix\"")
        assertContains(encoded, "\"defaultLatencyMs\"")
        assertContains(encoded, "\"cors\"")
    }

    @Test
    fun `enum values are written with the expected wire names`() {
        // These names are a contract; other platforms must produce them exactly
        val env = sampleEnvironment().copy(
            routes = listOf(
                Route(
                    id = "r1",
                    method = HttpMethod.PATCH,
                    path = "/x",
                    responseMode = ResponseMode.RULES,
                    responses = listOf(
                        RouteResponse(
                            id = "resp1",
                            statusCode = 200,
                            bodyType = BodyType.FILE,
                            bodyFile = "resp1.json",
                            rulesOperator = RulesOperator.OR,
                            rules = listOf(
                                Rule(
                                    target = RuleTarget.QUERY,
                                    key = "verbose",
                                    operator = RuleOperator.EQUALS,
                                    value = "true",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val encoded = EnvironmentJson.encode(env)
        assertContains(encoded, "\"PATCH\"")
        assertContains(encoded, "\"RULES\"")
        assertContains(encoded, "\"FILE\"")
        assertContains(encoded, "\"OR\"")
        assertContains(encoded, "\"QUERY\"")
        assertContains(encoded, "\"EQUALS\"")
    }

    @Test
    fun `round trip is lossless with v2 fields set`() {
        val env = Environment(
            schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
            id = "env-auth",
            key = "auth",
            name = "Auth API",
            port = 8080,
            states = listOf("logged_out", "logged_in"),
            initialState = "logged_out",
            routes = listOf(
                Route(
                    id = "route-login",
                    key = "login",
                    method = HttpMethod.POST,
                    path = "/auth/login",
                    responseMode = ResponseMode.SEQUENTIAL,
                    sequentialBehavior = SequentialBehavior.CYCLE,
                    responses = listOf(
                        RouteResponse(
                            id = "resp-ok",
                            key = "success",
                            statusCode = 200,
                            whenState = "logged_out",
                            setState = "logged_in",
                            isDefault = true,
                        ),
                    ),
                ),
            ),
        )

        val restored = EnvironmentJson.decode(EnvironmentJson.encode(env))
        assertEquals(env, restored)

        val encoded = EnvironmentJson.encode(env)
        // SequentialBehavior is written with its wire name (cross-platform contract)
        assertContains(encoded, "\"CYCLE\"")
    }

    @Test
    fun `enum values defined ahead of engine support can be read`() {
        // Values may exist in the schema before the engine supports them; reading the
        // file must not crash.
        val json = """
            {
              "schemaVersion": 1, "id": "env-1", "name": "Test", "port": 8080,
              "routes": [
                {
                  "id": "r1", "method": "GET", "path": "/ping",
                  "responseMode": "SEQUENTIAL",
                  "responses": [ { "id": "resp1", "statusCode": 200 } ]
                }
              ]
            }
        """.trimIndent()

        val env = EnvironmentJson.decode(json)
        assertEquals(ResponseMode.SEQUENTIAL, env.routes.single().responseMode)
    }
}
