package com.mockonomy.ui.screens.response

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.mockonomy.engine.RulePattern
import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.RulesOperator
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.components.dashedBorder
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing

/**
 * The rule editor.
 *
 * The component carrying the most information in the project: nested data (response →
 * rules → five fields per rule) shown at phone width. A rule is **not** kept on a single
 * line; it spans two lines (target + key on top, operator + value below). Horizontal
 * squeezing reads worse than vertical growth.
 */
@Composable
fun RulesSection(
    response: RouteResponse,
    onUpdate: ((RouteResponse) -> RouteResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            text = "Rules",
            modifier = Modifier.padding(bottom = Spacing.s2),
            trailing = {
                if (response.rules.size > 1) {
                    RulesOperatorToggle(
                        current = response.rulesOperator,
                        onSelect = { operator -> onUpdate { it.withRulesOperator(operator) } },
                    )
                }
            },
        )

        if (response.rules.isEmpty()) {
            NoRulesNotice(isDefault = response.isDefault)
        }

        response.rules.forEachIndexed { index, rule ->
            RuleCard(
                rule = rule,
                onChange = { transform -> onUpdate { it.withRuleAt(index, transform) } },
                onRemove = { onUpdate { it.withRuleRemoved(index) } },
                modifier = Modifier.padding(bottom = Spacing.s2),
            )
        }

        TextButton(onClick = { onUpdate { it.withRuleAdded() } }) {
            MockonomyIcon(
                icon = MockonomyIcons.Plus,
                contentDescription = null,
                tint = MockonomyTheme.colors.primaryText,
                size = 14.dp,
            )
            Text(
                text = "Add rule",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * Says **explicitly** when a response without rules is served.
 *
 * A response without rules does not take part in rule-based selection
 * (`spec/behavior/matching.md` §5), so a text like "This response is always served" would be
 * wrong — and a wrong explanation is worse than none.
 */
@Composable
private fun NoRulesNotice(isDefault: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .dashedBorder(MockonomyTheme.colors.outline, cornerRadius = 10.dp)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s4),
    ) {
        Text(
            text = if (isDefault) {
                "No rules. This response is served whenever no other response matches."
            } else {
                "No rules. This response is never served — add a rule, or make it the default."
            },
            style = MockonomyTheme.typography.body,
            color = if (isDefault) {
                MockonomyTheme.colors.onSurfaceVariant
            } else {
                MockonomyTheme.colors.warning
            },
        )
    }
}

/** A two-state picker. Not shown at all with a single rule — there is nothing to combine. */
@Composable
private fun RulesOperatorToggle(
    current: RulesOperator,
    onSelect: (RulesOperator) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .border(1.dp, MockonomyTheme.colors.outline, RoundedCornerShape(MockonomyMetrics.chipCornerRadius)),
    ) {
        RulesOperator.entries.forEach { operator ->
            val selected = operator == current
            Text(
                text = operator.name,
                style = MockonomyTheme.typography.monoMeta,
                color = if (selected) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier
                    .clickable { onSelect(operator) }
                    .background(
                        if (selected) MockonomyTheme.colors.primaryContainer else MockonomyTheme.colors.surface,
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: Rule,
    onChange: ((Rule) -> Rule) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val regexProblem = rule.operator == RuleOperator.REGEX && !RulePattern.isValidRegex(rule.value)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MockonomyTheme.colors.surfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, MockonomyTheme.colors.outlineSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EnumMenuChip(
                label = rule.target.label(),
                options = SUPPORTED_TARGETS,
                optionLabel = { it.label() },
                onSelect = { target -> onChange { it.copy(target = target) } },
            )

            RuleField(
                value = rule.key,
                onValueChange = { key -> onChange { it.copy(key = key) } },
                // For BODY an empty key points at the whole body; the hint should say so.
                hint = if (rule.needsKey) rule.target.keyHint() else "whole body",
                modifier = Modifier.weight(1f).padding(start = Spacing.s2),
            )

            InvertToggle(
                inverted = rule.invert,
                onToggle = { onChange { it.copy(invert = !it.invert) } },
            )

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(width = 34.dp, height = MockonomyMetrics.minTouchTarget),
            ) {
                MockonomyIcon(
                    icon = MockonomyIcons.X,
                    contentDescription = "Remove rule",
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                    size = 15.dp,
                )
            }
        }

        Row(
            modifier = Modifier.padding(top = Spacing.s1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnumMenuChip(
                label = rule.operator.label(),
                options = SUPPORTED_OPERATORS,
                optionLabel = { it.label() },
                onSelect = { operator -> onChange { it.withOperator(operator) } },
            )

            if (rule.usesValue) {
                RuleField(
                    value = rule.value,
                    onValueChange = { value -> onChange { it.copy(value = value) } },
                    hint = "value",
                    modifier = Modifier.weight(1f).padding(start = Spacing.s2),
                )
            } else {
                Text(
                    text = "no value needed",
                    style = MockonomyTheme.typography.caption,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.weight(1f).padding(start = Spacing.s3),
                )
            }
        }

        if (regexProblem) {
            RegexWarning(modifier = Modifier.padding(top = Spacing.s1))
        }
    }
}

/**
 * Warns, does not block: while the user types a pattern every intermediate state is invalid.
 * The engine fails this rule and does not apply `invert` (`spec/behavior/rules.md` §2) —
 * without the warning the user would silently get a wrong result.
 */
@Composable
private fun RegexWarning(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.Warning,
            contentDescription = null,
            tint = MockonomyTheme.colors.warning,
            size = 14.dp,
        )
        Text(
            text = "Not a valid regular expression — this rule never passes.",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.warning,
        )
    }
}

