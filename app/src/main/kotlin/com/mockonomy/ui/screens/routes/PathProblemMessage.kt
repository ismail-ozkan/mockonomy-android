package com.mockonomy.ui.screens.routes

import com.mockonomy.engine.PathProblem

/**
 * The user-facing form of [PathProblem]. The text lives here, not in `:engine`: the engine
 * is shared across platforms, while the words belong to this app.
 *
 * Every message says what to do, not what is wrong — "Invalid path" does not move the user
 * a single step forward.
 */
fun pathProblemMessage(problem: PathProblem): String = when (problem) {
    PathProblem.Empty -> "Enter a path, e.g. /users"
    PathProblem.MissingLeadingSlash -> "Start the path with /"
    PathProblem.ContainsWhitespace -> "Paths can't contain spaces"
    PathProblem.EmptySegment -> "Remove the empty segment (//)"
    PathProblem.EmptyParameterName -> "Name the parameter, e.g. /users/:id"
    PathProblem.InvalidParameterName -> "Use letters, digits and _ — and : only at the start of a segment"
    PathProblem.DuplicateParameterName -> "Each parameter needs a different name"
}
