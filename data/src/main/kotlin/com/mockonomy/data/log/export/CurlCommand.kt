package com.mockonomy.data.log.export

import com.mockonomy.data.log.RequestLog

/**
 * Turns a log entry into a **runnable** `curl` command.
 *
 * One criterion: pasted into a terminal, the command must send the same request. So
 * quoting uses single quotes — inside single quotes the shell interprets nothing, and
 * `$`, backticks and backslashes pass through as they are. For the single quote
 * itself the standard shell trick is used: `'\''` (close the quote, escaped quote,
 * open again).
 */
object CurlCommand {

    /** When the host of the entry is unknown: the server is always on loopback. */
    private const val DEFAULT_HOST = "127.0.0.1"

    fun of(log: RequestLog): String {
        val parts = mutableListOf("curl")

        // GET is curl's default; spelling it out would only make the command longer.
        if (!log.method.equals("GET", ignoreCase = true)) {
            parts += "-X ${log.method}"
        }

        parts += quote(url(log))

        log.requestHeaders
            // curl computes the body length itself; passing it by hand would break the
            // command if the body was truncated.
            .filterNot { it.key.equals("Content-Length", ignoreCase = true) }
            .forEach { parts += "-H ${quote("${it.key}: ${it.value}")}" }

        // Unlike --data, --data-raw does not treat a body starting with `@` as a file
        // name and does not strip newlines.
        log.requestBody?.let { parts += "--data-raw ${quote(it)}" }

        return parts.joinToString(" \\\n  ")
    }

    /**
     * The log entry does not store a host — the request's `Host` header does. Without
     * that header (a raw socket client may not send one) loopback is assumed; even
     * without the port the command still goes to the right path.
     */
    private fun url(log: RequestLog): String {
        val host = log.requestHeaders
            .firstOrNull { it.key.equals("Host", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_HOST
        val query = if (log.query.isEmpty()) "" else "?${log.query}"
        return "http://$host${log.path}$query"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
