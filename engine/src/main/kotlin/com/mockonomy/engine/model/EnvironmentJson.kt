package com.mockonomy.engine.model

import kotlinx.serialization.json.Json

/**
 * The single configuration point for reading and writing environment files.
 *
 * Why these settings:
 * - `ignoreUnknownKeys`: forward compatibility. A field added in a newer version
 *   does not break the file when it is opened by an older version
 *   (spec/schema/environment-v1.md, "General rules").
 * - `encodeDefaults`: the exported file should be complete and explicit. Users
 *   should be able to read and edit it; invisible defaults get in the way.
 * - `prettyPrint` + two spaces: the file must be human-readable and diffable in git.
 */
object EnvironmentJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun decode(text: String): Environment = format.decodeFromString(Environment.serializer(), text)

    fun encode(environment: Environment): String =
        format.encodeToString(Environment.serializer(), environment)
}
