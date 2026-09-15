package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair

/**
 * Masking of sensitive headers on export.
 *
 * Being **on by default** is deliberate: if the user forgets to turn masking off,
 * they only share a masked file — harmless; if it were off and they forgot to turn
 * it on, they would leak a token. The default sits on the cheap side of a mistake.
 *
 * Only **header values** are masked; scanning bodies and user-defined patterns are
 * deliberately out of scope. Response headers produced from recorded real traffic
 * (such as `Set-Cookie`) or identity headers the user added by hand go through here.
 */

const val REDACTED_VALUE = "***REDACTED***"

private val SENSITIVE_HEADERS = setOf(
    "authorization",
    "cookie",
    "set-cookie",
    "proxy-authorization",
    "x-api-key",
    "x-auth-token",
)

private fun HeaderPair.isSensitive(): Boolean = key.lowercase() in SENSITIVE_HEADERS

private fun List<HeaderPair>.redacted(): List<HeaderPair> =
    map { if (it.isSensitive()) it.copy(value = REDACTED_VALUE) else it }

/**
 * Masks sensitive values in a header list — used to hide recorded traffic on the
 * admin API's read endpoints. **Same list, same logic** as export.
 */
fun redactSensitiveHeaders(headers: List<HeaderPair>): List<HeaderPair> = headers.redacted()

/** Replaces sensitive values in global and response headers with [REDACTED_VALUE]. */
fun redactSensitiveHeaders(environment: Environment): Environment = environment.copy(
    headers = environment.headers.redacted(),
    routes = environment.routes.map { route ->
        route.copy(responses = route.responses.map { it.copy(headers = it.headers.redacted()) })
    },
)

/** Whether there is at least one header to mask — used to inform the export dialog. */
fun hasSensitiveHeaders(environment: Environment): Boolean =
    environment.headers.any { it.isSensitive() } ||
        environment.routes.any { route -> route.responses.any { it.headers.any { h -> h.isSensitive() } } }
