package com.mockonomy.engine

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ResponseMode
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.SequentialBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Forced response override (through `MockSession`; the engine side of the admin
 * API's `PUT /routes/{key}/response` endpoint).
 * Behavior contract: `spec/behavior/response-override.md`.
 */
class ResponseOverrideTest {

    private val matcher = RequestMatcher()

    private fun response(id: String, status: Int, whenState: String? = null, setState: String? = null, isDefault: Boolean = false) =
        RouteResponse(id = id, statusCode = status, body = """{"n":$status}""", whenState = whenState, setState = setState, isDefault = isDefault)

    private fun route(
        id: String,
        responses: List<RouteResponse>,
        mode: ResponseMode = ResponseMode.RULES,
        behavior: SequentialBehavior = SequentialBehavior.STAY_ON_LAST,
    ) = Route(id = id, method = HttpMethod.GET, path = "/x", responses = responses, responseMode = mode, sequentialBehavior = behavior)

    private fun env(vararg routes: Route, states: List<String> = emptyList(), initial: String? = null) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env", name = "Test", port = Environment.DEFAULT_PORT,
        states = states, initialState = initial, routes = routes.toList(),
    )

    private fun req() = MockRequest(method = "GET", path = "/x")

    @Test
    fun `override beats the default selection`() {
        val environment = env(route("r", listOf(response("ok", 200, isDefault = true), response("nf", 404))))
        val session = MockSession()
        // Without an override: default (200)
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
        // Override "nf" → always 404 from now on
        session.overrideResponse("r", "nf")
        assertEquals(404, matcher.serve(environment, req(), session = session).statusCode)
        assertEquals(404, matcher.serve(environment, req(), session = session).statusCode)
    }

    @Test
    fun `override skips the SEQUENTIAL order`() {
        val environment = env(
            route(
                "r",
                listOf(response("a", 201), response("b", 202), response("c", 200)),
                mode = ResponseMode.SEQUENTIAL,
            ),
        )
        val session = MockSession()
        session.overrideResponse("r", "c")
        // Even as the counter advances, always the pinned "c" (200)
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
    }

    @Test
    fun `override skips the whenState filter`() {
        val environment = env(
            route(
                "r",
                listOf(
                    response("gated", 200, whenState = "b"),
                    response("open", 401, whenState = "a", isDefault = true),
                ),
            ),
            states = listOf("a", "b"), initial = "a",
        )
        val session = MockSession("a")
        // Normally the active state "a" → "open" (401). The override "gated" ignores the state.
        session.overrideResponse("r", "gated")
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
    }

    @Test
    fun `a pinned response still applies setState`() {
        val environment = env(
            route("r", listOf(response("go", 200, setState = "b", isDefault = true), response("stay", 204))),
            states = listOf("a", "b"), initial = "a",
        )
        val session = MockSession("a")
        session.overrideResponse("r", "go")
        matcher.serve(environment, req(), session = session)
        assertEquals("b", session.state)
    }

    @Test
    fun `an override pointing at a deleted response is ignored`() {
        // The overridden id is not on the route (e.g. deleted by hot reload) → fall back to normal selection
        val environment = env(route("r", listOf(response("ok", 200, isDefault = true))))
        val session = MockSession()
        session.overrideResponse("r", "gone")
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
    }

    @Test
    fun `reset clears the override`() {
        val environment = env(route("r", listOf(response("ok", 200, isDefault = true), response("nf", 404))))
        val session = MockSession()
        session.overrideResponse("r", "nf")
        assertEquals(404, matcher.serve(environment, req(), session = session).statusCode)
        session.reset()
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
    }

    @Test
    fun `clearOverride releases a single route`() {
        val environment = env(route("r", listOf(response("ok", 200, isDefault = true), response("nf", 404))))
        val session = MockSession()
        session.overrideResponse("r", "nf")
        session.clearOverride("r")
        assertEquals(200, matcher.serve(environment, req(), session = session).statusCode)
    }

    @Test
    fun `override shows up in the trace as forcedResponseId`() {
        val environment = env(route("r", listOf(response("ok", 200, isDefault = true), response("nf", 404))))
        val session = MockSession()
        session.overrideResponse("r", "nf")
        val trace = matcher.explain(environment, req(), session = session).trace
        assertEquals("nf", trace.forcedResponseId)
        assertEquals("nf", trace.matchedResponseId)
    }

    @Test
    fun `forcedResponseId is null without an override`() {
        val environment = env(route("r", listOf(response("ok", 200, isDefault = true))))
        val trace = matcher.explain(environment, req(), session = MockSession()).trace
        assertNull(trace.forcedResponseId)
    }
}
