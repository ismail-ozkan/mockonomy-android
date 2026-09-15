package com.mockonomy.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonBodyTest {

    // --- formatting ---

    @Test
    fun `single-line json is indented`() {
        val formatted = JsonBody.format("""{"a":1,"b":[2,3]}""")

        assertEquals(
            """
            {
              "a": 1,
              "b": [
                2,
                3
              ]
            }
            """.trimIndent(),
            formatted,
        )
    }

    @Test
    fun `key order is preserved - formatting does not reorganize the document`() {
        val formatted = JsonBody.format("""{"z":1,"a":2}""")

        assertNotNull(formatted)
        assertTrue(formatted.indexOf("\"z\"") < formatted.indexOf("\"a\""))
    }

    @Test
    fun `invalid json cannot be formatted and returns null`() {
        assertNull(JsonBody.format("{not json"))
    }

    @Test
    fun `plain text that is not json cannot be formatted`() {
        assertNull(JsonBody.format("hello world"))
    }

    // --- validation ---

    @Test
    fun `valid json reports no problem`() {
        assertNull(JsonBody.validate("""{"ok": true}"""))
    }

    @Test
    fun `blank body reports a problem`() {
        val problem = JsonBody.validate("   ")

        assertNotNull(problem)
        assertEquals(1, problem.line)
    }

    @Test
    fun `the error line is reported correctly`() {
        val body = """
            {
              "a": 1,
              "b": ,
            }
        """.trimIndent()

        val problem = JsonBody.validate(body)

        assertNotNull(problem)
        assertEquals(3, problem.line)
    }

    @Test
    fun `an error on the first line is reported with its column`() {
        val problem = JsonBody.validate("""{"a" 1}""")

        assertNotNull(problem)
        assertEquals(1, problem.line)
        assertTrue(problem.column > 1)
    }

    @Test
    fun `the error message is a single line`() {
        val problem = JsonBody.validate("{oops")

        assertNotNull(problem)
        assertTrue('\n' !in problem.message)
        assertTrue(problem.message.isNotBlank())
    }
}
