package com.mockonomy.ui.screens.response

import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.RulesOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure logic of rule edits. Buried in a screen component it would only be noticed on a
 * device.
 */
class RuleEditsTest {

    private fun response(vararg rules: Rule, isDefault: Boolean = false) = RouteResponse(
        id = "resp-1",
        statusCode = 200,
        isDefault = isDefault,
        rules = rules.toList(),
    )

    private fun rule(
        target: RuleTarget = RuleTarget.QUERY,
        key: String = "q",
        operator: RuleOperator = RuleOperator.EQUALS,
        value: String = "x",
        invert: Boolean = false,
    ) = Rule(target, key, operator, value, invert)

    @Test
    fun `a new rule comes with query equality`() {
        val added = response().withRuleAdded().rules.single()

        assertEquals(RuleTarget.QUERY, added.target)
        assertEquals(RuleOperator.EQUALS, added.operator)
        assertEquals("", added.key)
        assertEquals("", added.value)
    }

    @Test
    fun `a rule changes only at its own position`() {
        val updated = response(rule(key = "a"), rule(key = "b"))
            .withRuleAt(1) { it.copy(key = "changed") }

        assertEquals(listOf("a", "changed"), updated.rules.map { it.key })
    }

    @Test
    fun `deleting a rule keeps the order of the rest`() {
        val updated = response(rule(key = "a"), rule(key = "b"), rule(key = "c"))
            .withRuleRemoved(1)

        assertEquals(listOf("a", "c"), updated.rules.map { it.key })
    }

    @Test
    fun `the combining operator can be changed`() {
        assertEquals(RulesOperator.OR, response().withRulesOperator(RulesOperator.OR).rulesOperator)
    }

    /**
     * The user may change the operator by mistake and switch back; silently destroying the
     * value they typed is behavior rejected everywhere in this project.
     */
    @Test
    fun `switching to EXISTS does not delete the value, only hides it`() {
        val existing = rule(value = "important")

        val switched = existing.withOperator(RuleOperator.EXISTS)
        assertEquals("important", switched.value)
        assertFalse(switched.usesValue)

        val back = switched.withOperator(RuleOperator.CONTAINS)
        assertEquals("important", back.value)
        assertTrue(back.usesValue)
    }

    @Test
    fun `targets other than BODY require a key`() {
        assertTrue(rule(target = RuleTarget.QUERY).needsKey)
        assertTrue(rule(target = RuleTarget.HEADER).needsKey)
        assertTrue(rule(target = RuleTarget.PATH_PARAM).needsKey)
        // An empty key points at the whole body — an option, not a gap.
        assertFalse(rule(target = RuleTarget.BODY).needsKey)
    }

    @Test
    fun `unsupported targets and operators are not offered in the editor`() {
        assertFalse(RuleTarget.COOKIE in SUPPORTED_TARGETS)
        assertFalse(RuleTarget.REQUEST_NUMBER in SUPPORTED_TARGETS)
        assertEquals(RuleOperator.entries.size, SUPPORTED_OPERATORS.size)
    }
}
