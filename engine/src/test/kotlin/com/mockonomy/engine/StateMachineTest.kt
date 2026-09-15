package com.mockonomy.engine

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The named state machine (`whenState` / `setState`).
 * Behavior contract: `spec/behavior/state-machine.md`.
 */
class StateMachineTest {

    private val matcher = RequestMatcher()

    private fun response(
        id: String,
        status: Int,
        whenState: String? = null,
        setState: String? = null,
        isDefault: Boolean = false,
        rules: List<Rule> = emptyList(),
    ) = RouteResponse(
        id = id,
        statusCode = status,
        body = """{"n":$status}""",
        whenState = whenState,
        setState = setState,
        isDefault = isDefault,
        rules = rules,
    )

    private fun env(states: List<String>, initial: String?, vararg routes: Route) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env", name = "Test", port = Environment.DEFAULT_PORT,
        states = states, initialState = initial,
        routes = routes.toList(),
    )

    private fun route(id: String, method: HttpMethod, path: String, responses: List<RouteResponse>) =
        Route(id = id, method = method, path = path, responses = responses)

    private fun req(method: HttpMethod, path: String) = MockRequest(method = method.name, path = path)

    @Test
    fun `a response whose whenState does not match the current state is dropped`() {
        val environment = env(
            listOf("logged_out", "logged_in"), "logged_out",
            route(
                "profile", HttpMethod.GET, "/profile",
                listOf(
                    response("unauth", 401, whenState = "logged_out", isDefault = true),
                    response("profile", 200, whenState = "logged_in"),
                ),
            ),
        )
        // Initial logged_out → 401
        val session = MockSession(environment.initialState)
        assertEquals(401, matcher.serve(environment, req(HttpMethod.GET, "/profile"), session = session).statusCode)
    }

    @Test
    fun `setState changes the active state after serving`() {
        val environment = env(
            listOf("logged_out", "logged_in"), "logged_out",
            route(
                "login", HttpMethod.POST, "/auth/login",
                listOf(response("ok", 200, whenState = "logged_out", setState = "logged_in", isDefault = true)),
            ),
        )
        val session = MockSession(environment.initialState)
        assertEquals("logged_out", session.state)
        matcher.serve(environment, req(HttpMethod.POST, "/auth/login"), session = session)
        assertEquals("logged_in", session.state)
    }

    @Test
    fun `full session flow login profile logout`() {
        val environment = env(
            listOf("logged_out", "logged_in"), "logged_out",
            route(
                "login", HttpMethod.POST, "/auth/login",
                listOf(response("ok", 200, whenState = "logged_out", setState = "logged_in", isDefault = true)),
            ),
            route(
                "profile", HttpMethod.GET, "/profile",
                listOf(
                    response("unauth", 401, whenState = "logged_out", isDefault = true),
                    response("me", 200, whenState = "logged_in"),
                ),
            ),
            route(
                "logout", HttpMethod.POST, "/auth/logout",
                listOf(response("bye", 204, whenState = "logged_in", setState = "logged_out", isDefault = true)),
            ),
        )
        val session = MockSession(environment.initialState)

        // logged_out: profile 401
        assertEquals(401, matcher.serve(environment, req(HttpMethod.GET, "/profile"), session = session).statusCode)
        // login → logged_in
        assertEquals(200, matcher.serve(environment, req(HttpMethod.POST, "/auth/login"), session = session).statusCode)
        // logged_in: profile 200
        assertEquals(200, matcher.serve(environment, req(HttpMethod.GET, "/profile"), session = session).statusCode)
        // logout → logged_out
        assertEquals(204, matcher.serve(environment, req(HttpMethod.POST, "/auth/logout"), session = session).statusCode)
        // logged_out again: profile 401
        assertEquals(401, matcher.serve(environment, req(HttpMethod.GET, "/profile"), session = session).statusCode)
        assertEquals("logged_out", session.state)
    }

    @Test
    fun `404 when the only response requires another state`() {
        val environment = env(
            listOf("a", "b"), "a",
            route("only", HttpMethod.GET, "/x", listOf(response("b-only", 200, whenState = "b"))),
        )
        // Active state is a; the only response requires b → no eligible candidate → 404
        val session = MockSession(environment.initialState)
        assertEquals(404, matcher.serve(environment, req(HttpMethod.GET, "/x"), session = session).statusCode)
    }

    @Test
    fun `whenState filters before rules, so the dropped rule is not evaluated`() {
        // While logged_in is active, the response that requires logged_out has a rule;
        // since the state does not match, the rule must never be evaluated (the trace
        // shows a state skip, not a rule).
        val environment = env(
            listOf("logged_out", "logged_in"), "logged_in",
            route(
                "x", HttpMethod.GET, "/x",
                listOf(
                    response(
                        "gated", 401, whenState = "logged_out",
                        rules = listOf(Rule(RuleTarget.QUERY, "q", RuleOperator.EQUALS, "1")),
                    ),
                    response("open", 200, whenState = "logged_in", isDefault = true),
                ),
            ),
        )
        val traced = matcher.explain(environment, req(HttpMethod.GET, "/x"), session = MockSession("logged_in"))

        assertEquals("logged_in", traced.trace.activeState)
        assertEquals(200, traced.response.statusCode)
        // "gated" was skipped because of the state; its rule was never evaluated
        assertTrue(traced.trace.stateSkips.any { it.responseId == "gated" && it.requiredState == "logged_out" })
        assertTrue(traced.trace.evaluatedRules.isEmpty())
    }

    @Test
    fun `a response with empty whenState is eligible in every state`() {
        val environment = env(
            listOf("a", "b"), "a",
            route("x", HttpMethod.GET, "/x", listOf(response("any", 200, isDefault = true))),
        )
        assertEquals(200, matcher.serve(environment, req(HttpMethod.GET, "/x"), session = MockSession("a")).statusCode)
        assertEquals(200, matcher.serve(environment, req(HttpMethod.GET, "/x"), session = MockSession("b")).statusCode)
    }

    @Test
    fun `setState appears in the trace`() {
        val environment = env(
            listOf("a", "b"), "a",
            route("x", HttpMethod.GET, "/x", listOf(response("go", 200, setState = "b", isDefault = true))),
        )
        val trace = matcher.explain(environment, req(HttpMethod.GET, "/x"), session = MockSession("a")).trace
        assertEquals("b", trace.appliedSetState)
    }

    @Test
    fun `an environment without states works as before`() {
        val environment = Environment(
            schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
            id = "env", name = "Test", port = Environment.DEFAULT_PORT,
            routes = listOf(route("x", HttpMethod.GET, "/x", listOf(response("ok", 200, isDefault = true)))),
        )
        val session = MockSession()
        assertNull(session.state)
        assertEquals(200, matcher.serve(environment, req(HttpMethod.GET, "/x"), session = session).statusCode)
    }
}
