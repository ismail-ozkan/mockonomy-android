package com.mockonomy.engine

import com.mockonomy.engine.model.HeaderPair

/*
 * The contract between :engine and the platform layer.
 *
 * These two types are the most important boundary in the architecture: :server
 * turns Ktor's ApplicationCall into a MockRequest, calls the engine and writes the
 * returned MockResponse. That way :engine knows nothing about Ktor, Android or any
 * other platform concept.
 *
 * No platform-specific type may enter this file.
 */

/**
 * Platform-independent representation of an incoming HTTP request.
 *
 * [method] is deliberately a `String`, not a [com.mockonomy.engine.model.HttpMethod]:
 * a method the schema does not define can arrive over the wire. Such a request
 * does not match and gets a 404; crashing the engine because of it would be wrong.
 */
data class MockRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>> = emptyMap(),
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String? = null,
)

/**
 * Platform-independent representation of the response to serve.
 *
 * [latencyMs] is only the *computed* value here; :server does the waiting (the
 * engine stays pure and synchronous, which keeps tests instant).
 */
data class MockResponse(
    val statusCode: Int,
    val headers: List<HeaderPair>,
    val body: String,
    val latencyMs: Int,
)

/** A feature that the schema defines but the engine does not support yet was used. */
class UnsupportedMockFeatureException(message: String) : IllegalStateException(message)

/**
 * The full output of [RequestMatcher.match] — besides the response it carries the
 * captured path parameters.
 *
 * [pathParams] feed the template engine (`{{path.id}}`) and let tests verify that
 * matching works (`expectedPathParams` in spec/fixtures). They are not part of
 * [MockRequest] because path parameters belong to *the result of matching*, not to
 * the request itself.
 */
data class MatchResult(
    val response: MockResponse,
    val pathParams: Map<String, String>,
)

/**
 * The output of [RequestMatcher.explain]: the served response and the reasoning.
 *
 * When nothing matched, [response] is the synthetic 404 while [trace] is still
 * filled — the answer to "why did I get a 404?" is in the record of attempted routes.
 */
data class TracedResponse(
    val response: MockResponse,
    val trace: MatchTrace,
)
