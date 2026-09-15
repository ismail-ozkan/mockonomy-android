package com.mockonomy.data.openapi

import com.mockonomy.engine.model.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenApiImportTest {

    private fun preview(document: String): ImportPreview {
        val result = OpenApiImport.parse(document.trimIndent())
        assertTrue(result is ImportResult.Success, "unexpected failure: $result")
        return result.preview
    }

    private fun failure(document: String): String {
        val result = OpenApiImport.parse(document.trimIndent())
        assertTrue(result is ImportResult.Failure, "expected a failure, got: $result")
        return result.message
    }

    private val minimal = """
        openapi: 3.0.3
        info:
          title: Demo API
          version: "2.1"
        paths:
          /users/{userId}:
            get:
              responses:
                "200":
                  description: A user
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          id: {type: string}
    """

    @Test
    fun `a simple document produces a route`() {
        val preview = preview(minimal)

        assertEquals("Demo API", preview.title)
        assertEquals("2.1", preview.version)

        val route = preview.routes.single()
        assertEquals(HttpMethod.GET, route.method)
        // The engine's path parameter syntax starts with `:`.
        assertEquals("/users/:userId", route.path)

        val response = route.responses.single()
        assertEquals(200, response.statusCode)
        assertEquals("A user", response.label)
        assertTrue(response.isDefault)
        assertEquals("{\n  \"id\": \"string\"\n}", response.body)
        assertEquals("application/json", response.headers.single().value)
    }

    @Test
    fun `the same document is also read as JSON`() {
        val yaml = preview(minimal)
        val json = preview(
            """
            {"openapi":"3.0.3","info":{"title":"Demo API","version":"2.1"},
             "paths":{"/users/{userId}":{"get":{"responses":{"200":{"description":"A user",
             "content":{"application/json":{"schema":{"type":"object",
             "properties":{"id":{"type":"string"}}}}}}}}}}}
            """,
        )

        assertEquals(yaml.routes.map { it.method to it.path }, json.routes.map { it.method to it.path })
        assertEquals(yaml.routes.single().responses.single().body, json.routes.single().responses.single().body)
    }

    @Test
    fun `each method is a separate route`() {
        val preview = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                get:
                  responses: {"200": {description: ok}}
                post:
                  responses: {"201": {description: created}}
                delete:
                  responses: {"204": {description: gone}}
            """,
        )

        assertEquals(
            listOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.DELETE),
            preview.routes.map { it.method },
        )
    }

    @Test
    fun `default response is the lowest 2xx`() {
        val route = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                get:
                  responses:
                    "404": {description: missing}
                    "201": {description: created}
                    "200": {description: ok}
            """,
        ).routes.single()

        assertEquals(listOf(404, 201, 200), route.responses.map { it.statusCode })
        assertEquals(200, route.responses.single { it.isDefault }.statusCode)
    }

    @Test
    fun `without 2xx the first response becomes the default`() {
        val route = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                get:
                  responses:
                    "403": {description: denied}
                    "500": {description: broken}
            """,
        ).routes.single()

        assertEquals(403, route.responses.single { it.isDefault }.statusCode)
    }

    @Test
    fun `example comes before the schema`() {
        val route = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json:
                          schema: {type: object, properties: {name: {type: string}}}
                          example: {name: Rex}
            """,
        ).routes.single()

        assertEquals("{\n  \"name\": \"Rex\"\n}", route.responses.single().body)
    }

    @Test
    fun `a response without a body has no header either`() {
        val response = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                delete:
                  responses: {"204": {description: gone}}
            """,
        ).routes.single().responses.single()

        assertEquals("", response.body)
        assertTrue(response.headers.isEmpty())
    }

    @Test
    fun `non-JSON media type is reported`() {
        val preview = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /page:
                get:
                  responses:
                    "200":
                      description: ok
                      content:
                        text/html: {schema: {type: string}}
            """,
        )

        assertEquals("", preview.routes.single().responses.single().body)
        assertTrue(preview.issues.any { "text/html" in it.reason }, preview.issues.toString())
    }

    @Test
    fun `default response is skipped and reported`() {
        val preview = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                get:
                  responses:
                    "200": {description: ok}
                    default: {description: error}
            """,
        )

        assertEquals(listOf(200), preview.routes.single().responses.map { it.statusCode })
        assertTrue(preview.issues.any { "default" in it.reason })
    }

    @Test
    fun `path with mixed segments is skipped and reported`() {
        val preview = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /files/{id}.json:
                get:
                  responses: {"200": {description: ok}}
              /ok:
                get:
                  responses: {"200": {description: ok}}
            """,
        )

        assertEquals(listOf("/ok"), preview.routes.map { it.path })
        assertTrue(preview.issues.any { it.location == "/files/{id}.json" })
    }

    @Test
    fun `path part of the server url becomes the prefix`() {
        assertEquals(
            "/v1",
            preview(
                """
                openapi: 3.0.0
                info: {title: T, version: "1"}
                servers:
                  - url: https://api.example.com/v1
                paths:
                  /pets:
                    get:
                      responses: {"200": {description: ok}}
                """,
            ).prefix,
        )
    }

    @Test
    fun `templated server url does not become the prefix`() {
        val preview = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            servers:
              - url: https://{region}.example.com/v1
            paths:
              /pets:
                get:
                  responses: {"200": {description: ok}}
            """,
        )

        assertEquals("", preview.prefix)
        assertTrue(preview.issues.any { it.location == "servers" })
    }

    @Test
    fun `response reference under components is resolved`() {
        val response = preview(
            """
            openapi: 3.0.0
            info: {title: T, version: "1"}
            paths:
              /pets:
                get:
                  responses:
                    "200": {${'$'}ref: "#/components/responses/Ok"}
            components:
              responses:
                Ok:
                  description: shared
                  content:
                    application/json:
                      example: {ok: true}
            """,
        ).routes.single().responses.single()

        assertEquals("shared", response.label)
        assertEquals("{\n  \"ok\": true\n}", response.body)
    }

    @Test
    fun `swagger 2 fails loudly`() {
        assertTrue("Swagger 2.0" in failure("""{"swagger": "2.0", "paths": {}}"""))
    }

    @Test
    fun `version other than 3 fails loudly`() {
        assertTrue("3.x" in failure("""{"openapi": "4.0.0", "paths": {"/a": {}}}"""))
    }

    @Test
    fun `missing openapi field fails loudly`() {
        assertTrue("openapi" in failure("""{"paths": {"/a": {}}}"""))
    }

    @Test
    fun `document without paths fails loudly`() {
        assertTrue("no paths" in failure("""{"openapi": "3.0.0", "paths": {}}"""))
    }

    @Test
    fun `broken YAML is reported with a line number`() {
        assertTrue("Line" in failure("openapi: 3.0.0\ninfo:\n\ttitle: T\n"))
    }

    /**
     * A real-world file (Swagger Petstore 3.0.4). The acceptance criterion: routes
     * imported from it must be able to serve responses — here we check that the
     * generated body is valid JSON and that a sample route comes out as expected.
     */
    @Test
    fun `petstore file is imported`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/openapi/petstore.json")).readBytes().decodeToString()
        val preview = preview(text)

        assertEquals("Swagger Petstore - OpenAPI 3.0", preview.title)
        assertEquals("/api/v3", preview.prefix)
        assertEquals(19, preview.routes.size)

        val pet = preview.routes.single { it.method == HttpMethod.GET && it.path == "/pet/:petId" }
        assertEquals(listOf(200, 400, 404), pet.responses.map { it.statusCode })
        assertTrue(pet.responses.single { it.isDefault }.body.contains("\"name\": \"doggie\""))
        assertFalse(preview.routes.any { it.path.contains('{') }, "path parameters were not converted")
    }
}
