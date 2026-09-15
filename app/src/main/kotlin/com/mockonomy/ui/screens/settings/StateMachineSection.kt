package com.mockonomy.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.engine.model.Environment
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.components.dashedBorder
import com.mockonomy.ui.screens.detail.EnvironmentDetailViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * The state machine section.
 *
 * Named states are defined here: add / rename / delete and picking the initial state. It is
 * a behavioral, top-level feature like the proxy, so it is not hidden under "Advanced". The
 * responses' `whenState` / `setState` dropdowns are fed from this list
 * (`spec/behavior/state-machine.md`).
 *
 * Renaming and deleting update references together — see [StateEdits]. The **active** state
 * indicator and manual switching while running come separately (they need the runtime
 * channel).
 */
@Composable
fun StateMachineSection(
    viewModel: EnvironmentDetailViewModel,
    current: Environment,
    modifier: Modifier = Modifier,
) {
    val states = current.states
    val duplicate = states.groupingBy { it }.eachCount().any { it.value > 1 }
    val needsInitial = states.isNotEmpty() && current.initialState !in states
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val activeState by viewModel.activeState.collectAsStateWithLifecycle()

    var infoVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            text = "State machine",
            modifier = Modifier.padding(vertical = Spacing.s3),
            trailing = {
                IconButton(
                    onClick = { infoVisible = true },
                    modifier = Modifier.size(24.dp),
                ) {
                    MockonomyIcon(
                        icon = MockonomyIcons.Info,
                        contentDescription = "What is a state machine?",
                        tint = MockonomyTheme.colors.onSurfaceFaint,
                        size = 16.dp,
                    )
                }
            },
        )

        if (infoVisible) {
            StateMachineInfoDialog(onDismiss = { infoVisible = false })
        }

        // The live active state + manual switching while running — the control QA uses most,
        // so it sits at the top.
        if (isRunning && states.isNotEmpty()) {
            CurrentStateControl(
                states = states,
                activeState = activeState,
                onSelect = { viewModel.setActiveState(it) },
            )
        }

        if (states.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .dashedBorder(MockonomyTheme.colors.outline, cornerRadius = 10.dp)
                    .padding(Spacing.s4),
            ) {
                Text(
                    text = "No states. Add states to mock stateful flows — e.g. logged_out → logged_in.",
                    style = MockonomyTheme.typography.body,
                    color = MockonomyTheme.colors.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = "The initial state (selected) is active when the server starts. " +
                    "A response can require a state (whenState) and change it (setState).",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier.padding(bottom = Spacing.s2),
            )

            states.forEachIndexed { index, name ->
                StateRow(
                    name = name,
                    isInitial = name == current.initialState && name.isNotBlank(),
                    onSelectInitial = { viewModel.setInitialState(name) },
                    onRename = { viewModel.renameState(index, it) },
                    onDelete = { viewModel.deleteState(index) },
                )
            }

            if (needsInitial) {
                NoticeRow(
                    text = "Pick an initial state — otherwise no state is active at startup.",
                    color = MockonomyTheme.colors.warning,
                )
            }
            if (duplicate) {
                NoticeRow(
                    text = "Duplicate state names — whenState/setState can't tell them apart.",
                    color = MockonomyTheme.colors.warning,
                )
            }
        }

        TextButton(onClick = { viewModel.addState() }) {
            MockonomyIcon(
                icon = MockonomyIcons.Plus,
                contentDescription = null,
                tint = MockonomyTheme.colors.primaryText,
                size = 14.dp,
            )
            Text(
                text = "Add state",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * Shows the running server's active state and lets the user switch it by hand. Each state is
 * a chip; the active one is highlighted. Tapping changes the state immediately
 * (setActiveState).
 */
@Composable
private fun CurrentStateControl(
    states: List<String>,
    activeState: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.s3)
            .background(MockonomyTheme.colors.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(Spacing.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MockonomyIcon(
                icon = MockonomyIcons.Pulse,
                contentDescription = null,
                tint = MockonomyTheme.colors.primaryText,
                size = 14.dp,
            )
            Text(
                text = "Current state — tap to change",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            states.forEach { state ->
                val selected = state == activeState
                Text(
                    text = state,
                    style = MockonomyTheme.typography.monoChip,
                    color = if (selected) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier
                        .clickable { onSelect(state) }
                        .background(
                            if (selected) MockonomyTheme.colors.primaryContainer else MockonomyTheme.colors.surface,
                            RoundedCornerShape(MockonomyMetrics.chipCornerRadius),
                        )
                        .border(
                            1.dp,
                            if (selected) MockonomyTheme.colors.primaryOutline else MockonomyTheme.colors.outlineSubtle,
                            RoundedCornerShape(MockonomyMetrics.chipCornerRadius),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StateRow(
    name: String,
    isInitial: Boolean,
    onSelectInitial: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = isInitial,
            onClick = onSelectInitial,
            enabled = name.isNotBlank(),
        )
        OutlinedTextField(
            value = name,
            onValueChange = onRename,
            placeholder = { Text("state name") },
            singleLine = true,
            modifier = Modifier.weight(1f).padding(horizontal = Spacing.s2),
        )
        androidx.compose.material3.IconButton(
            onClick = onDelete,
            modifier = Modifier.size(MockonomyMetrics.minTouchTarget),
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.X,
                contentDescription = "Delete state $name",
                tint = MockonomyTheme.colors.onSurfaceFaint,
                size = 15.dp,
            )
        }
    }
}

/**
 * A short info popup: what it is for, an example, and where to set the states defined here
 * on the response detail screen — all on one screen, briefly.
 */
@Composable
private fun StateMachineInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "State machine",
                style = MockonomyTheme.typography.titleLarge,
                color = MockonomyTheme.colors.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = "Let the same endpoint return different responses based on a session flow.",
                    style = MockonomyTheme.typography.body,
                )
                Text(
                    text = "Example",
                    style = MockonomyTheme.typography.title,
                    color = MockonomyTheme.colors.onSurface,
                    modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s1),
                )
                Text(
                    text = "logged_out → POST /auth/login → logged_in\n" +
                        "GET /profile → 401 in logged_out, 200 in logged_in",
                    style = MockonomyTheme.typography.monoBody,
                )
                Text(
                    text = "How to wire it",
                    style = MockonomyTheme.typography.title,
                    color = MockonomyTheme.colors.onSurface,
                    modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s1),
                )
                Text(
                    text = "Open a response under Routes and use the STATE CONFIG block:",
                    style = MockonomyTheme.typography.body,
                )
                Text(
                    text = "• Only serve in state — response is a candidate only in this state\n" +
                        "• Change state after serving — active state switches to this after the response is served",
                    style = MockonomyTheme.typography.body,
                    modifier = Modifier.padding(top = Spacing.s1),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}

@Composable
private fun NoticeRow(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.s1, bottom = Spacing.s1),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.Warning,
            contentDescription = null,
            tint = color,
            size = 14.dp,
        )
        Text(text = text, style = MockonomyTheme.typography.caption, color = color)
    }
}
