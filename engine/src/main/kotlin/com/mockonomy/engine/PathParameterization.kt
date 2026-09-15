package com.mockonomy.engine

/**
 * A suggestion for one parameter segment of a concrete path.
 *
 * [segmentIndex] is the 0-based position in `path.trim('/').split('/')`;
 * [value] is the original concrete segment (`"42"`), [name] the suggested
 * parameter name (without `:`, `"userId"`).
 */
data class PathParameter(
    val segmentIndex: Int,
    val name: String,
    val value: String,
)

/**
 * A parameterization suggestion for a path. If [parameters] is empty there is no
 * suggestion and [suggestedPath] == [originalPath].
 */
data class ParameterSuggestion(
    val originalPath: String,
    val suggestedPath: String,
    val parameters: List<PathParameter>,
)

/**
 * **Suggests** generalizing concrete paths into parameterized routes when a mock is
 * created from a request log (`spec/behavior/path-parameterization.md`).
 *
 * An authoring-time helper, not a serve-time one: the matcher already handles
 * `:param` segments in [RequestMatcher]. This only decides which segments are
 * parameter candidates (numbers or UUIDs) and what the suggested name is (derived
 * from the preceding static segment). It is deterministic and pure, so an iOS
 * implementation can produce the same suggestion.
 */
object PathParameterization {

    fun suggest(path: String): ParameterSuggestion {
        val segments = path.trim('/').let { if (it.isEmpty()) emptyList() else it.split('/') }

        val parameters = mutableListOf<PathParameter>()
        val usedNames = mutableSetOf<String>()
        val rendered = segments.toMutableList()

        segments.forEachIndexed { index, segment ->
            if (segment.startsWith(PARAMETER_PREFIX)) return@forEachIndexed
            if (!isNumeric(segment) && !isUuid(segment)) return@forEachIndexed

            val name = uniqueName(nameFor(segments, index), usedNames)
            usedNames += name
            parameters += PathParameter(segmentIndex = index, name = name, value = segment)
            rendered[index] = "$PARAMETER_PREFIX$name"
        }

        if (parameters.isEmpty()) return ParameterSuggestion(path, path, emptyList())

        val suggested = "/" + rendered.joinToString("/")
        return ParameterSuggestion(path, suggested, parameters)
    }

    /** The name is derived from the nearest preceding **static** segment. */
    private fun nameFor(segments: List<String>, index: Int): String {
        for (i in index - 1 downTo 0) {
            val prev = segments[i]
            if (prev.startsWith(PARAMETER_PREFIX)) continue
            if (isNumeric(prev) || isUuid(prev)) continue
            return singularize(prev) + "Id"
        }
        return "id"
    }

    /** Naive singularization: only a trailing `s` is dropped. The name is cosmetic. */
    private fun singularize(word: String): String =
        if (word.length > 1 && word.endsWith("s")) word.dropLast(1) else word

    private fun uniqueName(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while ("$base$n" in used) n++
        return "$base$n"
    }

    private fun isNumeric(segment: String): Boolean =
        segment.isNotEmpty() && segment.all { it in '0'..'9' }

    private fun isUuid(segment: String): Boolean = UUID_REGEX.matches(segment)

    private const val PARAMETER_PREFIX = ":"

    private val UUID_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
}
