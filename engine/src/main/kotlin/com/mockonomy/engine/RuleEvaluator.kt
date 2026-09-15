package com.mockonomy.engine

import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import com.mockonomy.engine.model.RulesOperator
import com.mockonomy.engine.model.RouteResponse

/**
 * Evaluates a response's rules against the incoming request.
 *
 * Behavior contract: `spec/behavior/rules.md`. This object implements that
 * document; behavior changes are settled there first.
 */
internal object RuleEvaluator {

    /**
     * Whether the response is eligible for rule-based selection.
     *
     * **All** rules are evaluated, without short-circuiting: there are only ever a
     * few rules, and exiting early would record something different when a trace
     * is collected than when it is not.
     */
    fun passes(
        response: RouteResponse,
        request: MockRequest,
        pathParams: Map<String, String>,
        requestNumber: Int,
        trace: TraceBuilder?,
    ): Boolean {
        val results = response.rules.map { rule ->
            val outcome = evaluate(rule, request, pathParams, requestNumber)
            trace?.rule(response.id, rule, outcome)
            outcome.passed
        }
        return when (response.rulesOperator) {
            RulesOperator.AND -> results.all { it }
            RulesOperator.OR -> results.any { it }
        }
    }

    /** [readValue]: the value that decided — the passing value, otherwise the first one read. */
    data class Outcome(val passed: Boolean, val readValue: String?, val note: String? = null)

    fun evaluate(rule: Rule, request: MockRequest, pathParams: Map<String, String>, requestNumber: Int): Outcome {
        val values = readValues(rule, request, pathParams, requestNumber)

        if (rule.operator == RuleOperator.REGEX) {
            val regex = RulePattern.compile(rule.value)
                // An invalid pattern is a user error: the rule fails and `invert` is
                // not applied. Otherwise a typo would silently flip the rule into one
                // that *passes*.
                ?: return Outcome(false, values?.firstOrNull(), "invalid regex: ${rule.value}")
            return decide(values, rule) { regex.containsMatchIn(it) }
        }

        return decide(values, rule) { value ->
            when (rule.operator) {
                RuleOperator.EQUALS -> value == rule.value
                RuleOperator.CONTAINS -> value.contains(rule.value)
                RuleOperator.EXISTS -> true
                RuleOperator.REGEX -> error("handled above")
            }
        }
    }

    /** With multiple values one match is enough (`spec/behavior/rules.md` §1). */
    private fun decide(values: List<String>?, rule: Rule, satisfies: (String) -> Boolean): Outcome {
        val hit = values?.firstOrNull(satisfies)
        return Outcome(
            passed = if (rule.invert) hit == null else hit != null,
            readValue = hit ?: values?.firstOrNull(),
        )
    }

    /** `null` or an empty list: the target has **no** value — different from an empty string. */
    private fun readValues(
        rule: Rule,
        request: MockRequest,
        pathParams: Map<String, String>,
        requestNumber: Int,
    ): List<String>? {
        val values = when (rule.target) {
            RuleTarget.QUERY -> request.query[rule.key]
            RuleTarget.HEADER -> request.headers.entries
                .firstOrNull { it.key.equals(rule.key, ignoreCase = true) }
                ?.value
            RuleTarget.PATH_PARAM -> pathParams[rule.key]?.let(::listOf)
            // An empty key means the whole body — this is what CONTAINS needs for
            // plain-text bodies (spec/schema/environment-v1.md).
            RuleTarget.BODY -> if (rule.key.isEmpty()) {
                request.body?.let(::listOf)
            } else {
                JsonPath.read(request.body, rule.key)?.let(::listOf)
            }

            // Number of requests made to this route (1-based). `key` is ignored; the
            // value is the counter itself, compared with EQUALS/CONTAINS/REGEX
            // (spec/behavior/sequential-response.md). There is always a value.
            RuleTarget.REQUEST_NUMBER -> listOf(requestNumber.toString())

            RuleTarget.COOKIE -> throw UnsupportedMockFeatureException(
                "RuleTarget=${rule.target} is not supported yet (planned for phase 4).",
            )
        }
        return values?.takeIf { it.isNotEmpty() }
    }
}
