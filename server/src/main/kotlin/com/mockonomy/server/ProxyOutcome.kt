package com.mockonomy.server

import com.mockonomy.engine.model.HeaderPair

/**
 * The loggable result of a proxy forward.
 *
 * [ProxyForwarder] has already written the response to `call`; this is a copy of the same
 * information so [MockServer] can record it in the request log. [responseBody] is only
 * filled for text content types — binary bodies are not logged ([bodyOmitted]).
 */
data class ProxyOutcome(
    val statusCode: Int,
    val responseHeaders: List<HeaderPair>,
    val responseBody: String,
    val bodyOmitted: Boolean,
)
