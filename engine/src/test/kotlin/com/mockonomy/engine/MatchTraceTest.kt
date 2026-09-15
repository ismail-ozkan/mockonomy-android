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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract: `spec/behavior/rules.md` §5.
 *
 * The trace's only job is to **record** the decision; this class also tests that it
 * does not change the decision — the log detail screen's "why did this request get
 * this response?" view is built on this data.
 */
class MatchTraceTest {

    private val matcher = RequestMatcher()

    private fun environment(vararg routes: Route, prefix: String = "") = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-a",
        name = "A",
        port = Environment.DEFAULT_PORT,
        prefix = prefix,
        routes = routes.toList(),
    )

    private fun route(
        id: String,
        path: String,
        method: HttpMethod = HttpMethod.GET,
        enabled: Boolean = true,
        responses: List<RouteResponse> = listOf(RouteResponse(id = "$id-resp", statusCode = 200, isDefault = true)),
    ) = Route(id = id, method = method, path = path, enabled = enabled, responses = responses)

    @Test
    fun `the trace does not change the decision`() {
        val environment = environment(route("r1", "/users/:id"))
        val request = MockRequest(method = "GET", path = "/users/7")

        assertEquals(matcher.serve(environment, request), matcher.explain(environment, request).response)
    }

    @Test
    fun `skip reasons are recorded`() {
        val environment = environment(
            route("disabled", "/ping", enabled = false),
            route("wrong-method", "/ping", method = HttpMethod.POST),
            route("wrong-path", "/pong"),
            route("hit", "/ping"),
        )

        val trace = matcher.explain(environment, MockRequest(method = "GET", path = "/ping")).trace

        assertEquals("hit", trace.matchedRouteId)
        assertEquals(
            mapOf(
                "disabled" to SkipReason.DISABLED,
                "wrong-method" to SkipReason.METHOD_MISMATCH,
                "wrong-path" to SkipReason.PATH_MISMATCH,
                "hit" to null,
            ),
            trace.attemptedRoutes.associate { it.routeId to it.skipReason },
        )
    }

    /** Where users get stuck most often: why `/users/:id` does not fire while `/users/me` exists. */
    @Test
    fun `the losing candidate is marked less specific`() {
        val environment = environment(route("param", "/users/:id"), route("static", "/users/me"))

        val trace = matcher.explain(environment, MockRequest(method = "GET", path = "/users/me")).trace

        assertEquals("static", trace.matchedRouteId)
        assertEquals(
            SkipReason.LESS_SPECIFIC,
            trace.attemptedRoutes.first { it.routeId == "param" }.skipReason,
        )
    }

    @Test
    fun `no effective path when the prefix does not match`() {
        val environment = environment(route("r1", "/users"), prefix = "api/v1")

        val trace = matcher.explain(environment, MockRequest(method = "GET", path = "/users")).trace

        assertNull(trace.effectivePath)
        assertTrue(trace.attemptedRoutes.isEmpty())
        assertNull(trace.matchedRouteId)
    }

    @Test
    fun `attempted routes are recorded even without a match`() {
        val environment = environment(route("r1", "/ping"))

        val traced = matcher.explain(environment, MockRequest(method = "GET", path = "/nope"))

        assertEquals(404, traced.response.statusCode)
        assertEquals(SkipReason.PATH_MISMATCH, traced.trace.attemptedRoutes.single().skipReason)
    }

    @Test
    fun `evaluated rules are recorded with the value read`() {
        val environment = environment(
            route(
                "r1", "/ping",
                responses = listOf(
                    RouteResponse(
                        id = "resp-404",
                        statusCode = 404,
                        rules = listOf(
                            Rule(RuleTarget.QUERY, "simulate", RuleOperator.EQUALS, "missing"),
                            Rule(RuleTarget.HEADER, "X-Debug", RuleOperator.EXISTS),
                        ),
                    ),
                    RouteResponse(id = "resp-200", statusCode = 200, isDefault = true),
                ),
            ),
        )
        val request = MockRequest(
            method = "GET",
            path = "/ping",
            query = mapOf("simulate" to listOf("missing")),
        )

        val trace = matcher.explain(environment, request).trace

        // AND: the second rule failed, so the response was dropped and selection fell back to the default.
        assertEquals("resp-200", trace.matchedResponseId)
        assertTrue(trace.fallbackUsed)
        assertEquals(2, trace.evaluatedRules.size)
        assertEquals(listOf(true, false), trace.evaluatedRules.map { it.passed })
        assertEquals(listOf("missing", null), trace.evaluatedRules.map { it.readValue })
        assertTrue(trace.evaluatedRules.all { it.responseId == "resp-404" })
    }

    @Test
    fun `a response selected by rules is not marked as a fallback`() {
        val environment = environment(
            route(
                "r1", "/ping",
                responses = listOf(
                    RouteResponse(
                        id = "resp-404",
                        statusCode = 404,
                        rules = listOf(Rule(RuleTarget.QUERY, "simulate", RuleOperator.EXISTS)),
                    ),
                    RouteResponse(id = "resp-200", statusCode = 200, isDefault = true),
                ),
            ),
        )
        val request = MockRequest(method = "GET", path = "/ping", query = mapOf("simulate" to listOf("x")))

        val trace = matcher.explain(environment, request).trace

        assertEquals("resp-404", trace.matchedResponseId)
        assertFalse(trace.fallbackUsed)
    }
}
