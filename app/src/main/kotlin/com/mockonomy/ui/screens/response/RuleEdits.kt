package com.mockonomy.ui.screens.response

import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.RulesOperator

/**
 * Rule list edits — pure, Compose-free, testable.
 *
 * The same reason as `RouteEdits.kt`: decisions buried inside screen components are only
 * noticed on a device.
 */

/**
 * The default for a new rule: exact equality on a query parameter. Most rules are like
 * this; the user finds what they will type most often ready to go.
 */
internal fun RouteResponse.withRuleAdded(): RouteResponse =
    copy(rules = rules + Rule(target = RuleTarget.QUERY, operator = RuleOperator.EQUALS))

internal fun RouteResponse.withRuleAt(index: Int, transform: (Rule) -> Rule): RouteResponse =
    copy(rules = rules.mapIndexed { i, rule -> if (i == index) transform(rule) else rule })

internal fun RouteResponse.withRuleRemoved(index: Int): RouteResponse =
    copy(rules = rules.filterIndexed { i, _ -> i != index })

internal fun RouteResponse.withRulesOperator(operator: RulesOperator): RouteResponse =
    copy(rulesOperator = operator)

/**
 * Switching to `EXISTS` does **not delete** `value`, it only hides it.
 *
 * The user may change the operator by mistake and switch back; silently destroying the value
 * they typed is behavior rejected everywhere in this project (`spec/behavior/templating.md`,
 * "Unrecognized expressions"). The engine already ignores `value` for `EXISTS`, so keeping it
 * costs nothing.
 */
internal fun Rule.withOperator(operator: RuleOperator): Rule = copy(operator = operator)

/** `EXISTS` is a question of presence; it has no value to compare. */
internal val Rule.usesValue: Boolean
    get() = operator != RuleOperator.EXISTS

/**
 * For the `BODY` target the key **may be left empty**: then the whole body is read
 * (`spec/behavior/rules.md` §1). For other targets a rule without a key looks at nothing.
 */
internal val Rule.needsKey: Boolean
    get() = target != RuleTarget.BODY

internal fun RuleTarget.label(): String = when (this) {
    RuleTarget.QUERY -> "query"
    RuleTarget.HEADER -> "header"
    RuleTarget.BODY -> "body"
    RuleTarget.PATH_PARAM -> "path"
    RuleTarget.COOKIE -> "cookie"
    RuleTarget.REQUEST_NUMBER -> "request #"
}

internal fun RuleOperator.label(): String = when (this) {
    RuleOperator.EQUALS -> "equals"
    RuleOperator.CONTAINS -> "contains"
    RuleOperator.REGEX -> "regex"
    RuleOperator.EXISTS -> "exists"
}

/** The hint for the key field — it depends on the target, because what is expected differs. */
internal fun RuleTarget.keyHint(): String = when (this) {
    RuleTarget.QUERY -> "name"
    RuleTarget.HEADER -> "X-Header"
    RuleTarget.BODY -> "user.address.city"
    RuleTarget.PATH_PARAM -> "id"
    RuleTarget.COOKIE, RuleTarget.REQUEST_NUMBER -> ""
}

/** Targets the engine does not support are not offered in the editor. */
internal val SUPPORTED_TARGETS = listOf(
    RuleTarget.QUERY,
    RuleTarget.HEADER,
    RuleTarget.BODY,
    RuleTarget.PATH_PARAM,
)

internal val SUPPORTED_OPERATORS = listOf(
    RuleOperator.EQUALS,
    RuleOperator.CONTAINS,
    RuleOperator.REGEX,
    RuleOperator.EXISTS,
)
