package com.mockonomy.data.openapi

import com.mockonomy.data.IdGenerator
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Converts an OpenAPI 3.x document into routes.
 *
 * Scope: 3.x only (Swagger 2.0 is rejected with an explicit error), JSON and YAML, with
 * our own minimal reader. **Full compatibility is not claimed** — everything that is
 * skipped is reported with a reason in [ImportPreview.issues] and shown to the user
 * before importing.
 */
object OpenApiImport {

    fun parse(text: String): ImportResult {
        val root = try {
            if (text.trimStart().startsWith("{")) Json.parseToJsonElement(text) else Yaml.parse(text)
        } catch (error: Exception) {
            // YamlException and SerializationException — both mean the same thing: the
            // file could not be read; show the reason to the user.
            return ImportResult.Failure(error.message ?: "The file could not be parsed")
        }

        if (root !is JsonObject) return ImportResult.Failure("The file is not an OpenAPI document")
        if (root["swagger"] != null) {
            return ImportResult.Failure("Swagger 2.0 is not supported — convert the file to OpenAPI 3.x first")
        }

        val version = root.string("openapi")
            ?: return ImportResult.Failure("Missing 'openapi' field — this does not look like an OpenAPI document")
        if (!version.startsWith("3.")) {
            return ImportResult.Failure("Only OpenAPI 3.x is supported (found $version)")
        }

        val paths = root["paths"] as? JsonObject
        if (paths == null || paths.isEmpty()) return ImportResult.Failure("The document has no paths")

        return ImportResult.Success(Reader(root).read(paths))
    }
}

/**
 * The result of an import attempt. A format-neutral carrier: both the OpenAPI and the
 * Mockoon parsers ([com.mockonomy.data.mockoon.MockoonImport]) return it — both produce
 * the same [ImportPreview] and show it on the same screen.
 */
sealed interface ImportResult {
    data class Success(val preview: ImportPreview) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * The import preview — the user reviews and confirms it.
 *
 * Routes are fully built here; the only work left at confirmation is putting them
 * into an environment. This way the number shown in the preview equals the number
 * imported.
 */
data class ImportPreview(
    val title: String,
    val version: String,
    val prefix: String,
    val routes: List<Route>,
    val issues: List<ImportIssue>,
    /** Environment-wide default latency — only filled by the Mockoon import. */
    val defaultLatencyMs: Int = 0,
) {
    val responseCount: Int get() = routes.sumOf { it.responses.size }

    val statusCodes: List<Int>
        get() = routes.flatMap { route -> route.responses.map { it.statusCode } }.distinct().sorted()
}

/** Something that was skipped or could not be imported fully. [location] points into the file. */
data class ImportIssue(val location: String, val reason: String)

/** A new environment: the name comes from the document, the port is the default. */
fun ImportPreview.toEnvironment(): Environment = Environment(
    schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
    id = IdGenerator.generate("env"),
    name = title.ifBlank { "Imported API" },
    port = Environment.DEFAULT_PORT,
    prefix = prefix,
    defaultLatencyMs = defaultLatencyMs,
    routes = routes,
)

/** Adding to an existing environment: routes are appended, environment settings are untouched. */
fun Environment.withImported(preview: ImportPreview): Environment = copy(routes = routes + preview.routes)

private class Reader(private val root: JsonObject) {

    private val issues = mutableListOf<ImportIssue>()
    private val schemaExample = SchemaExample(root)

    fun read(paths: JsonObject): ImportPreview {
        val routes = mutableListOf<Route>()
        for ((path, item) in paths) {
            if (item !is JsonObject) {
                issues += ImportIssue(path, "Path item is not an object")
                continue
            }
            if (item["\$ref"] != null) {
                issues += ImportIssue(path, "Path items defined with \$ref are not supported")
                continue
            }

            val routePath = toRoutePath(path)
            if (routePath == null) {
                issues += ImportIssue(path, "Path segments that mix text and parameters are not supported")
                continue
            }

            for ((key, operation) in item) {
                val method = METHODS[key.lowercase()]
                if (method == null) {
                    if (key.lowercase() == "trace") issues += ImportIssue("$path TRACE", "TRACE is not supported")
                    continue
                }
                if (operation !is JsonObject) continue
                readOperation(path, routePath, method, operation)?.let { routes += it }
            }
        }

        schemaExample.notes.forEach { issues += ImportIssue("components/schemas", it) }

        return ImportPreview(
            title = (root["info"] as? JsonObject)?.string("title").orEmpty(),
            version = (root["info"] as? JsonObject)?.string("version").orEmpty(),
            prefix = readPrefix(),
            routes = routes,
            issues = issues.toList(),
        )
    }

