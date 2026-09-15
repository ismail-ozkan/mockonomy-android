package com.mockonomy.ui.screens.response

import com.mockonomy.engine.template.TemplateEngine

/**
 * The body editor's own lightweight tokenizer.
 *
 * No third-party editor component is used: what we need is a few hundred lines, and the
 * alternative would cost APK size plus a text field we cannot control.
 *
 * The tokenizer is **error-tolerant**: half-typed JSON must be highlighted too, because
 * while the user types the text is invalid most of the time. It is a scanner, not a
 * parser — grammar validation is `JsonBody.validate`'s job.
 */
enum class TokenType {
    /** A string ending with `:` — a key in JSON. */
    KEY,
    STRING,
    NUMBER,

    /** `true`, `false`, `null`. */
    LITERAL,
    PUNCTUATION,

    /** A `{{ ... }}` expression the engine recognizes. */
    TEMPLATE,

    /** An unrecognized expression: the engine does not touch it and serves it as is. */
    UNKNOWN_TEMPLATE,
}

data class Token(val start: Int, val end: Int, val type: TokenType)

object JsonHighlighter {

    /**
     * The ranges to color, in text order and without overlaps.
     *
     * Templates are scanned **first** and the JSON scan skips over them: `"{{path.id}}"` is
     * the most common body pattern, and painting the expression inside a string with the
     * string color would defeat the whole point.
     */
    fun tokenize(text: String): List<Token> {
        val templates = templateTokens(text)
        val tokens = ArrayList<Token>(templates.size + text.length / AVERAGE_TOKEN_LENGTH)
        tokens += templates

        // Templates are sorted and the scan only moves forward, so a single pointer is
        // enough: searching the list from the start on every character would make the scan
        // quadratic on large bodies.
        var nextTemplate = 0
        var index = 0
        while (index < text.length) {
            while (nextTemplate < templates.size && templates[nextTemplate].end <= index) nextTemplate++
            val template = templates.getOrNull(nextTemplate)
            if (template != null && index >= template.start) {
                index = template.end
                continue
            }

            when (text[index]) {
                '"' -> {
                    val end = stringEnd(text, index)
                    val key = isKey(text, end)
                    // Templates inside a string are separate tokens; the string is split into
                    // pieces around them.
                    tokens += splitAround(index, end, templates, if (key) TokenType.KEY else TokenType.STRING)
                    index = end
                }

                '{', '}', '[', ']', ':', ',' -> {
                    tokens += Token(index, index + 1, TokenType.PUNCTUATION)
                    index++
                }

                in '0'..'9', '-' -> {
                    val end = numberEnd(text, index)
                    tokens += Token(index, end, TokenType.NUMBER)
                    index = end
                }

                else -> {
                    val literal = LITERALS.firstOrNull { text.startsWith(it, index) }
                    if (literal != null) {
                        tokens += Token(index, index + literal.length, TokenType.LITERAL)
                        index += literal.length
                    } else {
                        // Whitespace and anything we do not recognize stays uncolored: the body
                        // does not have to be JSON.
                        index++
                    }
                }
            }
        }

        return tokens.sortedBy { it.start }
    }

    /** `{{ ... }}` and `{{{ ... }}}` blocks in the text. */
    private fun templateTokens(text: String): List<Token> {
        if (!text.contains(OPEN)) return emptyList()

        val tokens = mutableListOf<Token>()
        var cursor = 0
        while (true) {
            val start = text.indexOf(OPEN, cursor)
            if (start < 0) break

            val raw = text.startsWith(RAW_OPEN, start)
            val open = if (raw) RAW_OPEN else OPEN
            val close = if (raw) RAW_CLOSE else CLOSE
            val end = text.indexOf(close, start + open.length)
            if (end < 0) break // An unclosed block is plain text — the engine treats it the same way.

            val expression = text.substring(start + open.length, end)
            val known = TemplateEngine.recognizes(expression)
            tokens += Token(
                start = start,
                end = end + close.length,
                type = if (known) TokenType.TEMPLATE else TokenType.UNKNOWN_TEMPLATE,
            )
            cursor = end + close.length
        }
        return tokens
    }

    /**
     * Splits the string into the pieces outside template ranges. Returns a single piece if it
     * contains no template.
     */
    private fun splitAround(
        start: Int,
        end: Int,
        templates: List<Token>,
        type: TokenType,
    ): List<Token> {
        val inside = templates.filter { it.start >= start && it.end <= end }
        if (inside.isEmpty()) return listOf(Token(start, end, type))

        val pieces = mutableListOf<Token>()
        var cursor = start
        for (template in inside) {
            if (template.start > cursor) pieces += Token(cursor, template.start, type)
            cursor = template.end
        }
        if (cursor < end) pieces += Token(cursor, end, type)
        return pieces
    }

    /** One past the closing quote; the end of the text if it never closes. */
    private fun stringEnd(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++ // An escaped character does not close the string, even a quote.
                '"' -> return index + 1
                // End of line: an unfinished string. The user may still be typing; the rest of
                // the highlighting must not break.
                '\n' -> return index
            }
            index++
        }
        return text.length
    }

    /** If the first non-whitespace character after the string is `:`, it is a key. */
    private fun isKey(text: String, stringEnd: Int): Boolean {
        var index = stringEnd
        while (index < text.length && text[index].isWhitespace()) index++
        return index < text.length && text[index] == ':'
    }

    private fun numberEnd(text: String, start: Int): Int {
        var index = start
        if (index < text.length && text[index] == '-') index++
        while (index < text.length && (text[index].isDigit() || text[index] in NUMBER_CHARS)) index++
        return index
    }

    private val LITERALS = listOf("true", "false", "null")
    private const val NUMBER_CHARS = ".eE+-"
    private const val OPEN = "{{"
    private const val CLOSE = "}}"
    private const val RAW_OPEN = "{{{"
    private const val RAW_CLOSE = "}}}"

    /** A rough estimate, only for pre-allocating the list. */
    private const val AVERAGE_TOKEN_LENGTH = 6
}
