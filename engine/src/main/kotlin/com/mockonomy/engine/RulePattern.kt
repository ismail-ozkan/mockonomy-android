package com.mockonomy.engine

/**
 * Validity of rule patterns.
 *
 * The editor's warning and the engine's decision must come from **the same
 * compiler**: if the editor says "valid" and the engine drops the rule, the user
 * never sees the error. That is why the check lives in `:engine` and
 * [RuleEvaluator] uses it too.
 *
 * Message text lives in `:app`, not here — the same split as `RoutePath`.
 */
object RulePattern {

    /** Whether the pattern compiles. */
    fun isValidRegex(pattern: String): Boolean = compile(pattern) != null

    internal fun compile(pattern: String): Regex? = try {
        Regex(pattern)
    } catch (_: IllegalArgumentException) {
        null
    }
}
