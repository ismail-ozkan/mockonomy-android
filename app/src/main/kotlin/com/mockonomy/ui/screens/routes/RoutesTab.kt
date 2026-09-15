package com.mockonomy.ui.screens.routes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.ui.components.EmptyState
import com.mockonomy.ui.components.MethodChip
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.screens.detail.EnvironmentDetailViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * The route list.
 *
 * Order is preserved and can be changed by hand: in matching "the first route in the list
 * wins", so the order is part of the behavior. A move menu was chosen over drag and drop —
 * dragging on a LazyColumn is not worth the complexity it adds, and the menu makes
 * reordering a *deliberate* action.
 *
 * The row's second line is not a "metadata soup" but the response's identity: status code,
 * human-readable name, and latency only when it is not zero.
 */
@Composable
fun RoutesTab(
    viewModel: EnvironmentDetailViewModel,
    environment: Environment,
    onRouteClick: (routeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var addSheetVisible by rememberSaveable { mutableStateOf(false) }
    var switchTarget by remember { mutableStateOf<Route?>(null) }

    val visibleRoutes = remember(environment.routes, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            environment.routes
        } else {
            environment.routes.filter {
                it.path.contains(trimmed, ignoreCase = true) ||
                    it.method.name.contains(trimmed, ignoreCase = true)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (environment.routes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(top = 44.dp), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    title = "No routes here",
                    description = "This environment answers every request with 404 until you add one.",
                    primaryActionLabel = "Add route",
                    onPrimaryAction = { addSheetVisible = true },
                    icon = MockonomyIcons.GitBranch,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    FilterBar(
                        query = query,
                        onQueryChange = { query = it },
                        modifier = Modifier.padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                    )
                }
                items(visibleRoutes, key = { it.id }) { route ->
                    RouteRow(
                        route = route,
                        canMoveUp = environment.routes.first().id != route.id,
                        canMoveDown = environment.routes.last().id != route.id,
                        onClick = { onRouteClick(route.id) },
                        onSwitchResponse = { switchTarget = route },
                        onEnabledChange = { viewModel.setRouteEnabled(route.id, it) },
                        onMoveUp = { viewModel.moveRoute(route.id, -1) },
                        onMoveDown = { viewModel.moveRoute(route.id, 1) },
                        onDelete = { viewModel.deleteRoute(route.id) },
                    )
                    HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
                }
                item {
                    Text(
                        text = "Tap a row to edit · the swap icon changes the active response " +
                            "without leaving the list.",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                        modifier = Modifier.padding(horizontal = Spacing.s4, vertical = Spacing.s3),
                    )
                }
                // So the FAB does not cover the last row.
                item { Box(modifier = Modifier.size(72.dp)) }
            }
        }

        if (environment.routes.isNotEmpty()) {
            AddRouteButton(
                onClick = { addSheetVisible = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(Spacing.s4),
            )
        }
    }

    if (addSheetVisible) {
        AddRouteSheet(
            onDismiss = { addSheetVisible = false },
            onAdd = { method, path ->
                viewModel.addRoute(method, path)
                addSheetVisible = false
            },
        )
    }

    switchTarget?.let { route ->
        SwitchResponseSheet(
            responses = route.responses,
            onDismiss = { switchTarget = null },
            onSelect = { responseId ->
                viewModel.setActiveResponse(route.id, responseId)
                switchTarget = null
            },
        )
    }
}

/**
 * A single field that filters path and method together. The "ALL" method button from the
 * design is not a separate control — typing `GET` does the same job, so there is no need
 * for a second dropdown.
 */
@Composable
private fun FilterBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 38.dp)
            .background(MockonomyTheme.colors.surfaceVariant, RoundedCornerShape(8.dp))
            .border(1.dp, MockonomyTheme.colors.outlineSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.MagnifyingGlass,
            contentDescription = null,
            tint = MockonomyTheme.colors.onSurfaceFaint,
            size = 13.dp,
        )
        Box(modifier = Modifier.weight(1f).padding(start = 7.dp)) {
            if (query.isEmpty()) {
                Text(
                    text = "filter path or method",
                    style = MockonomyTheme.typography.monoBody,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                cursorBrush = SolidColor(MockonomyTheme.colors.primaryText),
                textStyle = MockonomyTheme.typography.monoBody.copy(color = MockonomyTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(24.dp)) {
                MockonomyIcon(
                    icon = MockonomyIcons.X,
                    contentDescription = "Clear filter",
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                    size = 13.dp,
                )
            }
        }
    }
}

