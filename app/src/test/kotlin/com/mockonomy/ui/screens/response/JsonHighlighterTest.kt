package com.mockonomy.ui.screens.response

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonHighlighterTest {

    /** A token in readable form: type + the text it covers. */
    private fun tokensOf(text: String): List<Pair<TokenType, String>> =
        JsonHighlighter.tokenize(text).map { it.type to text.substring(it.start, it.end) }

    @Test
    fun `keys and values are told apart`() {
        val tokens = tokensOf("""{"name":"ada"}""")

        assertEquals(
            listOf(
                TokenType.PUNCTUATION to "{",
                TokenType.KEY to "\"name\"",
                TokenType.PUNCTUATION to ":",
                TokenType.STRING to "\"ada\"",
                TokenType.PUNCTUATION to "}",
            ),
            tokens,
        )
    }

    @Test
    fun `number, bool and null`() {
        val tokens = tokensOf("""{"a":1,"b":-2.5e3,"c":true,"d":null}""").filter {
            it.first == TokenType.NUMBER || it.first == TokenType.LITERAL
        }

        assertEquals(
            listOf(
                TokenType.NUMBER to "1",
                TokenType.NUMBER to "-2.5e3",
                TokenType.LITERAL to "true",
                TokenType.LITERAL to "null",
            ),
            tokens,
        )
    }

    @Test
    fun `a colon after whitespace also marks a key`() {
        val tokens = tokensOf("""{ "name" : "ada" }""")

        assertTrue(tokens.contains(TokenType.KEY to "\"name\""))
        assertTrue(tokens.contains(TokenType.STRING to "\"ada\""))
    }

    @Test
    fun `a recognized template inside a string is a separate token`() {
        val tokens = tokensOf("""{"id":"{{path.id}}"}""")

        assertTrue(tokens.contains(TokenType.TEMPLATE to "{{path.id}}"))
        // The quotes around the template are still string.
        assertTrue(tokens.contains(TokenType.STRING to "\""))
    }

    @Test
    fun `an unrecognized template is marked with a separate type`() {
        val tokens = tokensOf("""{"name":"{{faker.name}}"}""")

        assertTrue(tokens.contains(TokenType.UNKNOWN_TEMPLATE to "{{faker.name}}"))
    }

    @Test
    fun `a helper with arguments is recognized`() {
        assertTrue(tokensOf("""{"n":"{{random.int 1 100}}"}""").contains(TokenType.TEMPLATE to "{{random.int 1 100}}"))
    }

    @Test
    fun `an invalid argument is not recognized`() {
        // `random.int` needs two numbers; if one is missing the engine leaves the expression alone.
        assertTrue(tokensOf("""{"n":"{{random.int 1}}"}""").contains(TokenType.UNKNOWN_TEMPLATE to "{{random.int 1}}"))
    }

    @Test
    fun `a raw insertion block is a template too`() {
        assertTrue(tokensOf("""{"raw":{{{body.items}}}}""").contains(TokenType.TEMPLATE to "{{{body.items}}}"))
    }

    @Test
    fun `a template outside a string is highlighted too`() {
        assertTrue(tokensOf("""{"n":{{timestamp}}}""").contains(TokenType.TEMPLATE to "{{timestamp}}"))
    }

    @Test
    fun `an unclosed template counts as plain text`() {
        val tokens = tokensOf("""{"a":"{{uuid"}""")

        assertTrue(tokens.none { it.first == TokenType.TEMPLATE || it.first == TokenType.UNKNOWN_TEMPLATE })
    }

    @Test
    fun `half-typed JSON does not crash`() {
        // This is what the text often looks like while the user types.
        val tokens = tokensOf("""{"name": "Ada""")

        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.contains(TokenType.KEY to "\"name\""))
    }

    @Test
    fun `an escaped quote does not close the string`() {
        val tokens = tokensOf("""{"q":"say \"hi\""}""")

        assertTrue(tokens.contains(TokenType.STRING to "\"say \\\"hi\\\"\""))
    }

    @Test
    fun `token ranges do not overlap and are ordered`() {
        val tokens = JsonHighlighter.tokenize(
            """{"id":"{{path.id}}","n":{{random.int 1 5}},"ok":true}""",
        )

        var previousEnd = 0
        tokens.forEach { token ->
            assertTrue(token.start >= previousEnd, "overlapping token: $token")
            assertTrue(token.end > token.start)
            previousEnd = token.end
        }
    }

    @Test
    fun `a non-JSON body does not crash`() {
        val tokens = tokensOf("plain text, not json at all")

        assertTrue(tokens.all { it.first == TokenType.PUNCTUATION })
    }
}
