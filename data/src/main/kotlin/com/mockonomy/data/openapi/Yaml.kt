package com.mockonomy.data.openapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts the subset of YAML used in OpenAPI files into a [JsonElement].
 *
 * Why our own parser: like `:engine`, `:data` follows the Kotlin Multiplatform
 * readiness rules, and every YAML library on the JVM side is built on `java.*`. The
 * subset we need is a few hundred lines; the alternative would be a dependency that
 * has to be rewritten for iOS.
 *
 * **It does not silently swallow what it does not support.** Anchors/aliases, merge
 * keys, tags, multiple documents and tab indentation fail with a line number — a
 * wrongly parsed file is worse than one that could not be parsed (the user would
 * spend hours on an incomplete mock).
 *
 * Supported: block mappings, block sequences, flow collections (`[a, b]` / `{a: 1}`),
 * quoted and plain scalars, line-wrapped plain scalars, block scalars (`|`, `>`,
 * `-`/`+` chomping), comments, `null`/`~`, booleans and numbers.
 */
object Yaml {
    fun parse(text: String): JsonElement = YamlParser(text).parseDocument()
}

/** The line number is shown to the user: they need to know where to look in the file. */
class YamlException(message: String) : Exception(message)

private class YamlParser(text: String) {

    // While sequence items are parsed the `-` marker is replaced with a space (see
    // below), so the list is mutable.
    private val lines: MutableList<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split("\n").toMutableList()

    private var index = 0

    fun parseDocument(): JsonElement {
        skipIgnorable()
        if (index < lines.size && lines[index].trim() == "---") {
            index++
            skipIgnorable()
        }
        if (index >= lines.size) return JsonNull

        val root = parseBlock(indentOf(index))

        skipIgnorable()
        if (index < lines.size) {
            if (lines[index].trim() == "---") fail("Multi-document YAML is not supported")
            fail("Unexpected content")
        }
        return root
    }

    // --- nodes ---

    /** A node indented at least [minIndent] deep. A `null` value if it is shallower. */
    private fun parseBlock(minIndent: Int): JsonElement {
        skipIgnorable()
        if (index >= lines.size) return JsonNull

        val indent = indentOf(index)
        if (indent < minIndent) return JsonNull

        val content = contentOf(index)
        return when {
            content == "-" || content.startsWith("- ") -> parseSequence(indent)
            // A block scalar as a sequence item (`- >-`). The content may sit deeper than
            // the item's own column, so the base is one column to the left: that is where
            // the `-` marker is.
            content.startsWith("|") || content.startsWith(">") -> {
                index++
                parseBlockScalar(content, indent - 1)
            }
            keyEnd(content) != null -> parseMapping(indent)
            else -> {
                index++
                scalarValue(content, indent)
            }
        }
    }

    private fun parseMapping(indent: Int): JsonElement {
        val entries = LinkedHashMap<String, JsonElement>()
        while (true) {
            skipIgnorable()
            if (index >= lines.size) break

            val lineIndent = indentOf(index)
            if (lineIndent < indent) break
            if (lineIndent > indent) fail("Unexpected indentation", index)

            val content = contentOf(index)
            if (content == "-" || content.startsWith("- ")) break
            // Document separator: [parseDocument] decides; here we only stop.
            if (content == "---") break

            val colon = keyEnd(content) ?: fail("Expected 'key: value'", index)
            val key = unquote(content.substring(0, colon).trim())
            if (key == "<<") fail("Merge keys (<<) are not supported", index)

            val rest = content.substring(colon + 1).trim()
            index++

            entries[key] = when {
                rest.isEmpty() -> parseNested(indent)
                rest.startsWith("|") || rest.startsWith(">") -> parseBlockScalar(rest, indent)
                else -> scalarValue(rest, indent)
            }
        }
        return JsonObject(entries)
    }

    /**
     * The node under a key whose value was left empty. In YAML a sequence may sit at the
     * **same** indentation as its key, so the two cases are separate.
     */
    private fun parseNested(indent: Int): JsonElement {
        skipIgnorable()
        if (index >= lines.size) return JsonNull

        val lineIndent = indentOf(index)
        val content = contentOf(index)
        return when {
            lineIndent > indent -> parseBlock(lineIndent)
            lineIndent == indent && (content == "-" || content.startsWith("- ")) -> parseSequence(indent)
            else -> JsonNull
        }
    }

