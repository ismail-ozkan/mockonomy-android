package com.mockonomy.engine.template

import com.mockonomy.engine.JsonPath
import com.mockonomy.engine.MockRequest
import kotlin.time.ExperimentalTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive

/**
 * Expands `{{ ... }}` expressions in a response body.
 *
 * Behavior contract: `spec/behavior/templating.md`. This object implements that
 * document; behavior changes are settled there first.
 *
 * Stateless and pure: everything that varies comes from [TemplateContext].
 */
object TemplateEngine {

    /**
     * Evaluates the expressions in [text] and returns the result.
     *
     * Unrecognized expressions are left as they are — real user data may contain
     * `{{`, and deleting something we do not understand is the worst option.
     */
    fun render(
        text: String,
        request: MockRequest,
        pathParams: Map<String, String>,
        context: TemplateContext,
    ): String {
        // Most bodies contain no template; they should not pay for the scan.
        if (!text.contains(OPEN)) return text

        val output = StringBuilder(text.length)
        var cursor = 0
        while (cursor < text.length) {
            val start = text.indexOf(OPEN, cursor)
            if (start < 0) {
                output.append(text, cursor, text.length)
                break
            }
            output.append(text, cursor, start)

            val raw = text.startsWith(RAW_OPEN, start)
            val open = if (raw) RAW_OPEN else OPEN
            val close = if (raw) RAW_CLOSE else CLOSE
            val end = text.indexOf(close, start + open.length)
            if (end < 0) {
                // Unclosed block: everything that remains is plain text.
                output.append(text, start, text.length)
                break
            }

            val expression = text.substring(start + open.length, end).trim()
            val value = evaluate(expression, request, pathParams, context)
            if (value == null) {
                output.append(text, start, end + close.length)
            } else {
                output.append(if (raw) value else jsonEscape(value))
            }
            cursor = end + close.length
        }
        return output.toString()
    }

    /**
     * Whether an expression is recognized — used for the editor warning.
     *
     * Recognition is defined by the engine's *own* evaluation: "recognized" means
     * "a value is substituted when it runs". A separate list of helpers would let a
     * helper that was never added to the list keep showing a warning in the
     * editor — the same reasoning as the regex check in the rule editor
     * (`RulePattern`).
     *
     * [expression] is given without `{{` and `}}`.
     */
    fun recognizes(expression: String): Boolean =
        evaluate(expression.trim(), EMPTY_REQUEST, emptyMap(), TemplateContext.system()) != null

    /** `null` for an expression that cannot be evaluated — the caller leaves it as is. */
    private fun evaluate(
        expression: String,
        request: MockRequest,
        pathParams: Map<String, String>,
        context: TemplateContext,
    ): String? {
        val tokens = tokenize(expression) ?: return null
        val name = tokens.first()
        val args = tokens.drop(1)

        return when {
            name == "uuid" -> if (args.isEmpty()) uuidV4(context) else null
            name == "now" -> now(args, context)
            name == "timestamp" -> if (args.isEmpty()) epochSeconds(context) else null
            name == "random.int" -> randomInt(args, context)
            name == "random.string" -> randomString(args, context)
            name == "random.bool" -> if (args.isEmpty()) context.random.nextBoolean().toString() else null

            // Keyed helpers take no arguments, and their key cannot be empty.
            args.isNotEmpty() -> null
            name.startsWith(PATH) -> lookupKey(name, PATH) { pathParams[it].orEmpty() }
            name.startsWith(QUERY) -> lookupKey(name, QUERY) { request.query[it]?.firstOrNull().orEmpty() }
            name.startsWith(HEADER) -> lookupKey(name, HEADER) { header(request, it) }
            name.startsWith(BODY) -> lookupKey(name, BODY) { JsonPath.read(request.body, it).orEmpty() }
            else -> null
        }
    }

    private fun lookupKey(name: String, prefix: String, read: (String) -> String): String? {
        val key = name.substring(prefix.length)
        return if (key.isEmpty()) null else read(key)
    }

    // --- helpers ---

    /** HTTP header names are case-insensitive. */
    private fun header(request: MockRequest, name: String): String =
        request.headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value?.firstOrNull()
            .orEmpty()

