package com.mockonomy.engine

import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.Rule

/**
 * Collects a [MatchTrace] step by step.
 *
 * Mutable state is deliberately confined here: [RequestMatcher] and
 * [RuleEvaluator] stay stateless, and when no trace is requested (`null` is
 * passed) not a single object is allocated.
 */
internal class TraceBuilder {

    private val attempts = mutableListOf<RouteAttempt>()
    private val rules = mutableListOf<RuleEvaluation>()
    private val stateSkips = mutableListOf<StateSkip>()
    private var effectivePath: String? = null
    private var matchedRouteId: String? = null
    private var requestNumber: Int? = null
    private var activeState: String? = null
    private var sequentialIndex: Int? = null
    private var matchedResponseId: String? = null
    private var fallbackUsed: Boolean = false
    private var forcedResponseId: String? = null
    private var appliedSetState: String? = null

    fun effectivePath(path: String?) {
        effectivePath = path
    }

    fun route(route: Route, skipReason: SkipReason?) {
        attempts += RouteAttempt(route.id, route.method.name, route.path, skipReason)
    }

    /**
     * Called once route selection is done: matching candidates that did not win
     * are marked "less specific". This is where users get stuck most often — why
     * `/users/:id` does not fire while `/users/me` exists.
     */
    fun routesResolved(winnerId: String?) {
        matchedRouteId = winnerId
        for (index in attempts.indices) {
            val attempt = attempts[index]
            if (attempt.skipReason == null && attempt.routeId != winnerId) {
                attempts[index] = attempt.copy(skipReason = SkipReason.LESS_SPECIFIC)
            }
        }
    }

    fun requestNumber(value: Int) {
        requestNumber = value
    }

    fun sequentialIndex(index: Int) {
        sequentialIndex = index
    }

    fun activeState(state: String?) {
        activeState = state
    }

    fun stateSkip(responseId: String, requiredState: String?, activeState: String?) {
        stateSkips += StateSkip(responseId, requiredState, activeState)
    }

    fun appliedSetState(state: String) {
        appliedSetState = state
    }

    fun rule(responseId: String, rule: Rule, outcome: RuleEvaluator.Outcome) {
        rules += RuleEvaluation(responseId, rule, outcome.readValue, outcome.passed, outcome.note)
    }

    fun response(responseId: String?, fallback: Boolean) {
        matchedResponseId = responseId
        fallbackUsed = fallback
    }

    /**
     * A forced override was applied: the pinned response becomes both
     * [forcedResponseId] and the served [matchedResponseId] (rule/sequential
     * selection never ran, so the latter has to be set here).
     */
    fun forcedResponse(responseId: String) {
        forcedResponseId = responseId
        matchedResponseId = responseId
        fallbackUsed = false
    }

    fun build(): MatchTrace = MatchTrace(
        effectivePath = effectivePath,
        attemptedRoutes = attempts.toList(),
        matchedRouteId = matchedRouteId,
        requestNumber = requestNumber,
        activeState = activeState,
        stateSkips = stateSkips.toList(),
        evaluatedRules = rules.toList(),
        sequentialIndex = sequentialIndex,
        matchedResponseId = matchedResponseId,
        fallbackUsed = fallbackUsed,
        forcedResponseId = forcedResponseId,
        appliedSetState = appliedSetState,
    )
}