    private fun parseSequence(indent: Int): JsonElement {
        val items = mutableListOf<JsonElement>()
        while (true) {
            skipIgnorable()
            if (index >= lines.size) break

            val lineIndent = indentOf(index)
            if (lineIndent < indent) break
            if (lineIndent > indent) fail("Unexpected indentation", index)

            val content = contentOf(index)
            if (content == "-") {
                index++
                items += parseBlock(indent + 1)
                continue
            }
            if (!content.startsWith("- ")) break

            // Replace the `-` marker with a space to turn the line into an ordinary line.
            // That way the same [parseBlock] handles the item whether it is a scalar, a
            // mapping or a nested sequence — no separate parser is needed for a "compact
            // mapping" inside a sequence (`- name: a` plus the lines below).
            var column = lineIndent + 1
            while (column < lines[index].length && lines[index][column] == ' ') column++
            lines[index] = " ".repeat(column) + lines[index].substring(column)
            items += parseBlock(column)
        }
        return JsonArray(items)
    }

    // --- scalars ---

    /**
     * Parses a value that starts on a single line, consuming continuation lines when
     * needed. There are two kinds of continuation: an unfinished quote/flow block and
     * line wrapping of a plain scalar (free text under `description:`).
     */
    private fun scalarValue(first: String, indent: Int): JsonElement {
        var text = first
        // An unfinished quote/flow block is ONLY looked for if the value starts that way.
        // An apostrophe inside a plain scalar is not a quote: the line "Returns the
        // batch's details" must not swallow the next line (seen in openai.yaml, where
        // parsing drifted thousands of lines later).
        if (first.startsWith("\"") || first.startsWith("'") || first.startsWith("[") || first.startsWith("{")) {
            while (incomplete(text)) {
                if (index >= lines.size) fail("Unterminated quote or bracket")
                val next = lines[index].trim()
                // In a double-quoted scalar a trailing `\` means "join without a space";
                // long `$ref` values are wrapped this way in real files (openai.yaml).
                text = if (text.trailingBackslashes() % 2 == 1) text.dropLast(1) + next else "$text $next"
                index++
            }
        }

        if (!isQuoted(text) && !text.startsWith("[") && !text.startsWith("{")) {
            while (index < lines.size) {
                val raw = lines[index]
                if (raw.isBlank()) break
                val lineIndent = indentOf(index)
                if (lineIndent <= indent) break
                val content = contentOf(index)
                if (content.isEmpty()) break
                if (content == "-" || content.startsWith("- ") || keyEnd(content) != null) break
                text += " $content"
                index++
            }
        }

        return if (text.startsWith("[") || text.startsWith("{")) FlowParser(text).parse() else plainScalar(text)
    }

    /**
     * `|` and `>` blocks. The content is the lines indented deeper than the key; the
     * base indentation is read from the first non-empty line.
     */
    private fun parseBlockScalar(header: String, indent: Int): JsonElement {
        val marker = header.substringBefore(' ').substringBefore('#')
        val folded = marker.startsWith(">")
        val chomp = when {
            marker.endsWith("-") -> Chomp.STRIP
            marker.endsWith("+") -> Chomp.KEEP
            else -> Chomp.CLIP
        }

        val collected = mutableListOf<String>()
        while (index < lines.size) {
            val raw = lines[index]
            if (raw.isNotBlank() && indentOf(index) <= indent) break
            collected += raw
            index++
        }
        while (collected.isNotEmpty() && collected.last().isBlank()) collected.removeAt(collected.size - 1)
        if (collected.isEmpty()) return JsonPrimitive("")

        val base = collected.filter { it.isNotBlank() }.minOf { line -> line.takeWhile { it == ' ' }.length }
        val body = collected.map { if (it.length >= base) it.substring(base) else "" }

        val joined = if (folded) fold(body) else body.joinToString("\n")
        return JsonPrimitive(
            when (chomp) {
                Chomp.STRIP -> joined.trimEnd('\n')
                Chomp.CLIP -> joined.trimEnd('\n') + "\n"
                Chomp.KEEP -> joined + "\n"
            },
        )
    }