/** `≠` — inverts the rule's result. Colored when on, faint when off. */
@Composable
private fun InvertToggle(inverted: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 34.dp, height = 30.dp)
            .background(
                if (inverted) MockonomyTheme.colors.primaryContainer else MockonomyTheme.colors.surface,
                RoundedCornerShape(MockonomyMetrics.chipCornerRadius),
            )
            .border(
                1.dp,
                if (inverted) MockonomyTheme.colors.primaryOutline else MockonomyTheme.colors.outlineSubtle,
                RoundedCornerShape(MockonomyMetrics.chipCornerRadius),
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "≠",
            style = MockonomyTheme.typography.monoChip,
            color = if (inverted) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceFaint,
        )
    }
}

/**
 * A chip that carries a dropdown menu. A bottom sheet was not used: a sheet covering half the
 * screen for four options does not make choosing easier.
 */
@Composable
private fun <T> EnumMenuChip(
    label: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .defaultMinSize(minWidth = 78.dp, minHeight = 30.dp)
                .background(MockonomyTheme.colors.surface, RoundedCornerShape(MockonomyMetrics.chipCornerRadius))
                .border(1.dp, MockonomyTheme.colors.outline, RoundedCornerShape(MockonomyMetrics.chipCornerRadius))
                .clickable { expanded = true }
                .padding(horizontal = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // NOT `weight(1f)`: a weighted child pushes the chip to swallow all the free space
            // in the row and the field next to it shrinks to zero. It went unnoticed until seen
            // on a device — the key/value fields and the delete button had overflowed the screen.
            Text(
                text = label,
                style = MockonomyTheme.typography.monoChip,
                color = MockonomyTheme.colors.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(end = 6.dp),
            )
            MockonomyIcon(
                icon = MockonomyIcons.CaretDown,
                contentDescription = null,
                tint = MockonomyTheme.colors.onSurfaceFaint,
                size = 10.dp,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MockonomyTheme.colors.surface,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = optionLabel(option),
                            style = MockonomyTheme.typography.monoBody,
                            color = MockonomyTheme.colors.onSurface,
                        )
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A frameless single-line field with a hint — the rule card is framed already. */
@Composable
private fun RuleField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                text = hint,
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(MockonomyTheme.colors.primaryText),
            textStyle = MockonomyTheme.typography.monoBody.copy(color = MockonomyTheme.colors.onSurface),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@MockonomyThemePreview
@Composable
private fun RulesSectionPreview() {
    MockonomyTheme {
        Column(
            modifier = Modifier
                .background(MockonomyTheme.colors.background)
                .padding(Spacing.s4)
                .width(360.dp),
        ) {
            RulesSection(
                response = RouteResponse(
                    id = "resp-404",
                    statusCode = 404,
                    rules = listOf(
                        Rule(RuleTarget.QUERY, "simulate", RuleOperator.EQUALS, "missing"),
                        Rule(RuleTarget.HEADER, "X-Debug", RuleOperator.EXISTS, invert = true),
                        Rule(RuleTarget.BODY, "user.role", RuleOperator.REGEX, "(["),
                    ),
                ),
                onUpdate = {},
            )
        }
    }
}
