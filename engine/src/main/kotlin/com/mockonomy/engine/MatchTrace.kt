package com.mockonomy.engine

import com.mockonomy.engine.model.Rule
import kotlinx.serialization.Serializable

/**
 * The reasoning behind the engine's decision: which routes were tried, why they
 * were skipped, which rules were evaluated and what the outcome was.
 *
 * The most common question in a mock tool is "why did I get this response?".
 * Answering it inside the product (the log detail screen) beats answering it in
 * documentation. Adding it later would have meant reopening every decision point
 * in the engine, so it was designed together with rules.
 *
 * The trace **never changes the decision, it only records it.** Collecting it has
 * a cost, so it is optional (`spec/behavior/rules.md` §5).
 *
 * `@Serializable` so the trace can be stored on the device with its log entry. It
 * is **not** part of the exported environment schema —
 * `spec/schema/environment-v1.md` is unaffected.
 */
@Serializable
data class MatchTrace(
    /** The path after the prefix was stripped; `null` if the prefix did not match. */
    val effectivePath: String?,
    val attemptedRoutes: List<RouteAttempt> = emptyList(),
    val matchedRouteId: String? = null,
    /** Number of requests made to this route (1-based); `null` if no route matched. */
    val requestNumber: Int? = null,
    /** The state that was active when the request arrived; `null` without a state machine. */
    val activeState: String? = null,
    /** Responses skipped because their `whenState` did not match the current state. */
    val stateSkips: List<StateSkip> = emptyList(),
    val evaluatedRules: List<RuleEvaluation> = emptyList(),
    /** List index of the selected response in SEQUENTIAL mode; `null` in other modes. */
    val sequentialIndex: Int? = null,
    val matchedResponseId: String? = null,
    /** Whether the response was selected by rules or by falling back to the default. */
    val fallbackUsed: Boolean = false,
    /** Id of a response pinned (forced) through the admin API, if one was served; otherwise `null`. */
    val forcedResponseId: String? = null,
    /** The new state if the served response applied a `setState`; otherwise `null`. */
    val appliedSetState: String? = null,
)

/**
 * A response skipped because of a state mismatch. The state-machine side of "why
 * did I get this response?": `unauthorized` was skipped because it has
 * `whenState=logged_out` but the active state was `logged_in`.
 */
@Serializable
data class StateSkip(
    val responseId: String,
    /** The state the response requires (`whenState`). */
    val requiredState: String?,
    /** The active state at the time of the request. */
    val activeState: String?,
)

@Serializable
data class RouteAttempt(
    val routeId: String,
    val method: String,
    val path: String,
    /** `null` means this route matched. */
    val skipReason: SkipReason?,
)

enum class SkipReason {
    DISABLED,
    METHOD_MISMATCH,
    PATH_MISMATCH,

    /** Matched, but another candidate was more specific (`/users/me` > `/users/:id`). */
    LESS_SPECIFIC,
}

/**
 * The evaluation of a single rule. A `null` [readValue] means the target had no
 * value — this is what lets the user tell "header missing" from "header empty".
 */
@Serializable
data class RuleEvaluation(
    val responseId: String,
    val rule: Rule,
    val readValue: String?,
    val passed: Boolean,
    /** Only set when something needs explaining, e.g. an invalid regex. */
    val note: String? = null,
)
