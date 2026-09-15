package com.mockonomy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.engine.RoutePath
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.ResponseMode
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.engine.model.SequentialBehavior
import com.mockonomy.ui.components.MethodChip
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.screens.routes.RouteEditViewModel
import com.mockonomy.ui.screens.routes.pathProblemMessage
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * Route editing.
 *
 * Response *contents* are not edited here; there is a list and adding, while editing
 * belongs to the response body editor.
 *
 * The "Save" button from the design is missing on purpose: this app has no save moment —
 * every edit is written to disk immediately and reflected in the running server (hot
 * reload). Not having a button is better than one that implies a step that does not exist.
 */
@Composable
fun RouteEditScreen(
    environmentId: String,
    routeId: String,
    onBack: () -> Unit,
    onResponseClick: (responseId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouteEditViewModel = hiltViewModel(),
) {
    val route by viewModel.route.collectAsStateWithLifecycle()
    val hasConflict by viewModel.hasConflict.collectAsStateWithLifecycle()
    val servedOrigin by viewModel.servedOrigin.collectAsStateWithLifecycle()
    val current = route

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                MockonomyIcon(
                    icon = MockonomyIcons.ArrowLeft,
                    contentDescription = "Back",
                    tint = MockonomyTheme.colors.onSurface,
                )
            }
            Text(
                text = "Route",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        if (current == null) return@Column

        val pathProblem = RoutePath.validate(current.path)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(top = Spacing.s3, bottom = Spacing.s5),
        ) {
            SectionHeader(text = "Request", modifier = Modifier.padding(bottom = 9.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                for (candidate in HttpMethod.entries) {
                    MethodChip(
                        method = candidate,
                        modifier = Modifier
                            .clickable { viewModel.update { it.copy(method = candidate) } }
                            .alpha(if (candidate == current.method) 1f else UNSELECTED_ALPHA),
                    )
                }
            }

            OutlinedTextField(
                value = current.path,
                onValueChange = { text -> viewModel.update { it.copy(path = text) } },
                label = { Text("Path") },
                singleLine = true,
                isError = pathProblem != null,
                supportingText = { Text(pathProblem?.let(::pathProblemMessage) ?: "e.g. /users/:id") },
                modifier = Modifier.fillMaxWidth(),
            )

            // The full address with prefix and port — removes any doubt before saving.
            PathPreview(
                servedOrigin = servedOrigin,
                path = current.path,
                modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s5),
            )

            if (hasConflict) {
                ConflictWarning()
            }

            ResponseModeSection(
                mode = current.responseMode,
                behavior = current.sequentialBehavior,
                onModeChange = { mode -> viewModel.update { it.copy(responseMode = mode) } },
                onBehaviorChange = { behavior -> viewModel.update { it.copy(sequentialBehavior = behavior) } },
                modifier = Modifier.padding(bottom = Spacing.s4),
            )

            SectionHeader(
                text = "Responses",
                modifier = Modifier.padding(bottom = 9.dp),
                trailing = {
                    TextButton(onClick = viewModel::addResponse) {
                        Text(
                            text = "Add",
                            style = MockonomyTheme.typography.label,
                            color = MockonomyTheme.colors.primaryText,
                        )
                    }
                },
            )

            // In RULES mode the filled dot on the left works like a selectable radio button —
            // the user changes the default response with one tap. SEQUENTIAL mode has no
            // "active response" concept, so the dot is inactive.
            val defaultSelectable = current.responseMode != ResponseMode.SEQUENTIAL
            current.responses.forEach { response ->
                ResponseRow(
                    response = response,
                    defaultSelectable = defaultSelectable,
                    onSelectDefault = { viewModel.selectDefaultResponse(response.id) },
                    onClick = { onResponseClick(response.id) },
                    onDelete = { viewModel.deleteResponse(response.id) },
                )
                HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
            }

            Text(
                text = if (current.responseMode == ResponseMode.SEQUENTIAL) {
                    "Sequential mode: responses answer in list order, one per request. " +
                        "Rules and the fallback dot are ignored."
                } else {
                    "Rules are checked top to bottom — the first response that passes answers. " +
                        "The filled dot is the fallback when none of them do."
                },
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier.padding(top = Spacing.s3),
            )

        }
    }
}

/**
 * Response mode picker. `RULES` (rule-based) or `SEQUENTIAL` (in list order). When
 * sequential is chosen, the end-of-list behavior (`STAY_ON_LAST` / `CYCLE`) appears.
 * `RANDOM` is not offered in the UI — the engine does not support it yet.
 */