@Composable
private fun RouteRow(
    route: Route,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onSwitchResponse: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val active = route.activeResponse()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 58.dp)
            .padding(start = Spacing.s3, end = Spacing.s2, top = Spacing.s2, bottom = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MethodChip(method = route.method)

        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = route.path,
                style = MockonomyTheme.typography.monoPath,
                // A disabled route dims the whole row, not the chip (a design system rule).
                color = if (route.enabled) {
                    MockonomyTheme.colors.onSurface
                } else {
                    MockonomyTheme.colors.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (active != null) {
                    StatusCodeChip(statusCode = active.statusCode)
                    Text(
                        text = active.label.ifBlank { "untitled response" },
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 7.dp),
                    )
                    // Latency only when it is not zero — and in the warning color, because a
                    // slow response is always a deliberate choice.
                    if (active.latencyMs > 0) {
                        Text(
                            text = "${active.latencyMs} ms",
                            style = MockonomyTheme.typography.monoMeta,
                            color = MockonomyTheme.colors.warning,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                } else {
                    Text(
                        text = "no responses",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.error,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (!route.enabled) {
            // Red: "this route is muted" is a warning, and neutral gray did not stand out
            // enough. The border is half-alpha, the counterpart of the same pattern as the
            // ACTIVE badge (in the success color).
            //
            // The badge sits in the outer Row: thanks to the outer
            // `verticalAlignment = CenterVertically` it sits at the row's vertical center; in
            // the inner meta Row it would drift toward the bottom (user feedback).
            Text(
                text = "OFF",
                style = MockonomyTheme.typography.overline,
                color = MockonomyTheme.colors.error,
                modifier = Modifier
                    .padding(start = 7.dp)
                    .border(1.dp, MockonomyTheme.colors.error.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }

        // The most frequent action in manual testing is changing which response is active —
        // without leaving the row.
        if (route.responses.size > 1) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onSwitchResponse),
                contentAlignment = Alignment.Center,
            ) {
                MockonomyIcon(
                    icon = MockonomyIcons.Swap,
                    contentDescription = "Switch response",
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                    size = 16.dp,
                )
            }
        }

        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(width = 32.dp, height = 40.dp)) {
                MockonomyIcon(
                    icon = MockonomyIcons.DotsThreeVertical,
                    contentDescription = "Route actions",
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                    size = 16.dp,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text(if (route.enabled) "Disable" else "Enable") },
                    onClick = { menuExpanded = false; onEnabledChange(!route.enabled) },
                )
                if (canMoveUp) {
                    DropdownMenuItem(
                        text = { Text("Move up") },
                        onClick = { menuExpanded = false; onMoveUp() },
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        text = { Text("Move down") },
                        onClick = { menuExpanded = false; onMoveDown() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Delete", color = MockonomyTheme.colors.error) },
                    onClick = { menuExpanded = false; onDelete() },
                )
            }
        }
    }
}

/**
 * Shown instead of the real rows on the Routes tab while the environment is being read.
 * Sizes are close to [RouteRow]: a rectangular bar instead of the method chip, then a path
 * bar and a status+label bar below it. The same pattern exists in the environment list
 * (`EnvironmentListSkeleton`); it was not moved into a shared component — only the sizes
 * differ between the two, and a third use would justify moving it to `ui/components/`.
 */
@Composable
fun RoutesSkeleton(rowCount: Int = 6) {
    val transition = rememberInfiniteTransition(label = "routes-skeleton-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "routes-skeleton-alpha",
    )
    val baseColor = MockonomyTheme.colors.outlineSubtle
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rowCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 58.dp)
                    .padding(start = Spacing.s3, end = Spacing.s2, top = Spacing.s2, bottom = Spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 46.dp, height = 22.dp)
                        .background(baseColor.copy(alpha = alpha), shape = RoundedCornerShape(4.dp)),
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    SkeletonBar(width = 200.dp, height = 12.dp, color = baseColor.copy(alpha = alpha))
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SkeletonBar(width = 32.dp, height = 10.dp, color = baseColor.copy(alpha = alpha))
                        Box(modifier = Modifier.size(width = 7.dp, height = 1.dp))
                        SkeletonBar(width = 100.dp, height = 10.dp, color = baseColor.copy(alpha = alpha * 0.75f))
                    }
                }
            }
            HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
        }
    }
}

@Composable
private fun SkeletonBar(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, color: Color) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .background(color = color, shape = RoundedCornerShape(4.dp)),
    )
}

/** The bottom-right pill from the design; the same measurements as on the environment list. */
@Composable
private fun AddRouteButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
        shape = RoundedCornerShape(16.dp),
        color = MockonomyTheme.colors.surface,
        border = BorderStroke(1.dp, MockonomyTheme.colors.primary),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.Plus,
                contentDescription = null,
                tint = MockonomyTheme.colors.primaryText,
                size = 16.dp,
            )
            Text(
                text = "Route",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier.padding(start = Spacing.s2),
            )
        }
    }
}

/**
 * Per schema rule 7 exactly one response is the default; still, a corrupt file may arrive
 * here without one — then the first one in the list is served
 * (`spec/behavior/matching.md`, "first response when no default").
 */
private fun Route.activeResponse(): RouteResponse? =
    responses.firstOrNull { it.isDefault } ?: responses.firstOrNull()