    /**
     * Folding rules (YAML 1.2 §8.1.3): a single line break becomes a space, n consecutive
     * line breaks leave (n-1) line breaks, and breaks around **more deeply indented**
     * lines are kept as they are. Without the last rule, JSON examples embedded in a `>`
     * block collapse onto a single line (openai.yaml).
     */
    private fun fold(body: List<String>): String {
        val output = StringBuilder()
        var blanks = 0
        var first = true
        var previousDeeper = false

        for (line in body) {
            if (line.isBlank()) {
                blanks++
                continue
            }
            val deeper = line.startsWith(" ")
            if (!first) {
                output.append(
                    when {
                        // Breaks around a more deeply indented line are kept as they
                        // are — the folding rule does not apply.
                        deeper || previousDeeper -> "\n".repeat(blanks + 1)
                        blanks > 0 -> "\n".repeat(blanks)
                        else -> " "
                    },
                )
            }
            output.append(line)
            first = false
            blanks = 0
            previousDeeper = deeper
        }
        return output.toString()
    }

    private fun plainScalar(text: String): JsonElement {
        val value = text.trim()
        if (value.isEmpty() || value == "null" || value == "~") return JsonNull
        if (isQuoted(value)) return JsonPrimitive(unquote(value))

        when (value.first()) {
            '&' -> fail("Anchors (&) are not supported")
            '*' -> fail("Aliases (*) are not supported")
            '!' -> fail("Tags (!) are not supported")
            '%' -> fail("Directives (%) are not supported")
        }

        if (value == "true") return JsonPrimitive(true)
        if (value == "false") return JsonPrimitive(false)
        // Numbers are recognized with a regex: Kotlin's `toDoubleOrNull` inherits Java's
        // suffix rule and treats "1d" as 1.0 — that broke `enum: [1d, 7d]` in openai.yaml.
        if (NUMBER.matches(value)) {
            value.toLongOrNull()?.let { return JsonPrimitive(it) }
            value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        }
        return JsonPrimitive(value)
    }

    // --- line helpers ---

    private fun skipIgnorable() {
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (trimmed == "...") {
                index = lines.size
                return
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) index++ else return
        }
    }

    private fun indentOf(line: Int): Int {
        val text = lines[line]
        var count = 0
        while (count < text.length && text[count] == ' ') count++
        if (count < text.length && text[count] == '\t') fail("Tab indentation is not supported", line)
        return count
    }

    private fun contentOf(line: Int): String =
        stripComment(lines[line].substring(indentOf(line))).trimEnd()

    private fun stripComment(text: String): String {
        var position = 0
        while (position < text.length) {
            when (text[position]) {
                '"' -> position = skipQuoted(text, position, '"')
                '\'' -> position = skipQuoted(text, position, '\'')
                '#' -> if (position == 0 || text[position - 1] == ' ') return text.substring(0, position) else position++
                else -> position++
            }
        }
        return text
    }

    /** Position of the `:` that ends the key — quotes and flow blocks are not keys. */
    private fun keyEnd(content: String): Int? {
        var position = 0
        while (position < content.length) {
            when (content[position]) {
                '"' -> position = skipQuoted(content, position, '"')
                '\'' -> position = skipQuoted(content, position, '\'')
                // Only a bracket/brace at the start of the line opens a flow block. A brace
                // in the middle is normal in OpenAPI paths: `/pets/{petId}:` is a key, not a
                // flow value.
                '[', '{' -> if (position == 0) return null else position++
                ':' -> if (position + 1 == content.length || content[position + 1] == ' ') return position else position++
                else -> position++
            }
        }
        return null
    }

    /** For an unclosed quote the end of the text is returned — so the scan never loops forever. */
    private fun skipQuoted(text: String, start: Int, quote: Char): Int =
        quoteEnd(text, start, quote) ?: text.length

    /** One past the closing quote; `null` if the quote is not closed. */
    private fun quoteEnd(text: String, start: Int, quote: Char): Int? {
        var position = start + 1
        while (position < text.length) {
            if (text[position] == '\\' && quote == '"') {
                position += 2
                continue
            }
            if (text[position] == quote) return position + 1
            position++
        }
        return null
    }

    /** Text with an unfinished quote or flow bracket continues on the next line. */
    private fun incomplete(text: String): Boolean {
        var depth = 0
        var position = 0
        while (position < text.length) {
            when (val char = text[position]) {
                '"', '\'' -> position = quoteEnd(text, position, char) ?: return true
                '[', '{' -> { depth++; position++ }
                ']', '}' -> { depth--; position++ }
                else -> position++
            }
        }
        return depth > 0
    }

    private fun isQuoted(text: String): Boolean =
        text.length >= 2 && (
            (text.startsWith("\"") && text.endsWith("\"")) || (text.startsWith("'") && text.endsWith("'"))
            )

    private fun unquote(text: String): String = when {
        text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") ->
            unescape(text.substring(1, text.length - 1))
        text.length >= 2 && text.startsWith("'") && text.endsWith("'") ->
            text.substring(1, text.length - 1).replace("''", "'")
        else -> text
    }

    private fun unescape(text: String): String {
        if (!text.contains('\\')) return text
        val output = StringBuilder(text.length)
        var position = 0
        while (position < text.length) {
            val char = text[position]
            if (char != '\\' || position + 1 == text.length) {
                output.append(char)
                position++
                continue
            }
            when (val escaped = text[position + 1]) {
                'n' -> output.append('\n')
                't' -> output.append('\t')
                'r' -> output.append('\r')
                '0' -> output.append(' ')
                'u' -> {
                    val hex = text.substring(position + 2, minOf(position + 6, text.length))
                    output.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    position += 4
                }
                else -> output.append(escaped)
            }
            position += 2
        }
        return output.toString()
    }

    private fun String.trailingBackslashes(): Int = length - trimEnd('\\').length

    private fun fail(detail: String, line: Int = index): Nothing =
        throw YamlException("Line ${line + 1}: $detail")

    private enum class Chomp { STRIP, CLIP, KEEP }

    private companion object {
        val NUMBER = Regex("""[-+]?\d+(\.\d+)?([eE][-+]?\d+)?""")
    }
}

