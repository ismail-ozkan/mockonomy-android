package com.mockonomy.data

import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.EnvironmentJson
import com.mockonomy.engine.model.SchemaMigration

/**
 * Export and import of environments.
 *
 * Format: **a single, self-contained JSON.** Large bodies are normally kept in files
 * under `bodies/` ([BodyStore]); on export they are all pulled inline with
 * [BodyStore.hydrate], so the recipient gets no broken file references. On import,
 * bodies above the threshold are moved back into files with [applyBody]. No separate
 * multi-file package (zip) is needed.
 */

/** The imported file uses a newer schema than this code base can read. */
class SchemaTooNewException(val fileVersion: Int, val supportedVersion: Int) :
    Exception("Environment schema v$fileVersion is newer than supported v$supportedVersion")

/**
 * Turns an environment into a shareable JSON string. With [redact] on, sensitive
 * headers are masked (on by default). The source environment is not modified; only
 * the string is produced.
 */
suspend fun exportEnvironment(environment: Environment, bodyStore: BodyStore, redact: Boolean): String {
    val hydrated = bodyStore.hydrate(environment)
    val prepared = if (redact) redactSensitiveHeaders(hydrated) else hydrated
    return EnvironmentJson.encode(prepared)
}

/**
 * Turns a JSON string into an importable [Environment]: the schema version is
 * checked, **new ids are assigned at every level** (existing environments are never
 * overwritten) and large bodies are moved back into files. The caller writes the
 * result with [EnvironmentStore.save].
 *
 * @throws SchemaTooNewException if the file uses a newer schema version
 * @throws kotlinx.serialization.SerializationException if the JSON is invalid
 */
suspend fun importEnvironment(json: String, bodyStore: BodyStore): Environment {
    val decoded = EnvironmentJson.decode(json)
    if (decoded.schemaVersion > Environment.CURRENT_SCHEMA_VERSION) {
        throw SchemaTooNewException(decoded.schemaVersion, Environment.CURRENT_SCHEMA_VERSION)
    }
    // An older file is upgraded to the current schema on import.
    val parsed = SchemaMigration.migrate(decoded)

    val routes = parsed.routes.map { route ->
        route.copy(
            id = IdGenerator.generate("route"),
            responses = route.responses.map { response ->
                val newId = IdGenerator.generate("resp")
                // Export pulled bodies inline; a file that still says FILE (edited by
                // hand) is safely treated as inline anyway — that file does not exist
                // locally. If the body exceeds the threshold, applyBody moves it back.
                val inlineBody = if (response.bodyType == BodyType.FILE) "" else response.body
                bodyStore.applyBody(
                    response.copy(id = newId, bodyType = BodyType.INLINE, body = inlineBody, bodyFile = null),
                    inlineBody,
                )
            },
        )
    }

    return parsed.copy(id = IdGenerator.generate("env"), routes = routes)
}