    /**
     * `java.util.UUID` is deliberately not used: `:engine` stays away from `java.*`
     * APIs to remain portable to Kotlin Multiplatform. Version and variant bits
     * are set according to RFC 4122 §4.4.
     */
    private fun uuidV4(context: TemplateContext): String {
        val bytes = ByteArray(16)
        context.random.nextBytes(bytes)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        val hex = StringBuilder(36)
        for (index in bytes.indices) {
            if (index == 4 || index == 6 || index == 8 || index == 10) hex.append('-')
            hex.append(HEX[(bytes[index].toInt() shr 4) and 0x0f])
            hex.append(HEX[bytes[index].toInt() and 0x0f])
        }
        return hex.toString()
    }

    @OptIn(ExperimentalTime::class)
    private fun epochSeconds(context: TemplateContext): String =
        context.clock.now().epochSeconds.toString()

    @OptIn(ExperimentalTime::class, FormatStringsInDatetimeFormats::class)
    private fun now(args: List<String>, context: TemplateContext): String? {
        if (args.size > 1) return null
        // The local time zone is not used: the same configuration must produce the
        // same output on two devices.
        val moment = context.clock.now().toLocalDateTime(TimeZone.UTC)
        if (args.isEmpty()) return ISO_SECONDS.format(moment) + "Z"

        return try {
            LocalDateTime.Format { byUnicodePattern(args[0]) }.format(moment)
        } catch (_: RuntimeException) {
            // An unparseable, unsupported or type-incompatible pattern — three
            // different exception types, one answer: leave it untouched.
            null
        }
    }

    private fun randomInt(args: List<String>, context: TemplateContext): String? {
        if (args.size != 2) return null
        val min = args[0].toIntOrNull() ?: return null
        val max = args[1].toIntOrNull() ?: return null
        if (min > max) return null
        // nextInt excludes the upper bound; the contract includes both ends.
        return context.random.nextInt(min, max + 1).toString()
    }

    private fun randomString(args: List<String>, context: TemplateContext): String? {
        if (args.size != 1) return null
        val length = args[0].toIntOrNull() ?: return null
        if (length < 0) return null
        return buildString(length) {
            repeat(length) { append(ALPHANUMERIC[context.random.nextInt(ALPHANUMERIC.length)]) }
        }
    }

    // --- parsing ---

    /**
     * Splits the expression into the helper name and its arguments. Quoted arguments
     * may contain spaces (`now "yyyy-MM-dd HH:mm"`). An unclosed quote is invalid.
     */
    private fun tokenize(expression: String): List<String>? {
        val tokens = mutableListOf<String>()
        var index = 0
        while (index < expression.length) {
            val char = expression[index]
            when {
                char.isWhitespace() -> index++
                char == '"' -> {
                    val end = expression.indexOf('"', index + 1)
                    if (end < 0) return null
                    tokens += expression.substring(index + 1, end)
                    index = end + 1
                }
                else -> {
                    var end = index
                    while (end < expression.length && !expression[end].isWhitespace()) end++
                    tokens += expression.substring(index, end)
                    index = end
                }
            }
        }
        return tokens.ifEmpty { null }
    }

    /**
     * Makes the value safe to place inside a JSON string. Escaping rules are not
     * written by hand: the library already gets them right; we only strip the
     * outer quotes it adds.
     */
    private fun jsonEscape(value: String): String {
        val quoted = JsonPrimitive(value).toString()
        return quoted.substring(1, quoted.length - 1)
    }

    @OptIn(FormatStringsInDatetimeFormats::class)
    private val ISO_SECONDS = LocalDateTime.Format { byUnicodePattern("yyyy-MM-dd'T'HH:mm:ss") }

    private const val OPEN = "{{"
    private const val CLOSE = "}}"
    private const val RAW_OPEN = "{{{"
    private const val RAW_CLOSE = "}}}"

    private const val PATH = "path."
    private const val QUERY = "query."
    private const val HEADER = "header."
    private const val BODY = "body."

    /** For [recognizes]: no helper's result depends on the request content, existence is enough. */
    private val EMPTY_REQUEST = MockRequest(method = "GET", path = "/")

    private const val HEX = "0123456789abcdef"
    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
}
