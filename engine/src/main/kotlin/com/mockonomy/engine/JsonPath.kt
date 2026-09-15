package com.mockonomy.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Follows a path like `user.address.city` through a JSON body.
 *
 * The template engine (`{{body.a.b.c}}`) and the rule engine (`BODY` target) use
 * the **same** notation; making users learn two syntaxes would be unfair. Since
 * this read is the only thing they need to share, it is a single-function object
 * rather than a class.
 */
internal object JsonPath {

    /**
     * `null` if the path is not found, there is no body, or the body is not JSON.
     *
     * The difference between "missing" and "empty string" matters to callers: the
     * template engine turns both into an empty value, while the rule engine's
     * `EXISTS` tells them apart.
     */
    fun read(body: String?, dotted: String): String? {
        if (body.isNullOrBlank()) return null
        var current = try {
            lenient.parseToJsonElement(body)
        } catch (_: IllegalArgumentException) {
            return null
        }

        for (key in dotted.split('.')) {
            val obj = current as? JsonObject ?: return null
            current = obj[key] ?: return null
        }

        val primitive = current as? JsonPrimitive ?: return current.toString()
        // Strings are read without quotes; everything else in its JSON representation.
        return if (primitive.isString) primitive.content else primitive.toString()
    }

    /**
     * The body is the user's data, not template input; a single stray comma should
     * not silence dot notation entirely.
     */
    private val lenient = Json { isLenient = true }
}
