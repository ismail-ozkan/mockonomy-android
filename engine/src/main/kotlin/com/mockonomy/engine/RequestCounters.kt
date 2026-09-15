package com.mockonomy.engine

/**
 * Per-route request counter.
 *
 * Counters live **in memory** and are not persisted. `SEQUENTIAL` response
 * selection and the `REQUEST_NUMBER` rule target are fed by the same counter:
 * when a request matches a route, that route's counter goes up by one and the
 * returned value determines both the sequential index and the rule value.
 *
 * Counters are reset (`spec/behavior/sequential-response.md`) when the server
 * starts, when an environment is activated, and on `POST /__mockonomy/reset`.
 * Test isolation depends on this.
 *
 * The counters are kept **outside** [RequestMatcher] and passed into each call
 * (just like `TemplateContext`) so the matcher stays stateless. The server owns
 * them and manages their lifecycle. Access is locked because Ktor can handle
 * concurrent requests on different threads.
 */
class RequestCounters {

    private val lock = Any()
    private val counts = mutableMapOf<String, Int>()

    /** Increments the counter for [routeId] by 1 and returns the new (1-based) value. */
    fun next(routeId: String): Int = synchronized(lock) {
        val value = (counts[routeId] ?: 0) + 1
        counts[routeId] = value
        value
    }

    /** Resets all counters — the next request starts from 1 again. */
    fun reset() = synchronized(lock) {
        counts.clear()
    }
}