/** `[a, b]` and `{a: 1, b: c}` — OpenAPI often writes `required` and `enum` this way. */
private class FlowParser(private val text: String) {

    private var position = 0

    fun parse(): JsonElement {
        val value = parseValue()
        skipSpace()
        if (position < text.length) throw YamlException("Unexpected content in flow value: $text")
        return value
    }

    private fun parseValue(): JsonElement {
        skipSpace()
        if (position >= text.length) return JsonNull
        return when (text[position]) {
            '[' -> parseArray()
            '{' -> parseObject()
            else -> parseScalar()
        }
    }

    private fun parseArray(): JsonElement {
        position++
        val items = mutableListOf<JsonElement>()
        skipSpace()
        if (peek() == ']') {
            position++
            return JsonArray(items)
        }
        while (true) {
            items += parseValue()
            skipSpace()
            when (peek()) {
                ',' -> position++
                ']' -> { position++; return JsonArray(items) }
                else -> throw YamlException("Unterminated flow sequence: $text")
            }
        }
    }

    private fun parseObject(): JsonElement {
        position++
        val entries = LinkedHashMap<String, JsonElement>()
        skipSpace()
        if (peek() == '}') {
            position++
            return JsonObject(entries)
        }
        while (true) {
            skipSpace()
            val key = readToken(stopAt = ":,}")
            skipSpace()
            if (peek() == ':') position++
            entries[unquoteFlow(key)] = parseValue()
            skipSpace()
            when (peek()) {
                ',' -> position++
                '}' -> { position++; return JsonObject(entries) }
                else -> throw YamlException("Unterminated flow mapping: $text")
            }
        }
    }

    private fun parseScalar(): JsonElement {
        val token = readToken(stopAt = ",]}")
        val value = token.trim()
        return when {
            value.isEmpty() || value == "null" || value == "~" -> JsonNull
            value == "true" -> JsonPrimitive(true)
            value == "false" -> JsonPrimitive(false)
            value.startsWith("\"") || value.startsWith("'") -> JsonPrimitive(unquoteFlow(value))
            else -> value.toLongOrNull()?.let { JsonPrimitive(it) }
                ?: value.toDoubleOrNull()?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(value)
        }
    }

    private fun readToken(stopAt: String): String {
        val start = position
        while (position < text.length && text[position] !in stopAt) {
            val char = text[position]
            if (char == '"' || char == '\'') {
                position++
                while (position < text.length && text[position] != char) {
                    if (text[position] == '\\' && char == '"') position++
                    position++
                }
            }
            position++
        }
        return text.substring(start, position)
    }

    private fun unquoteFlow(token: String): String {
        val value = token.trim()
        return when {
            value.length >= 2 && value.startsWith("\"") && value.endsWith("\"") ->
                value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\n", "\n")
            value.length >= 2 && value.startsWith("'") && value.endsWith("'") ->
                value.substring(1, value.length - 1).replace("''", "'")
            else -> value
        }
    }

    private fun peek(): Char? = text.getOrNull(position)

    private fun skipSpace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }
}
