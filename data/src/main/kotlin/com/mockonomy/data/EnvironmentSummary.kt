package com.mockonomy.data

import com.mockonomy.engine.model.Environment

/**
 * The [EnvironmentStore.list] summary of a single file in the `environments/` directory.
 *
 * A file that fails validation does not crash the app — it shows up in the list as
 * [Corrupt] and the user can delete it (spec/schema/environment-v1.md, "Corrupt file
 * behavior").
 */
sealed interface EnvironmentSummary {
    /**
     * The file name without its extension (`<id>.json` → `<id>`). For [Valid] it equals
     * the real `Environment.id`; for [Corrupt] only the file name is available because
     * the content cannot be read — [EnvironmentStore.delete] uses this value the same
     * way in both cases.
     */
    val id: String

    data class Valid(val environment: Environment) : EnvironmentSummary {
        override val id: String get() = environment.id
    }

    data class Corrupt(override val id: String, val reason: String) : EnvironmentSummary
}
