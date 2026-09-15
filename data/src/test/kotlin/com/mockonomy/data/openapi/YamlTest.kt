package com.mockonomy.data.openapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YamlTest {

    private fun obj(text: String) = Yaml.parse(text.trimIndent()).jsonObject

    @Test
    fun `plain mapping`() {
        val yaml = obj(
            """
            openapi: 3.0.0
            count: 3
            enabled: true
            missing:
            """,
        )

        assertEquals("3.0.0", yaml["openapi"]!!.jsonPrimitive.content)
        assertEquals(3, yaml["count"]!!.jsonPrimitive.int)
        assertTrue(yaml["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, yaml["missing"])
    }

    @Test
    fun `nested mapping is resolved by indentation`() {
        val yaml = obj(
            """
            info:
              title: Demo
              contact:
                name: Ada
            paths: {}
            """,
        )

        assertEquals("Ada", yaml["info"]!!.jsonObject["contact"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), yaml["paths"])
    }

    @Test
    fun `a sequence can be at the same indentation or one level deeper`() {
        val flush = obj(
            """
            tags:
            - a
            - b
            """,
        )
        val indented = obj(
            """
            tags:
              - a
              - b
            """,
        )

        val expected = listOf("a", "b")
        assertEquals(expected, flush["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(expected, indented["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `compact mapping inside a sequence`() {
        val yaml = obj(
            """
            parameters:
              - name: petId
                in: path
                required: true
              - name: limit
                in: query
            """,
        )

        val parameters = yaml["parameters"]!!.jsonArray
        assertEquals(2, parameters.size)
        assertEquals("petId", parameters[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(parameters[0].jsonObject["required"]!!.jsonPrimitive.boolean)
        assertEquals("query", parameters[1].jsonObject["in"]!!.jsonPrimitive.content)
    }

    @Test
    fun `flow sequence and mapping`() {
        val yaml = obj(
            """
            required: [id, name]
            server: {url: /v1, description: "main"}
            empty: []
            """,
        )

        assertEquals(listOf("id", "name"), yaml["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("/v1", yaml["server"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("main", yaml["server"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), yaml["empty"])
    }

    @Test
    fun `flow block continues when it does not fit on one line`() {
        val yaml = obj(
            """
            enum: [
              draft,
              published
            ]
            """,
        )

        assertEquals(listOf("draft", "published"), yaml["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `colon inside a quoted value is not taken as a key`() {
        val yaml = obj(
            """
            url: "https://api.example.com/v1"
            note: 'it''s fine'
            """,
        )

        assertEquals("https://api.example.com/v1", yaml["url"]!!.jsonPrimitive.content)
        assertEquals("it's fine", yaml["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `comments are dropped but a hash inside quotes stays`() {
        val yaml = obj(
            """
            # header comment
            title: Demo   # end-of-line comment
            color: "#ff0000"
            tag: red#orange
            """,
        )

        assertEquals("Demo", yaml["title"]!!.jsonPrimitive.content)
        assertEquals("#ff0000", yaml["color"]!!.jsonPrimitive.content)
        assertEquals("red#orange", yaml["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `block scalar keeps line breaks`() {
        val yaml = obj(
            """
            body: |
              {
                "id": 1
              }
            next: after
            """,
        )

        assertEquals("{\n  \"id\": 1\n}\n", yaml["body"]!!.jsonPrimitive.content)
        assertEquals("after", yaml["next"]!!.jsonPrimitive.content)
    }

    @Test
    fun `folded block scalar joins lines`() {
        val yaml = obj(
            """
            description: >-
              first line
              second line
            next: after
            """,
        )

        assertEquals("first line second line", yaml["description"]!!.jsonPrimitive.content)
        assertEquals("after", yaml["next"]!!.jsonPrimitive.content)
    }

    @Test
    fun `plain scalar can wrap to the next line`() {
        val yaml = obj(
            """
            description: this description
              continues on the next line
            title: Demo
            """,
        )

        assertEquals("this description continues on the next line", yaml["description"]!!.jsonPrimitive.content)
        assertEquals("Demo", yaml["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `document separator and end`() {
        val yaml = obj(
            """
            ---
            title: Demo
            ...
            """,
        )

        assertEquals("Demo", yaml["title"]!!.jsonPrimitive.content)
    }

    /*
     * The following must fail rather than be parsed silently wrong: a wrongly parsed
     * file means the user struggling with an incomplete mock for hours (no claim of
     * full compatibility).
     */

    @Test
    fun `anchor and alias fail loudly`() {
        assertTrue("Anchors" in assertFailsWith<YamlException> { Yaml.parse("base: &ref\n  a: 1\n") }.message!!)
        assertTrue("Aliases" in assertFailsWith<YamlException> { Yaml.parse("copy: *ref\n") }.message!!)
    }

    @Test
    fun `merge key fails loudly`() {
        val error = assertFailsWith<YamlException> { Yaml.parse("target:\n  <<: base\n  a: 1\n") }
        assertTrue("Merge" in error.message!!, error.message!!)
    }

    @Test
    fun `tab indentation fails loudly`() {
        val error = assertFailsWith<YamlException> { Yaml.parse("info:\n\ttitle: Demo\n") }
        assertTrue("Tab" in error.message!!, error.message!!)
    }

    @Test
    fun `multiple documents fail loudly`() {
        val error = assertFailsWith<YamlException> { Yaml.parse("a: 1\n---\nb: 2\n") }
        assertTrue("Multi-document" in error.message!!, error.message!!)
    }

    @Test
    fun `error message contains the line number`() {
        val error = assertFailsWith<YamlException> {
            Yaml.parse("info:\n  title: Demo\n    stray: value\n")
        }
        assertTrue(error.message!!.startsWith("Line 3:"), error.message!!)
    }
}
