package com.mockonomy.engine.model

/**
 * Upgrades older schema versions to [Environment.CURRENT_SCHEMA_VERSION].
 *
 * Steps are one-way and chained: each `migrateNtoN+1` upgrades exactly one
 * version, and `migrate` applies them in order.
 *
 * A strictly **newer** version (greater than what this code reads) is not the
 * migration's job; the caller (e.g. the storage layer) handles it with a separate
 * error.
 */
object SchemaMigration {

    /**
     * Upgrades [environment] to the current schema version. Returns it unchanged if
     * it is already current. Throws for an unknown version (older than supported,
     * less than 1).
     */
    fun migrate(environment: Environment): Environment {
        var current = environment
        if (current.schemaVersion < 1) {
            throw IllegalArgumentException("Unknown schemaVersion: ${current.schemaVersion}")
        }
        if (current.schemaVersion == 1) {
            current = migrate1to2(current)
        }
        return current
    }

    /**
     * v1 → v2. The fields added in v2 (`key`, `states`, `initialState`,
     * `whenState`, `setState`, `sequentialBehavior`) are all optional with
     * defaults; since they are missing, the deserializer has already filled them
     * in. The migration only bumps the version stamp — but the path is actually
     * exercised once, so it is infrastructure we can trust when a breaking change
     * is needed later.
     */
    private fun migrate1to2(environment: Environment): Environment =
        environment.copy(schemaVersion = 2)
}
