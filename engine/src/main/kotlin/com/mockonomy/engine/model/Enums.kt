package com.mockonomy.engine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The wire names of enum values are spelled out with @SerialName. These names are a
 * file-format contract (spec/schema/environment-v1.md) and must be produced the
 * same way on other platforms. Renaming a Kotlin constant must not break users'
 * files.
 *
 * Some values are defined by the schema but not supported by the engine yet; the
 * engine raises an explicit error when it meets them.
 */

@Serializable
enum class HttpMethod {
    @SerialName("GET") GET,
    @SerialName("POST") POST,
    @SerialName("PUT") PUT,
    @SerialName("PATCH") PATCH,
    @SerialName("DELETE") DELETE,
    @SerialName("HEAD") HEAD,
    @SerialName("OPTIONS") OPTIONS,
}

@Serializable
enum class ResponseMode {
    @SerialName("RULES") RULES,

    /** Responses are returned in list order (`spec/behavior/sequential-response.md`). */
    @SerialName("SEQUENTIAL") SEQUENTIAL,

    /** Not supported yet. */
    @SerialName("RANDOM") RANDOM,
}

/** What happens in SEQUENTIAL mode when the end of the list is reached. */
@Serializable
enum class SequentialBehavior {
    /** Stay on the last response (default — for polling scenarios). */
    @SerialName("STAY_ON_LAST") STAY_ON_LAST,

    /** Start over from the beginning. */
    @SerialName("CYCLE") CYCLE,
}

@Serializable
enum class BodyType {
    @SerialName("INLINE") INLINE,
    @SerialName("FILE") FILE,
}

@Serializable
enum class RuleTarget {
    @SerialName("QUERY") QUERY,
    @SerialName("HEADER") HEADER,
    @SerialName("BODY") BODY,
    @SerialName("PATH_PARAM") PATH_PARAM,

    /** Not supported yet. */
    @SerialName("COOKIE") COOKIE,

    /** The number of requests made to the route so far (1-based). */
    @SerialName("REQUEST_NUMBER") REQUEST_NUMBER,
}

@Serializable
enum class RuleOperator {
    @SerialName("EQUALS") EQUALS,
    @SerialName("CONTAINS") CONTAINS,
    @SerialName("REGEX") REGEX,
    @SerialName("EXISTS") EXISTS,
}

@Serializable
enum class RulesOperator {
    @SerialName("AND") AND,
    @SerialName("OR") OR,
}
