package com.mockonomy.data.log

import com.mockonomy.data.IdGenerator
import com.mockonomy.engine.MatchTrace
import com.mockonomy.engine.model.HeaderPair

/**
 * A single served request/response pair.
 *
 * **Logs stay on the device and are never sent anywhere.** This principle is the
 * basis of the Play data safety declaration; no path that sends this type over the
 * network may be added.
 *
 * [trace] is only filled while recording is on — producing a trace has a cost
 * (`spec/behavior/rules.md` §5). It backs the "why did this request get this
 * response?" explanation in the log detail screen.
 */
data class RequestLog(
    val environmentId: String,
    val timestampMs: Long,
    val method: String,
    val path: String,
    /** The raw query string, without `?`; empty if there is none. */
    val query: String,
    val requestHeaders: List<HeaderPair>,
    val requestBody: String?,
    val statusCode: Int,
    val responseHeaders: List<HeaderPair>,
    val responseBody: String,
    val durationMs: Long,
    val matchedRouteId: String?,
    val matchedResponseId: String?,
    val trace: MatchTrace?,
    val id: String = IdGenerator.generate("log"),
    val requestBodyTruncated: Boolean = false,
    val responseBodyTruncated: Boolean = false,
) {
    /**
     * Cuts the bodies down to [limit] characters and marks them as truncated.
     *
     * Truncation happens **in the store**, not in the server: the server's job is to
     * write the response; whether the record fits on disk is the store's problem. The
     * unit is characters — counting bytes would mean encoding the body again, and the
     * goal is to keep the DB from bloating, not an exact limit.
     */
    fun truncated(limit: Int): RequestLog = copy(
        requestBody = requestBody?.take(limit),
        requestBodyTruncated = (requestBody?.length ?: 0) > limit,
        responseBody = responseBody.take(limit),
        responseBodyTruncated = responseBody.length > limit,
    )

    companion object {
        /** 64 KB. */
        const val BODY_LIMIT: Int = 64 * 1024
    }
}

/**
 * The fields the log list needs.
 *
 * Reading full records for the list would pull tens of megabytes into memory for a
 * 1000-entry buffer; bodies are only needed on the detail screen.
 */
data class RequestLogSummary(
    val id: String,
    val timestampMs: Long,
    val method: String,
    val path: String,
    val query: String,
    val statusCode: Int,
    val durationMs: Long,
)