@Composable
private fun ResponseModeSection(
    mode: ResponseMode,
    behavior: SequentialBehavior,
    onModeChange: (ResponseMode) -> Unit,
    onBehaviorChange: (SequentialBehavior) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(text = "Response mode", modifier = Modifier.padding(bottom = 9.dp))

        SegmentedToggle(
            options = listOf(ResponseMode.RULES, ResponseMode.SEQUENTIAL),
            selected = mode,
            label = { if (it == ResponseMode.RULES) "Rules" else "Sequential" },
            onSelect = onModeChange,
        )

        if (mode == ResponseMode.SEQUENTIAL) {
            Text(
                text = "On end of list:",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s1),
            )
            SegmentedToggle(
                options = listOf(SequentialBehavior.STAY_ON_LAST, SequentialBehavior.CYCLE),
                selected = behavior,
                label = { if (it == SequentialBehavior.STAY_ON_LAST) "Stay on last" else "Cycle" },
                onSelect = onBehaviorChange,
            )
        }
    }
}

/** A single-row segmented picker with two or three options. */
@Composable
private fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.border(1.dp, MockonomyTheme.colors.outline, RoundedCornerShape(8.dp)),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Text(
                text = label(option),
                style = MockonomyTheme.typography.label,
                color = if (isSelected) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier
                    .clickable { onSelect(option) }
                    .background(
                        if (isSelected) MockonomyTheme.colors.primaryContainer else MockonomyTheme.colors.surface,
                    )
                    .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
            )
        }
    }
}

/**
 * "Served as 127.0.0.1:8080/v2/users/:id · :id captures a path segment."
 * The parameter hint only appears when the path really has a parameter.
 */
@Composable
private fun PathPreview(servedOrigin: String, path: String, modifier: Modifier = Modifier) {
    val firstParam = path.split('/').firstOrNull { it.startsWith(':') && it.length > 1 }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Served as ",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
            Text(
                text = servedOrigin + path,
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (firstParam != null) {
            Text(
                text = "$firstParam captures a path segment.",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
        }
    }
}

/**
 * Warns, does not block: two routes with the same method + path are a legitimate use
 * together with rules.
 */
@Composable
private fun ConflictWarning() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.Warning,
            contentDescription = null,
            tint = MockonomyTheme.colors.warning,
            size = 16.dp,
        )
        Text(
            text = "Another route has the same method and path. The one higher in the list answers first.",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResponseRow(
    response: RouteResponse,
    defaultSelectable: Boolean,
    onSelectDefault: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Filled dot = the served response; empty ring = waiting on a rule.
        // In RULES mode the dot is a clickable radio — the user changes the default here
        // (it replaces the old "Switch response" modal).
        Box(
            modifier = Modifier
                .size(MockonomyMetrics.minTouchTarget)
                .then(
                    if (defaultSelectable && !response.isDefault) {
                        Modifier.clickable(onClick = onSelectDefault)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (defaultSelectable) 12.dp else 9.dp)
                    .then(
                        if (response.isDefault) {
                            Modifier.background(MockonomyTheme.colors.primaryText, CircleShape)
                        } else {
                            Modifier.border(1.dp, MockonomyTheme.colors.outline, CircleShape)
                        },
                    ),
            )
        }

        StatusCodeChip(statusCode = response.statusCode, modifier = Modifier.padding(start = Spacing.s2))

        Column(modifier = Modifier.weight(1f).padding(start = Spacing.s3)) {
            Text(
                text = response.label.ifBlank { "Untitled response" },
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // What matters is not how many rules there are but *when* this response is
            // returned — the row itself should say that.
            Text(
                text = response.servingSummary(),
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (response.isDefault) {
            Text(
                text = "DEFAULT",
                style = MockonomyTheme.typography.overline,
                color = MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier
                    .padding(end = Spacing.s2)
                    .border(1.dp, MockonomyTheme.colors.outline, RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(width = 38.dp, height = MockonomyMetrics.minTouchTarget),
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.X,
                contentDescription = "Delete response",
                tint = MockonomyTheme.colors.onSurfaceFaint,
                size = 14.dp,
            )
        }
    }
}

/**
 * Says in one line when a response is returned.
 *
 * A response with no rules that is not the default is **never returned**
 * (`spec/behavior/matching.md` §5). Saying so in the list keeps the user from searching
 * "why does it not work" after writing their rule.
 */
private fun RouteResponse.servingSummary(): String = when {
    rules.isNotEmpty() -> {
        val count = if (rules.size == 1) "1 rule" else "${rules.size} rules"
        if (isDefault) "$count · also the fallback" else "$count · ${rulesOperator.name.lowercase()}"
    }
    isDefault -> "Served when nothing else matches"
    else -> "No rules — never served"
}

private const val UNSELECTED_ALPHA = 0.35f