    private fun readOperation(
        path: String,
        routePath: String,
        method: HttpMethod,
        operation: JsonObject,
    ): Route? {
        val location = "$method $path"
        val responses = operation["responses"] as? JsonObject
        if (responses == null || responses.isEmpty()) {
            issues += ImportIssue(location, "The operation declares no responses")
            return null
        }

        val built = responses.mapNotNull { (key, value) -> readResponse(location, key, value) }
        if (built.isEmpty()) return null

        // Default: the lowest 2xx. Otherwise the first response in the list — a route must
        // always have exactly one default (schema rule 7).
        val default = built.filter { it.statusCode in 200..299 }.minByOrNull { it.statusCode } ?: built.first()

        return Route(
            id = IdGenerator.generate("route"),
            method = method,
            path = routePath,
            responses = built.map { it.copy(isDefault = it === default) },
        )
    }

    private fun readResponse(location: String, key: String, value: JsonElement): RouteResponse? {
        val status = key.toIntOrNull()
        if (status == null || status !in 100..599) {
            issues += ImportIssue(location, "Response '$key' was skipped — only numeric status codes are imported")
            return null
        }

        val response = resolve(value) as? JsonObject
        if (response == null) {
            issues += ImportIssue("$location $key", "The response object could not be resolved")
            return null
        }
        if (response["headers"] != null) {
            issues += ImportIssue("$location $key", "Response headers declared in the document were not imported")
        }

        val content = response["content"] as? JsonObject
        val mediaType = pickMediaType(content)
        val media = mediaType?.let { content?.get(it) as? JsonObject }

        val body = when {
            media == null -> ""
            !isJson(mediaType) -> {
                issues += ImportIssue("$location $key", "Body left empty — $mediaType is not JSON")
                ""
            }
            else -> encode(exampleOf(media) ?: schemaExample.generate(resolve(media["schema"])))
        }

        return RouteResponse(
            id = IdGenerator.generate("resp"),
            label = response.string("description")?.take(MAX_LABEL).orEmpty(),
            statusCode = status,
            headers = if (body.isEmpty()) emptyList() else listOf(HeaderPair("Content-Type", mediaType!!)),
            body = body,
        )
    }

    /** `#/components/...` references are resolved one level deep; chains do not show up in real files. */
    private fun resolve(element: JsonElement?): JsonElement? {
        val reference = ((element as? JsonObject)?.get("\$ref") as? JsonPrimitive)?.contentOrNull
            ?: return element
        if (!reference.startsWith("#/")) return null

        return reference.removePrefix("#/").split("/")
            .fold<String, JsonElement?>(root) { node, segment -> (node as? JsonObject)?.get(segment) }
    }

    private fun pickMediaType(content: JsonObject?): String? {
        val keys = content?.keys ?: return null
        return keys.firstOrNull { it.substringBefore(';').trim() == "application/json" }
            ?: keys.firstOrNull { it.substringBefore(';').trim().endsWith("+json") }
            ?: keys.firstOrNull()
    }

    private fun isJson(mediaType: String?): Boolean {
        val type = mediaType?.substringBefore(';')?.trim() ?: return false
        return type == "application/json" || type.endsWith("+json")
    }

    private fun encode(element: JsonElement): String =
        if (element is JsonNull) "" else PRETTY.encodeToString(JsonElement.serializer(), element)

    /**
     * The path part of `servers[0]` becomes the environment prefix: in a document that
     * says `https://api.x.com/v1`, routes must be served under `/v1`.
     */
    private fun readPrefix(): String {
        val url = ((root["servers"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("url") ?: return ""
        if (url.contains('{')) {
            issues += ImportIssue("servers", "Templated server URL was ignored: $url")
            return ""
        }

        val path = when {
            url.startsWith("http://") || url.startsWith("https://") ->
                url.substringAfter("://").substringAfter('/', missingDelimiterValue = "").let { if (it.isEmpty()) "" else "/$it" }
            url.startsWith("/") -> url
            else -> ""
        }
        return path.trimEnd('/')
    }

    /**
     * `/users/{userId}` → `/users/:userId`. The engine's path parameter syntax starts
     * with `:` (spec/behavior/matching.md); paths where only part of a segment is a
     * parameter (`/files/{id}.json`) are not supported.
     */
    private fun toRoutePath(path: String): String? {
        if (!path.startsWith("/")) return null

        val segments = path.split("/").map { segment ->
            when {
                !segment.contains('{') && !segment.contains('}') -> segment
                segment.startsWith("{") && segment.endsWith("}") && segment.count { it == '{' } == 1 ->
                    ":" + segment.substring(1, segment.length - 1)
                else -> return null
            }
        }
        return segments.joinToString("/")
    }

    private companion object {
        val METHODS = HttpMethod.entries.associateBy { it.name.lowercase() }
        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }
        const val MAX_LABEL = 60
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
