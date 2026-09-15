package com.mockonomy.engine

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * User-presentable form of invalid JSON. Line and column start at 1 (the way
 * editors count them).
 */
data class JsonProblem(
    val line: Int,
    val column: Int,
    val message: String,
)

/**
 * JSON helpers for the body editor.
 *
 * Invalid JSON is **not an error**: the user may want to serve plain text, XML or
 * a deliberately broken body. So nothing is blocked here — questions are only
 * answered when asked.
 */
object JsonBody {

    /**
     * Indented form; `null` if it cannot be parsed (the caller leaves the text as
     * is). Key order is preserved — reorganizing the user's document is not the
     * formatter's job.
     */
    fun format(text: String): String? = try {
        val element = LENIENT_READER.parseToJsonElement(text)
        PRETTY_WRITER.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    } catch (error: SerializationException) {
        null
    }

    /** `null` if valid. Blank text is not valid — there is nothing to parse. */
    fun validate(text: String): JsonProblem? {
        if (text.isBlank()) return JsonProblem(1, 1, "Empty body")
        return try {
            LENIENT_READER.parseToJsonElement(text)
            null
        } catch (error: SerializationException) {
            val message = error.message.orEmpty()
            val offset = extractOffset(message)
            val (line, column) = lineAndColumn(text, offset)
            JsonProblem(line, column, firstSentence(message))
        }
    }

    /**
     * kotlinx.serialization reports errors as `... at offset 42: ...`. If no offset
     * is found the start of the text is assumed — pointing at the beginning is less
     * misleading than pointing at a wrong position.
     */
    private fun extractOffset(message: String): Int =
        OFFSET_PATTERN.find(message)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
        val safeOffset = offset.coerceIn(0, text.length)
        var line = 1
        var lastNewline = -1
        for (index in 0 until safeOffset) {
            if (text[index] == '\n') {
                line++
                lastNewline = index
            }
        }
        return line to (safeOffset - lastNewline)
    }

    /**
     * Library messages span several lines and quote JSON; only the first sentence
     * fits on the single line under the editor.
     */
    private fun firstSentence(message: String): String {
        val firstLine = message.lineSequence().firstOrNull()?.trim().orEmpty()
        return firstLine.ifEmpty { "Invalid JSON" }
    }

    /** Lenient reading: the goal is to understand the user's intent, not to enforce a schema. */
    private val LENIENT_READER = Json { isLenient = false }

    private val PRETTY_WRITER = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val OFFSET_PATTERN = Regex("""offset (\d+)""")
}
