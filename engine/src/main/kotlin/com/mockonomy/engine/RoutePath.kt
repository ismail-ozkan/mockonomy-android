package com.mockonomy.engine

/**
 * Why a route path is invalid. There is **no** message text here — wording is the
 * UI's job; this layer is shared across platforms (`:engine` is pure Kotlin, ready
 * for KMP).
 */
enum class PathProblem {
    Empty,
    MissingLeadingSlash,
    ContainsWhitespace,
    EmptySegment,
    EmptyParameterName,
    InvalidParameterName,
    DuplicateParameterName,
}

/**
 * Route path validation.
 *
 * Matching itself lives in [RequestMatcher]; this only checks whether the path the
 * user typed is *meaningful*. The rules must mirror exactly how the engine reads a
 * path: split on `/`, and a segment starting with `:` is a parameter
 * (`spec/behavior/matching.md`).
 *
 * A query string (`?a=b`) is deliberately invalid: matching only looks at the path;
 * matching on query values is the rule engine's job.
 */
object RoutePath {

    fun validate(path: String): PathProblem? {
        if (path.isEmpty()) return PathProblem.Empty
        if (!path.startsWith("/")) return PathProblem.MissingLeadingSlash
        if (path.any { it.isWhitespace() }) return PathProblem.ContainsWhitespace

        // "/" on its own is valid (root). A trailing "/" is ignored by matching, so it
        // does not count as a segment here either.
        val segments = path.trim('/').let { if (it.isEmpty()) emptyList() else it.split('/') }

        val parameterNames = mutableSetOf<String>()
        for (segment in segments) {
            if (segment.isEmpty()) return PathProblem.EmptySegment
            if (!segment.startsWith(PARAMETER_PREFIX)) {
                if (segment.any { it in FORBIDDEN_CHARACTERS }) return PathProblem.InvalidParameterName
                continue
            }

            val name = segment.removePrefix(PARAMETER_PREFIX)
            if (name.isEmpty()) return PathProblem.EmptyParameterName
            if (!name.all { it.isLetterOrDigit() || it == '_' }) return PathProblem.InvalidParameterName
            if (!parameterNames.add(name)) return PathProblem.DuplicateParameterName
        }

        return null
    }

    private const val PARAMETER_PREFIX = ":"

    /** Query and fragment are not part of the path; `:` is only meaningful at the start of a segment. */
    private val FORBIDDEN_CHARACTERS = charArrayOf('?', '#', ':')
}
