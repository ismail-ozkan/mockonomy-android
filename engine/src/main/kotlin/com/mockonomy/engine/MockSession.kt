package com.mockonomy.engine

/**
 * The **runtime state** of a running environment.
 *
 * Three parts share one lifecycle and always travel together:
 * - [counters] — per-route request counters
 * - [state] — the active state of the named state machine
 * - response overrides — a response pinned per route
 *
 * All three live in memory and are never written to the environment file. They are
 * reset (`spec/behavior/sequential-response.md`, `state-machine.md`,
 * `response-override.md`) when the server starts, when an environment is
 * activated, and on `POST /__mockonomy/reset`.
 *
 * This object is kept **outside** [RequestMatcher] and passed into each call (just
 * like `TemplateContext`) so the matcher stays stateless. The server owns it.
 *
 * State machines are used in sequential QA flows by nature; atomic state
 * transitions across concurrent requests are not a goal (keep it simple). Counter
 * access is still locked, and the state field is `@Volatile`.
 */
class MockSession(initialState: String? = null) {

    val counters: RequestCounters = RequestCounters()

    @Volatile
    var state: String? = initialState
        private set

    /**
     * Response pinned per route (`routeId → responseId`), filled by the admin API's
     * `PUT /routes/{key}/response` endpoint. Access is locked: the admin call
     * (write) and the served request (read) run on different threads
     * (`spec/behavior/response-override.md`).
     */
    private val overrides = mutableMapOf<String, String>()

    /** Changes the active state if [to] is not null (the response's `setState`). */
    fun transition(to: String?) {
        if (to != null) state = to
    }

    /** Pins a route to a specific response. */
    fun overrideResponse(routeId: String, responseId: String) {
        synchronized(overrides) { overrides[routeId] = responseId }
    }

    /** Removes a route's override; no-op if there is none. */
    fun clearOverride(routeId: String) {
        synchronized(overrides) { overrides.remove(routeId) }
    }

    /** The response id pinned for this route, or `null`. */
    fun overriddenResponse(routeId: String): String? =
        synchronized(overrides) { overrides[routeId] }

    /**
     * Resets counters, the active state and overrides; the state returns to
     * [initialState]. The foundation of test isolation.
     */
    fun reset(initialState: String? = null) {
        counters.reset()
        state = initialState
        synchronized(overrides) { overrides.clear() }
    }
}
