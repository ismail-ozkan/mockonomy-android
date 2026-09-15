package com.mockonomy.ui.screens.response

/**
 * The short description that accompanies the code on the status card. Not a full IANA
 * list — the codes people actually use when building mocks. An unknown code returns empty
 * text so the user can type any code; a gap here is not a bug.
 */
internal fun reasonPhraseOf(statusCode: Int): String = REASON_PHRASES[statusCode].orEmpty()

/** Codes picked with one tap in the status sheet, grouped by class. */
internal val COMMON_STATUS_CODES: List<Int> =
    listOf(200, 201, 202, 204, 301, 302, 304, 400, 401, 403, 404, 409, 422, 429, 500, 502, 503, 504)

private val REASON_PHRASES = mapOf(
    200 to "OK",
    201 to "Created",
    202 to "Accepted",
    204 to "No Content",
    301 to "Moved Permanently",
    302 to "Found",
    304 to "Not Modified",
    400 to "Bad Request",
    401 to "Unauthorized",
    403 to "Forbidden",
    404 to "Not Found",
    409 to "Conflict",
    418 to "I'm a teapot",
    422 to "Unprocessable Content",
    429 to "Too Many Requests",
    500 to "Internal Server Error",
    501 to "Not Implemented",
    502 to "Bad Gateway",
    503 to "Service Unavailable",
    504 to "Gateway Timeout",
)
