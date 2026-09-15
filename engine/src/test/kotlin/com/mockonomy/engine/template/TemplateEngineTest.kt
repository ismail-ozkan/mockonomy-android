package com.mockonomy.engine.template

import com.mockonomy.engine.MockRequest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Contract: `spec/behavior/templating.md`.
 *
 * Golden files pin the deterministic helpers across platforms; this class tests the
 * two things they cannot cover: parser edge cases and the *shape* of random helper
 * output (not the sequence — that is platform-specific).
 */
@OptIn(ExperimentalTime::class)
class TemplateEngineTest {

    private class FixedClock(private val instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val context = TemplateContext(
        clock = FixedClock(Instant.parse("2026-03-14T15:09:26Z")),
        random = Random(42),
    )

    private fun render(
        text: String,
        request: MockRequest = MockRequest(method = "GET", path = "/"),
        pathParams: Map<String, String> = emptyMap(),
        context: TemplateContext = this.context,
    ) = TemplateEngine.render(text, request, pathParams, context)

    // --- syntax ---

    @Test
    fun `text without templates is returned unchanged`() {
        assertEquals("""{"a":1}""", render("""{"a":1}"""))
    }

    @Test
    fun `whitespace around the expression does not matter`() {
        val params = mapOf("id" to "42")
        assertEquals("42", render("{{path.id}}", pathParams = params))
        assertEquals("42", render("{{  path.id  }}", pathParams = params))
    }

    @Test
    fun `multiple expressions in the same text are expanded`() {
        val request = MockRequest(method = "GET", path = "/", query = mapOf("q" to listOf("kotlin")))
        assertEquals(
            """{"id":"7","q":"kotlin"}""",
            render("""{"id":"{{path.id}}","q":"{{query.q}}"}""", request, mapOf("id" to "7")),
        )
    }

    @Test
    fun `an unclosed block is plain text`() {
        assertEquals("{{uuid", render("{{uuid"))
    }

    // --- escaping ---

    @Test
    fun `values are inserted with JSON-safe escaping by default`() {
        val request = MockRequest(
            method = "GET",
            path = "/",
            query = mapOf("name" to listOf("Ada \"Lovelace\"\n")),
        )
        assertEquals("""{"name": "Ada \"Lovelace\"\n"}""", render("""{"name": "{{query.name}}"}""", request))
    }

    @Test
    fun `triple braces insert raw`() {
        val request = MockRequest(method = "GET", path = "/", body = """{"user":{"name":"Ada"}}""")
        assertEquals("""{"user": {"name":"Ada"}}""", render("""{"user": {{{body.user}}}}""", request))
    }

    // --- data helpers ---

    @Test
    fun `header names match case-insensitively`() {
        val request = MockRequest(
            method = "GET",
            path = "/",
            headers = mapOf("X-Debug" to listOf("on")),
        )
        assertEquals("on", render("{{header.x-debug}}", request))
        assertEquals("on", render("{{header.X-DEBUG}}", request))
    }

    @Test
    fun `query and header use the first value`() {
        val request = MockRequest(
            method = "GET",
            path = "/",
            query = mapOf("tag" to listOf("a", "b")),
        )
        assertEquals("a", render("{{query.tag}}", request))
    }

    @Test
    fun `body is read with dot notation`() {
        val body = """{"user":{"name":"Ada","age":36,"tags":["a","b"]}}"""
        val request = MockRequest(method = "POST", path = "/", body = body)

        assertEquals("Ada", render("{{body.user.name}}", request))
        assertEquals("36", render("{{body.user.age}}", request))
        assertEquals("""["a","b"]""", render("{{{body.user.tags}}}", request))
    }

    @Test
    fun `non-JSON body yields an empty string and does not crash`() {
        val request = MockRequest(method = "POST", path = "/", body = "plain text")
        assertEquals("", render("{{body.user.name}}", request))
    }

    @Test
    fun `a missing value becomes an empty string`() {
        assertEquals("""{"q":""}""", render("""{"q":"{{query.q}}"}"""))
        assertEquals("", render("{{path.id}}"))
        assertEquals("", render("{{header.X-Absent}}"))
    }

    // --- unrecognized expressions ---

    @Test
    fun `unrecognized expressions are left as they are`() {
        listOf(
            "{{nope}}",
            "{{UUID}}",
            "{{}}",
            "{{ }}",
            "{{uuid 5}}",
            "{{random.int 1}}",
            "{{random.int a b}}",
            "{{random.int 10 1}}",
            "{{random.string -1}}",
            """{{now "qqq"}}""",
            "{{query.}}",
            "{{path.id extra}}",
        ).forEach { expression ->
            assertEquals(expression, render(expression), "should have been left untouched: $expression")
        }
    }

    /**
     * Real user data may contain `{{`; turning it into an empty string would silently
     * delete data.
     */
    @Test
    fun `curly braces in user data are preserved`() {
        assertEquals(
            """{"tpl":"{{greeting}}, {{name}}"}""",
            render("""{"tpl":"{{greeting}}, {{name}}"}"""),
        )
    }

    @Test
    fun `helper output is not scanned again`() {
        val request = MockRequest(method = "GET", path = "/", query = mapOf("q" to listOf("{{uuid}}")))
        assertEquals("""{"q":"{{uuid}}"}""", render("""{"q":"{{query.q}}"}""", request))
    }

    // --- time ---

    @Test
    fun `now without a pattern produces ISO-8601 UTC`() {
        assertEquals("2026-03-14T15:09:26Z", render("{{now}}"))
    }

    @Test
    fun `now applies the pattern`() {
        assertEquals("2026-03-14", render("""{{now "yyyy-MM-dd"}}"""))
        assertEquals("15:09:26", render("""{{now "HH:mm:ss"}}"""))
        assertEquals("2026-03-14 15:09", render("""{{now "yyyy-MM-dd HH:mm"}}"""))
    }

    @Test
    fun `timestamp is epoch seconds`() {
        assertEquals("1773500966", render("{{timestamp}}"))
    }

    // --- randomness: shape, not sequence ---

    @Test
    fun `uuid has v4 format`() {
        val value = render("{{uuid}}")
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(value),
            "not a UUID v4: $value",
        )
    }

    @Test
    fun `same seed gives the same output`() {
        fun once() = TemplateEngine.render(
            "{{uuid}}",
            MockRequest(method = "GET", path = "/"),
            emptyMap(),
            TemplateContext(context.clock, Random(7)),
        )
        assertEquals(once(), once())
    }

    @Test
    fun `random int includes both ends`() {
        repeat(50) {
            val value = TemplateEngine.render(
                "{{random.int 1 3}}",
                MockRequest(method = "GET", path = "/"),
                emptyMap(),
                TemplateContext(context.clock, Random.Default),
            ).toInt()
            assertTrue(value in 1..3, "out of range: $value")
        }
        assertEquals("5", render("{{random.int 5 5}}"))
    }

    @Test
    fun `random string has the requested length and is alphanumeric`() {
        val value = render("{{random.string 12}}")
        assertEquals(12, value.length)
        assertTrue(Regex("[A-Za-z0-9]{12}").matches(value), "not alphanumeric: $value")
        assertEquals("", render("{{random.string 0}}"))
    }

    @Test
    fun `random bool produces true or false`() {
        assertTrue(render("{{random.bool}}") in setOf("true", "false"))
    }
}
