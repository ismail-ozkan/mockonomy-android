package com.mockonomy.data.openapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchemaExampleTest {

    private fun generate(document: String, pointer: String = "schema"): String {
        val root = Json.parseToJsonElement(document.trimIndent()).jsonObject
        return SchemaExample(root).generate(root[pointer]).toString()
    }

    @Test
    fun `basic types`() {
        val json = generate(
            """
            {"schema": {"type": "object", "properties": {
              "name": {"type": "string"},
              "age": {"type": "integer"},
              "score": {"type": "number"},
              "active": {"type": "boolean"}
            }}}
            """,
        )

        assertEquals("""{"name":"string","age":0,"score":0,"active":false}""", json)
    }

    @Test
    fun `array is generated with a single item`() {
        assertEquals(
            """["string"]""",
            generate("""{"schema": {"type": "array", "items": {"type": "string"}}}"""),
        )
    }

    @Test
    fun `example comes before everything`() {
        assertEquals(
            """{"id":7}""",
            generate(
                """
                {"schema": {"type": "object", "example": {"id": 7},
                 "properties": {"id": {"type": "string"}}}}
                """,
            ),
        )
    }

    @Test
    fun `enum gives its first value`() {
        assertEquals(""""available"""", generate("""{"schema": {"enum": ["available", "sold"]}}"""))
    }

    @Test
    fun `ref is resolved`() {
        assertEquals(
            """{"pet":{"name":"string"}}""",
            generate(
                """
                {
                  "schema": {"type": "object", "properties": {"pet": {"${'$'}ref": "#/components/schemas/Pet"}}},
                  "components": {"schemas": {"Pet": {"type": "object", "properties": {"name": {"type": "string"}}}}}
                }
                """,
            ),
        )
    }

    /** An infinite loop without protection — common in real schemas (e.g. tree structures). */
    @Test
    fun `circular ref is cut off`() {
        val root = Json.parseToJsonElement(
            """
            {
              "components": {"schemas": {"Node": {"type": "object", "properties": {
                "child": {"${'$'}ref": "#/components/schemas/Node"}
              }}}}
            }
            """.trimIndent(),
        ).jsonObject
        val example = SchemaExample(root)

        val json = example.generate(root["components"]!!.jsonObject["schemas"]!!.jsonObject["Node"]).toString()

        // Expanded once (first entry into the ref), cut off on the second entry.
        assertEquals("""{"child":{"child":null}}""", json)
        assertTrue(example.notes.any { "Circular" in it }, example.notes.toString())
    }

    @Test
    fun `unresolved ref is reported`() {
        val root = Json.parseToJsonElement("""{"schema": {"${'$'}ref": "#/components/schemas/Missing"}}""").jsonObject
        val example = SchemaExample(root)

        assertEquals(JsonNull, example.generate(root["schema"]))
        assertTrue(example.notes.any { "Unresolved" in it }, example.notes.toString())
    }

    @Test
    fun `allOf merges fields`() {
        assertEquals(
            """{"id":"string","name":"string"}""",
            generate(
                """
                {"schema": {"allOf": [
                  {"type": "object", "properties": {"id": {"type": "string"}}},
                  {"type": "object", "properties": {"name": {"type": "string"}}}
                ]}}
                """,
            ),
        )
    }

    @Test
    fun `oneOf generates from the first branch`() {
        val root = Json.parseToJsonElement(
            """{"schema": {"oneOf": [{"type": "string"}, {"type": "integer"}]}}""",
        ).jsonObject
        val example = SchemaExample(root)

        assertEquals(""""string"""", example.generate(root["schema"]).toString())
        assertTrue(example.notes.any { "oneOf" in it })
    }

    @Test
    fun `schema without a type produces null, not an empty object`() {
        assertEquals("null", generate("""{"schema": {"description": "anything"}}"""))
    }

    @Test
    fun `value is read from the examples map`() {
        val media = Json.parseToJsonElement(
            """{"examples": {"one": {"value": {"id": 1}}, "two": {"value": {"id": 2}}}}""",
        ).jsonObject

        assertEquals("""{"id":1}""", exampleOf(media as JsonObject).toString())
    }
}
