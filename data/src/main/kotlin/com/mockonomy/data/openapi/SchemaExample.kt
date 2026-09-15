package com.mockonomy.data.openapi

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Produces an example body from an OpenAPI schema.
 *
 * Priority follows the document: `example` → `examples` → generated from the schema.
 * Generation is deliberately dumb: `string` → `"string"`, `integer` → `0`. Producing
 * smarter values based on format (`date-time`, `uuid`) is tempting but out of scope —
 * the user can already edit the body in the editor; we provide the skeleton.
 *
 * `$ref` is only resolved within the document (`#/components/schemas/X`). **Cycle
 * protection is required:** self-referencing schemas (`Node.children: [Node]`) are
 * common in real files and loop forever without it.
 */
internal class SchemaExample(private val root: JsonObject) {

    /** Unresolved references, cut cycles — the caller reports these to the user. */
    val notes = linkedSetOf<String>()

    fun generate(schema: JsonElement?): JsonElement = build(schema, mutableSetOf(), 0)

    private fun build(schema: JsonElement?, visited: MutableSet<String>, depth: Int): JsonElement {
        if (schema !is JsonObject) return JsonNull
        if (depth > MAX_DEPTH) {
            notes += "Schema nesting deeper than $MAX_DEPTH levels was cut off"
            return JsonNull
        }

        val reference = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull
        if (reference != null) return buildReference(reference, visited, depth)

        schema["example"]?.let { return it }
        (schema["enum"] as? JsonArray)?.firstOrNull()?.let { return it }

        (schema["allOf"] as? JsonArray)?.let { branches ->
            return merge(branches.map { build(it, visited, depth + 1) })
        }
        ((schema["oneOf"] ?: schema["anyOf"]) as? JsonArray)?.firstOrNull()?.let {
            notes += "oneOf/anyOf schemas: the first variant was used"
            return build(it, visited, depth + 1)
        }

        val type = (schema["type"] as? JsonPrimitive)?.contentOrNull
        return when {
            type == "array" -> JsonArray(listOf(build(schema["items"], visited, depth + 1)))
            type == "object" || schema["properties"] != null -> buildObject(schema, visited, depth)
            type == "string" -> JsonPrimitive("string")
            type == "integer" || type == "number" -> JsonPrimitive(0)
            type == "boolean" -> JsonPrimitive(false)
            else -> JsonNull
        }
    }

    private fun buildReference(reference: String, visited: MutableSet<String>, depth: Int): JsonElement {
        if (!reference.startsWith(LOCAL_PREFIX)) {
            notes += "External \$ref left empty: $reference"
            return JsonNull
        }
        if (!visited.add(reference)) {
            notes += "Circular \$ref cut off: ${reference.removePrefix(LOCAL_PREFIX)}"
            return JsonNull
        }

        val target = reference.removePrefix(LOCAL_PREFIX).split("/")
            .fold<String, JsonElement?>(root) { node, segment -> (node as? JsonObject)?.get(segment) }
        if (target == null) {
            notes += "Unresolved \$ref: $reference"
            visited.remove(reference)
            return JsonNull
        }

        val value = build(target, visited, depth + 1)
        visited.remove(reference)
        return value
    }

    private fun buildObject(schema: JsonObject, visited: MutableSet<String>, depth: Int): JsonElement {
        val properties = schema["properties"] as? JsonObject ?: return JsonObject(emptyMap())
        val entries = LinkedHashMap<String, JsonElement>()
        for ((name, property) in properties) {
            entries[name] = build(property, visited, depth + 1)
        }
        return JsonObject(entries)
    }

    /** `allOf` merge: only objects are merged, the last one wins. */
    private fun merge(parts: List<JsonElement>): JsonElement {
        val objects = parts.filterIsInstance<JsonObject>()
        if (objects.isEmpty()) return parts.firstOrNull() ?: JsonNull

        val entries = LinkedHashMap<String, JsonElement>()
        objects.forEach { entries.putAll(it) }
        return JsonObject(entries)
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val LOCAL_PREFIX = "#/"
    }
}

/** `example` is used directly; `examples` is a `{ name: { value: ... } }` map. */
internal fun exampleOf(media: JsonObject): JsonElement? {
    media["example"]?.let { return it }
    val examples = media["examples"] as? JsonObject ?: return null
    return (examples.values.firstOrNull() as? JsonObject)?.get("value")
}
