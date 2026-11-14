package com.mockonomy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.engine.model.Environment
import com.mockonomy.ui.components.EmptyState
import com.mockonomy.ui.screens.detail.EnvironmentDetailViewModel
import com.mockonomy.ui.screens.logs.LogsTab
import com.mockonomy.ui.screens.routes.RoutesSkeleton
import com.mockonomy.ui.screens.routes.RoutesTab
import com.mockonomy.ui.screens.settings.EnvironmentSettingsTab
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing

/**
 * The `{environmentId}` node: the Routes, Logs and Settings tabs.
 *
 * Routes/Logs/Settings are not separate nav destinations — they are inner tab state.
 * [rememberSaveable] is used so the user stays on the same tab after rotation or process
 * death.
 *
 * The two real tabs share a **single** [EnvironmentDetailViewModel]: both write the same
 * file, and with separate copies one would overwrite the other.
 */
@Composable
fun EnvironmentDetailScreen(
    environmentId: String,
    onBack: () -> Unit,
    onRouteClick: (routeId: String) -> Unit,
    onLogClick: (logId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnvironmentDetailViewModel = hiltViewModel(),
) {
    var tab by rememberSaveable { mutableStateOf(EnvironmentTab.Routes) }
    val environment by viewModel.environment.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val portError by viewModel.portError.collectAsStateWithLifecycle()

    // Port conflicts and range errors originate exactly on this screen (Settings).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.failures.collect { failure -> snackbarHostState.showSnackbar(failure.reason) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = environment?.name ?: environmentId,
                        style = MockonomyTheme.typography.title,
                        color = MockonomyTheme.colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = environment?.let { "127.0.0.1:${it.port}" } ?: "",
                        style = MockonomyTheme.typography.monoMeta,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                    )
                }
                if (environment != null) {
                    StartStopButton(
                        isRunning = isRunning,
                        // Stop stays available: the port may have been broken while the
                        // server was already serving on a good one.
                        enabled = isRunning || portError == null,
                        onClick = viewModel::toggleRunning,
                        modifier = Modifier.padding(start = Spacing.s2),
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s2).padding(top = Spacing.s2)) {
                EnvironmentTab.entries.forEach { candidate ->
                    TabButton(
                        label = candidate.label,
                        count = environment?.let { candidate.countOf(it) },
                        selected = candidate == tab,
                        onClick = { tab = candidate },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)

            val current = environment
            when {
                // The file could not be read: it may have been deleted or corrupted. An empty
                // screen without explanation is the worst kind of error state.
                current == null && loaded -> NotFoundPlaceholder(onBack)

                // The environment is still being read. On the Routes tab show a skeleton —
                // for large environment files it avoids the "has the app frozen?" feeling.
                // Logs/Settings are a separate job; they wait without the body flickering.
                current == null && tab == EnvironmentTab.Routes -> RoutesSkeleton()
                current == null -> Unit
                tab == EnvironmentTab.Routes -> RoutesTab(
                    viewModel = viewModel,
                    environment = current,
                    onRouteClick = onRouteClick,
                )
                tab == EnvironmentTab.Logs -> LogsTab(
                    onLogClick = onLogClick,
                    onBackToRoutes = { tab = EnvironmentTab.Routes },
                )
                tab == EnvironmentTab.Settings -> EnvironmentSettingsTab(viewModel = viewModel, current = current)
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

private enum class EnvironmentTab(val label: String) {
    Routes("Routes"),
    Logs("Logs"),
    Settings("Settings"),
    ;

    /** The counter next to the tab label. `null` → nothing to count. */
    fun countOf(environment: Environment): Int? = when (this) {
        Routes -> environment.routes.size
        // The log count is not on the environment object but in the store; opening a
        // separate query just for the tab label is not worth the information it shows.
        Logs -> null
        Settings -> null
    }
}

/**
 * 46dp tall; the selected tab is marked by a 2dp accent line at the bottom. The counter is
 * mono and one tone fainter than the label — the tab name reads first, the number second.
 */
@Composable
private fun TabButton(
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            // Careful: `weight` is NOT used here — inside a Column it means vertical weight
            // and stretches the tab row to the screen height (seen on a device).
            modifier = Modifier.defaultMinSize(minHeight = 46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MockonomyTheme.typography.label,
                color = if (selected) MockonomyTheme.colors.onSurface else MockonomyTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (count != null) {
                Text(
                    text = count.toString(),
                    style = MockonomyTheme.typography.monoMeta,
                    color = if (selected) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) MockonomyTheme.colors.primary else Color.Transparent),
        )
    }
}

/**
 * Start/Stop in the app bar — accent-bordered while running, neutral while stopped and
 * faint while disabled (an unbindable port).
 */
@Composable
private fun StartStopButton(
    isRunning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconTint = when {
        !enabled -> MockonomyTheme.colors.onSurfaceFaint
        isRunning -> MockonomyTheme.colors.primaryText
        else -> MockonomyTheme.colors.onSurfaceVariant
    }
    val labelColor = when {
        !enabled -> MockonomyTheme.colors.onSurfaceFaint
        isRunning -> MockonomyTheme.colors.primaryText
        else -> MockonomyTheme.colors.onSurface
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                !enabled -> MockonomyTheme.colors.outlineSubtle
                isRunning -> MockonomyTheme.colors.primary
                else -> MockonomyTheme.colors.outline
            },
        ),
    ) {
        MockonomyIcon(
            icon = if (isRunning) MockonomyIcons.StopFill else MockonomyIcons.PlayFill,
            contentDescription = null,
            tint = iconTint,
            size = 11.dp,
        )
        Text(
            text = if (isRunning) "Stop" else "Start",
            style = MockonomyTheme.typography.label,
            color = labelColor,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun NotFoundPlaceholder(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            title = "Environment not found",
            description = "Its file is gone from this device. It may have been deleted.",
            primaryActionLabel = "Back to environments",
            onPrimaryAction = onBack,
        )
    }
}

@MockonomyThemePreview
@Composable
private fun EnvironmentDetailScreenPreview() {
    MockonomyTheme {
        EnvironmentDetailScreen(
            environmentId = "env-staging",
            onBack = {},
            onRouteClick = {},
            onLogClick = {},
        )
    }
}
