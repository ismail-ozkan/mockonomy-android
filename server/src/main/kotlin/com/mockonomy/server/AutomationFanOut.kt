package com.mockonomy.server

/**
 * Reduces the results of an automation command across **all** running servers into a
 * single answer (on the ADB surface no target is chosen; the command applies to all of
 * them).
 *
 * Rule: if the command applied to at least one server the result is a success — a state
 * or route may only be defined in some environments, and requiring it in all of them
 * would make the command unusable. Skipped servers appear by name in the message so the
 * test author notices the gap. If it applied to none, the first error is returned; the
 * `available` lists are merged (without duplicates), otherwise the answer to "which
 * states exist" would be limited to a random server's.
 */
internal fun combineAutomationResults(results: List<Pair<String, AutomationResult>>): AutomationResult {
    require(results.isNotEmpty()) { "combineAutomationResults needs at least one result" }

    val applied = results.filter { it.second is AutomationResult.Ok }
    val skipped = results.filterNot { it.second is AutomationResult.Ok }

    if (applied.isEmpty()) return merged(results)

    val appliedText = applied.joinToString("; ") { (name, result) -> "$name: ${result.message}" }
    val skippedText = if (skipped.isEmpty()) {
        ""
    } else {
        " Skipped ${skipped.size}: " + skipped.joinToString("; ") { (name, result) -> "$name: ${result.message}" }
    }
    return AutomationResult.Ok("Applied to ${applied.size} of ${results.size} servers. $appliedText$skippedText")
}

/** Applied to no server: the shape of the first error is kept, options are merged. */
private fun merged(results: List<Pair<String, AutomationResult>>): AutomationResult {
    val available = results.flatMap { it.second.availableOptions() }.distinct()
    return when (val first = results.first().second) {
        is AutomationResult.NotFound -> first.copy(available = available)
        is AutomationResult.BadRequest -> first.copy(available = available)
        else -> first
    }
}

private fun AutomationResult.availableOptions(): List<String> = when (this) {
    is AutomationResult.NotFound -> available
    is AutomationResult.BadRequest -> available
    else -> emptyList()
}
