package com.mockonomy.engine

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ResponseMode
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import com.mockonomy.engine.model.SequentialBehavior
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sequential responses and the `REQUEST_NUMBER` rule target.
 * Behavior contract: `spec/behavior/sequential-response.md`.
 */
class SequentialResponseTest {

    private val matcher = RequestMatcher()

    private fun response(status: Int, rules: List<Rule> = emptyList()) = RouteResponse(
        id = "resp-$status",
        statusCode = status,
        body = """{"n":$status}""",
        rules = rules,
    )

    private fun sequentialRoute(
        behavior: SequentialBehavior,
        responses: List<RouteResponse>,
    ) = Route(
        id = "route-seq",
        method = HttpMethod.GET,
        path = "/poll",
        responseMode = ResponseMode.SEQUENTIAL,
        sequentialBehavior = behavior,
        responses = responses,
    )

    private fun environmentOf(vararg routes: Route) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env", name = "Test", port = Environment.DEFAULT_PORT,
        routes = routes.toList(),
    )

    private fun get(path: String = "/poll") = MockRequest(method = "GET", path = path)

    /** Sends N requests and collects the returned status codes (with a shared counter). */
    private fun statusSequence(env: Environment, session: MockSession, times: Int, path: String = "/poll") =
        (1..times).map { matcher.serve(env, get(path), session = session).statusCode }

    @Test
    fun `STAY_ON_LAST stays on the last response`() {
        val env = environmentOf(
            sequentialRoute(
                SequentialBehavior.STAY_ON_LAST,
                listOf(response(201), response(202), response(200)),
            ),
        )
        // 5 requests to a route with 3 responses → 1,2,3,3,3
        assertEquals(listOf(201, 202, 200, 200, 200), statusSequence(env, MockSession(), times = 5))
    }

    @Test
    fun `CYCLE starts over`() {
        val env = environmentOf(
            sequentialRoute(
                SequentialBehavior.CYCLE,
                listOf(response(201), response(202), response(200)),
            ),
        )
        // → 1,2,3,1,2
        assertEquals(listOf(201, 202, 200, 201, 202), statusSequence(env, MockSession(), times = 5))
    }

    @Test
    fun `rules are ignored in SEQUENTIAL mode`() {
        // The first response has a rule that never passes; SEQUENTIAL must still serve
        // it on the first request (rules are not evaluated).
        val neverPasses = Rule(RuleTarget.QUERY, "nope", RuleOperator.EQUALS, "x")
        val env = environmentOf(
            sequentialRoute(
                SequentialBehavior.STAY_ON_LAST,
                listOf(response(201, rules = listOf(neverPasses)), response(202)),
            ),
        )
        assertEquals(listOf(201, 202), statusSequence(env, MockSession(), times = 2))
    }

    @Test
    fun `counter is reset after reset`() {
        val env = environmentOf(
            sequentialRoute(
                SequentialBehavior.STAY_ON_LAST,
                listOf(response(201), response(202), response(200)),
            ),
        )
        val session = MockSession()
        assertEquals(listOf(201, 202, 200), statusSequence(env, session, times = 3))
        session.reset()
        // Reset → starts from the beginning again
        assertEquals(listOf(201, 202), statusSequence(env, session, times = 2))
    }

    @Test
    fun `each route keeps its own counter`() {
        val env = environmentOf(
            sequentialRoute(
                SequentialBehavior.STAY_ON_LAST,
                listOf(response(201), response(202), response(200)),
            ),
            Route(
                id = "route-b", method = HttpMethod.GET, path = "/other",
                responseMode = ResponseMode.SEQUENTIAL,
                responses = listOf(response(301), response(302)),
            ),
        )
        val session = MockSession()
        // Even though /poll advances twice, /other starts from its own counter
        matcher.serve(env, get("/poll"), session = session)
        matcher.serve(env, get("/poll"), session = session)
        assertEquals(301, matcher.serve(env, get("/other"), session = session).statusCode)
    }

    // --- REQUEST_NUMBER rule target ---

    @Test
    fun `REQUEST_NUMBER EQUALS matches on the third request`() {
        val thirdOnly = response(
            418,
            rules = listOf(Rule(RuleTarget.REQUEST_NUMBER, "", RuleOperator.EQUALS, "3")),
        )
        val fallback = response(200).copy(isDefault = true)
        val env = environmentOf(
            Route(
                id = "route-rn", method = HttpMethod.GET, path = "/poll",
                responseMode = ResponseMode.RULES,
                responses = listOf(thirdOnly, fallback),
            ),
        )
        // 1,2 → fallback; 3 → rule matches; 4 → fallback again
        assertEquals(listOf(200, 200, 418, 200), statusSequence(env, MockSession(), times = 4))
    }

    @Test
    fun `REQUEST_NUMBER shows up as the read value in the rule trace`() {
        val rule = Rule(RuleTarget.REQUEST_NUMBER, "", RuleOperator.EQUALS, "2")
        val env = environmentOf(
            Route(
                id = "route-rn", method = HttpMethod.GET, path = "/poll",
                responseMode = ResponseMode.RULES,
                responses = listOf(response(418, rules = listOf(rule)), response(200).copy(isDefault = true)),
            ),
        )
        val session = MockSession()
        matcher.explain(env, get(), session = session) // request 1
        val trace = matcher.explain(env, get(), session = session).trace // request 2
        assertEquals(2, trace.requestNumber)
        assertEquals("2", trace.evaluatedRules.single().readValue)
    }
}
