package com.mockonomy.ui.screens.settings

import com.mockonomy.engine.model.Environment

/**
 * State machine edits — pure, testable `Environment` transformations. The UI calls them;
 * the logic lives here (the RouteEdits pattern).
 *
 * The critical point: a state name is referenced in **several places** — `initialState` and
 * the responses' `whenState` and `setState`. Renaming and deleting must not silently break
 * those references; the functions below update all of them together
 * (`spec/behavior/state-machine.md`).
 */

/** Adds a new empty state. The user types the name afterwards; duplicates are warned about in the UI. */
fun Environment.withStateAdded(name: String = ""): Environment =
    copy(states = states + name)

/**
 * Renames the state at [index] to [newName] and updates **all references** together:
 * `initialState` and every response's `whenState` / `setState`.
 */
fun Environment.withStateRenamed(index: Int, newName: String): Environment {
    val old = states.getOrNull(index) ?: return this
    if (newName == old) return copy(states = states.replaceAt(index, newName))
    return copy(
        states = states.replaceAt(index, newName),
        initialState = if (initialState == old) newName else initialState,
        routes = routes.map { route ->
            route.copy(
                responses = route.responses.map { response ->
                    response.copy(
                        whenState = if (response.whenState == old) newName else response.whenState,
                        setState = if (response.setState == old) newName else response.setState,
                    )
                },
            )
        },
    )
}

/**
 * Deletes the state at [index] and clears references to it: a `whenState` / `setState`
 * pointing at it becomes `null` (otherwise a response that can never match would be
 * orphaned), and if `initialState` equals it, it falls back to the first remaining state.
 */
fun Environment.withStateDeleted(index: Int): Environment {
    val removed = states.getOrNull(index) ?: return this
    val remaining = states.filterIndexed { i, _ -> i != index }
    return copy(
        states = remaining,
        initialState = when {
            initialState == removed -> remaining.firstOrNull()
            else -> initialState
        },
        routes = routes.map { route ->
            route.copy(
                responses = route.responses.map { response ->
                    response.copy(
                        whenState = response.whenState?.takeIf { it != removed },
                        setState = response.setState?.takeIf { it != removed },
                    )
                },
            )
        },
    )
}

/** Sets (or clears) the initial state. */
fun Environment.withInitialState(state: String?): Environment =
    copy(initialState = state)

private fun List<String>.replaceAt(index: Int, value: String): List<String> =
    mapIndexed { i, existing -> if (i == index) value else existing }
