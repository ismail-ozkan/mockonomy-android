package com.mockonomy.engine

import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Contract: `spec/behavior/rules.md`.
 *
 * Golden files cover every target × operator combination end to end; this class
 * measures edge cases and the information the trace carries.
 */
class RuleEvaluatorTest {

    private fun request(
        query: Map<String, List<String>> = emptyMap(),
        headers: Map<String, List<String>> = emptyMap(),
        body: String? = null,
    ) = MockRequest(method = "GET", path = "/", query = query, headers = headers, body = body)

    private fun evaluate(
        rule: Rule,
        request: MockRequest = request(),
        pathParams: Map<String, String> = emptyMap(),
        requestNumber: Int = 1,
    ) = RuleEvaluator.evaluate(rule, request, pathParams, requestNumber)

    // --- "missing" vs "empty" ---

    @Test
    fun `a parameter with an empty value exists`() {
        val request = request(query = mapOf("q" to listOf("")))

        assertTrue(evaluate(Rule(RuleTarget.QUERY, "q", RuleOperator.EXISTS), request).passed)
        assertTrue(evaluate(Rule(RuleTarget.QUERY, "q", RuleOperator.EQUALS, ""), request).passed)
    }

    @Test
    fun `a missing parameter passes no operator`() {
        listOf(
            Rule(RuleTarget.QUERY, "q", RuleOperator.EXISTS),
            Rule(RuleTarget.QUERY, "q", RuleOperator.EQUALS, ""),
            Rule(RuleTarget.QUERY, "q", RuleOperator.CONTAINS, ""),
            Rule(RuleTarget.QUERY, "q", RuleOperator.REGEX, ".*"),
        ).forEach { rule ->
            val outcome = evaluate(rule)
            assertFalse(outcome.passed, "should not have passed: $rule")
            assertNull(outcome.readValue, "should not have read a value: $rule")
        }
    }

    // --- multiple values ---

    @Test
    fun `one of multiple values is enough and the deciding value is written to the trace`() {
        val request = request(query = mapOf("tag" to listOf("a", "b")))
        val outcome = evaluate(Rule(RuleTarget.QUERY, "tag", RuleOperator.EQUALS, "b"), request)

        assertTrue(outcome.passed)
        assertEquals("b", outcome.readValue)
    }

    @Test
    fun `for a failing rule the first value read is written to the trace`() {
        val request = request(query = mapOf("tag" to listOf("a", "b")))
        val outcome = evaluate(Rule(RuleTarget.QUERY, "tag", RuleOperator.EQUALS, "z"), request)

        assertFalse(outcome.passed)
        assertEquals("a", outcome.readValue)
    }

    @Test
    fun `invert with multiple values passes when none of them matches`() {
        val request = request(query = mapOf("tag" to listOf("a", "b")))

        assertTrue(evaluate(Rule(RuleTarget.QUERY, "tag", RuleOperator.EQUALS, "z", invert = true), request).passed)
        assertFalse(evaluate(Rule(RuleTarget.QUERY, "tag", RuleOperator.EQUALS, "a", invert = true), request).passed)
    }

    // --- operators ---

    @Test
    fun `EQUALS and CONTAINS are case-sensitive`() {
        val request = request(headers = mapOf("X-Env" to listOf("Staging")))

        assertFalse(evaluate(Rule(RuleTarget.HEADER, "X-Env", RuleOperator.EQUALS, "staging"), request).passed)
        assertFalse(evaluate(Rule(RuleTarget.HEADER, "X-Env", RuleOperator.CONTAINS, "STAG"), request).passed)
        assertTrue(evaluate(Rule(RuleTarget.HEADER, "X-Env", RuleOperator.CONTAINS, "Stag"), request).passed)
    }

    @Test
    fun `REGEX matches partially, but anchors can require a full match`() {
        val request = request(query = mapOf("v" to listOf("v1.2.3")))

        assertTrue(evaluate(Rule(RuleTarget.QUERY, "v", RuleOperator.REGEX, "[0-9]+"), request).passed)
        assertFalse(evaluate(Rule(RuleTarget.QUERY, "v", RuleOperator.REGEX, "^[0-9]+$"), request).passed)
    }

    /**
     * Otherwise a broken pattern with `invert: true` would silently become a rule that
     * *passes* — a typo would invisibly flip the rule.
     */
    @Test
    fun `invalid regex fails the rule and invert is not applied`() {
        val request = request(query = mapOf("v" to listOf("x")))
        val outcome = evaluate(Rule(RuleTarget.QUERY, "v", RuleOperator.REGEX, "([", invert = true), request)

        assertFalse(outcome.passed)
        assertTrue(outcome.note!!.startsWith("invalid regex"))
    }

    // --- body ---

    @Test
    fun `dot notation silently does not match when the body is not JSON`() {
        val request = request(body = "plain text")
        assertFalse(evaluate(Rule(RuleTarget.BODY, "user.role", RuleOperator.EXISTS), request).passed)
    }

    @Test
    fun `an empty key points at the whole body`() {
        val request = request(body = "say hello world")
        assertTrue(evaluate(Rule(RuleTarget.BODY, "", RuleOperator.CONTAINS, "hello"), request).passed)
    }

    @Test
    fun `numbers and objects in the body are read in their JSON representation`() {
        val request = request(body = """{"user":{"age":36,"tags":["a"]}}""")

        assertEquals("36", evaluate(Rule(RuleTarget.BODY, "user.age", RuleOperator.EXISTS), request).readValue)
        assertEquals(
            """["a"]""",
            evaluate(Rule(RuleTarget.BODY, "user.tags", RuleOperator.EXISTS), request).readValue,
        )
    }

    // --- unsupported targets ---

    @Test
    fun `unsupported targets fail loudly instead of staying silent`() {
        assertFailsWith<UnsupportedMockFeatureException> {
            evaluate(Rule(RuleTarget.COOKIE, "session", RuleOperator.EXISTS))
        }
    }
}
