package com.mockonomy.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.mockonomy.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.ui.components.EmptyState
import com.mockonomy.ui.components.MockonomySheet
import com.mockonomy.ui.components.ServingStrip
import com.mockonomy.ui.components.SheetActionRow
import com.mockonomy.ui.screens.environmentlist.EnvironmentListViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The root of the navigation tree: the environment list.
 *
 * Start/stop on a row only affects that environment; several environments can run side
 * by side, each on its own port.
 *
 * One deviation from the design: rows end with a ⋮ menu instead of a caret. In the
 * prototype rows end with a caret and environment actions live in the detail screen's ⋮
 * menu — but the prototype never shows that menu's content. Rather than moving the
 * working, tested rename/duplicate/delete actions based on a guess, they stayed where
 * they are; the caret was only a "you can go in here" hint, and the row still opens on tap.
 */
@Composable
fun EnvironmentListScreen(
    onEnvironmentClick: (environmentId: String) -> Unit,
    onSetupGuideClick: () -> Unit,
    onImportClick: () -> Unit,
    onAutomationClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnvironmentListViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsStateWithLifecycle()
    val initialLoadComplete by viewModel.initialLoadComplete.collectAsStateWithLifecycle()
    val runningServers by viewModel.runningServers.collectAsStateWithLifecycle()
    val servingRows by viewModel.servingRows.collectAsStateWithLifecycle()

    var createSheetVisible by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<EnvironmentSummary.Valid?>(null) }
    var deleteTarget by remember { mutableStateOf<EnvironmentSummary?>(null) }
    // Export has two steps: first the redaction dialog, then saving via SAF.
    var exportTarget by remember { mutableStateOf<EnvironmentSummary.Valid?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // SAF: the user decides where to write/read the file; the app asks for no storage
    // permission (the same pattern as the HAR flow in LogsTab).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME),
    ) { uri ->
        val pending = pendingExport
        pendingExport = null
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = viewModel.exportJson(pending.id, pending.redact) ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } }
            }
            snackbarHostState.showSnackbar("Exported \"${pending.name}\"")
        }
    }
    // The list used to refresh only after its own mutations, so it could not see changes
    // made by another screen. Import creates the environment in ITS OWN ViewModel — that
    // environment never showed up in the list (seen on a device). Coming back from the
    // detail screen also left the route count stale.
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Server errors, including port conflicts, surface here in one place. The flow carries
    // one-off events: the same error is not shown again when the screen recomposes.
    LaunchedEffect(Unit) {
        viewModel.failures.collect { failure -> snackbarHostState.showSnackbar(failure.reason) }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MockonomyTheme.colors.background,
        // Insets are applied at the root (MainActivity `safeDrawingPadding`) — the Scaffold
        // must not add them again here (otherwise status/nav padding would be doubled).
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (environments.isNotEmpty()) {
                NewEnvironmentButton(onClick = { createSheetVisible = true })
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.s3, end = Spacing.s1, top = Spacing.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Brand pill: icon + text in an oval box with a `primaryContainer` fill, for a
                // brand-card feel. The action icons of the outer Row are not affected.
                Row(
                    modifier = Modifier
                        .background(MockonomyTheme.colors.primaryContainer, RoundedCornerShape(999.dp))
                        .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The app icon (the adaptive launcher foreground) — within the 108dp canvas
                    // the drawing sits in the 66dp safe zone. At a 32dp display size the mark is
                    // ~20dp, optically balanced with the "MOCKONOMY" text.
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        text = "MOCKONOMY",
                        style = MockonomyTheme.typography.title,
                        color = MockonomyTheme.colors.onSurface,
                    )
                }
                Box(modifier = Modifier.weight(1f))
                IconButton(onClick = onImportClick) {
                    MockonomyIcon(
                        icon = MockonomyIcons.FileArrowDown,
                        contentDescription = "Import OpenAPI or Mockoon",
                    )
                }
                IconButton(onClick = onAutomationClick) {
                    MockonomyIcon(
                        icon = MockonomyIcons.PlugsConnectedFill,
                        contentDescription = "Automation settings",
                    )
                }
                IconButton(onClick = onSetupGuideClick) {
                    MockonomyIcon(
                        icon = MockonomyIcons.Question,
                        contentDescription = "Connect your app",
                    )
                }
                IconButton(onClick = onSettingsClick) {
                    MockonomyIcon(
                        icon = MockonomyIcons.Gear,
                        contentDescription = "Settings",
                    )
                }
            }

            Column(modifier = Modifier.padding(start = Spacing.s4, end = Spacing.s4, bottom = Spacing.s3)) {
                Text(
                    text = "Environments",
                    style = MockonomyTheme.typography.display,
                    color = MockonomyTheme.colors.onSurface,
                )
            }

            // Each running server gets its own strip. The order is start order — the user
            // finds what they opened last at the bottom.
            servingRows.forEachIndexed { index, row ->
                val isLast = index == servingRows.lastIndex
                ServingStrip(
                    name = row.name,
                    address = row.address,
                    onStop = { viewModel.toggleRunning(row.environmentId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.s3)
                        .padding(bottom = if (row.states.isEmpty() && isLast) 14.dp else Spacing.s2),
                )
                // Quick state switch — when that environment has a state machine, without
                // going into settings.
                if (row.states.isNotEmpty()) {
                    StateQuickSwitch(
                        states = row.states,
                        activeState = row.activeState,
                        onSelect = { viewModel.setActiveState(row.environmentId, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.s3)
                            .padding(bottom = if (isLast) 14.dp else Spacing.s2),
                    )
                }
            }

            if (!initialLoadComplete && environments.isEmpty()) {
                // Skeleton rows instead of a blank screen during the first load — avoids the
                // "has the app frozen?" feeling.
                EnvironmentListSkeleton()
            } else if (environments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(Spacing.s5), contentAlignment = Alignment.TopCenter) {
                    EmptyState(
                        title = "No environments yet",
                        description = "An environment is a swappable profile — a port, a path prefix, and a set of routes.",
                        primaryActionLabel = "Create environment",
                        onPrimaryAction = { createSheetVisible = true },
                        icon = MockonomyIcons.Stack,
                        secondaryActionLabel = "Load the example instead",
                        onSecondaryAction = viewModel::loadExample,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(environments, key = { it.id }) { summary ->
                        EnvironmentRow(
                            summary = summary,
                            isActive = runningServers.any { it.environmentId == summary.id },
                            onClick = {
                                if (summary is EnvironmentSummary.Valid) onEnvironmentClick(summary.id)
                            },
                            onToggleRunning = { viewModel.toggleRunning(summary.id) },
                            onRename = { if (summary is EnvironmentSummary.Valid) renameTarget = summary },
                            onDuplicate = { viewModel.duplicate(summary.id) },
                            onExport = { if (summary is EnvironmentSummary.Valid) exportTarget = summary },
                            onDelete = { deleteTarget = summary },
                        )
                        HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
                    }
                    item {
                        Text(
                            text = "Environments run side by side — but two cannot share a port.",
                            style = MockonomyTheme.typography.caption,
                            color = MockonomyTheme.colors.onSurfaceFaint,
                            modifier = Modifier.padding(
                                start = Spacing.s4,
                                end = Spacing.s4,
                                top = Spacing.s3,
                                bottom = Spacing.s1,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (createSheetVisible) {
        CreateEnvironmentSheet(
            onDismiss = { createSheetVisible = false },
            onCreate = { name ->
                viewModel.create(name)
                createSheetVisible = false
            },
        )
    }

    renameTarget?.let { target ->
        RenameEnvironmentSheet(
            currentName = target.environment.name,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                viewModel.rename(target.id, newName)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteConfirmationDialog(
            environmentName = (target as? EnvironmentSummary.Valid)?.environment?.name ?: target.id,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                viewModel.delete(target.id)
                deleteTarget = null
            },
        )
    }

    exportTarget?.let { target ->
        ExportDialog(
            environmentName = target.environment.name,
            hasSensitive = { viewModel.containsSensitiveHeaders(target.id) },
            onDismiss = { exportTarget = null },
            onConfirm = { redact ->
                pendingExport = PendingExport(target.id, target.environment.name, redact)
                exportTarget = null
                exportLauncher.launch(exportFileName(target.environment.name))
            },
        )
    }
}

/** Information carried to the SAF save step of the two-step export. */
private data class PendingExport(val id: String, val name: String, val redact: Boolean)

/**
 * The redaction choice before export. "Hide sensitive headers" is **on by default**. If the
 * environment really contains sensitive headers this is called out too — the user should
 * see what will be masked.
 */
@Composable
private fun ExportDialog(
    environmentName: String,
    hasSensitive: suspend () -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: (redact: Boolean) -> Unit,
) {
    var redact by remember { mutableStateOf(true) }
    var sensitivePresent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { sensitivePresent = hasSensitive() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export \"$environmentName\"") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { redact = !redact }.padding(vertical = Spacing.s1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = redact, onCheckedChange = { redact = it })
                    Text(
                        text = "Redact sensitive headers",
                        style = MockonomyTheme.typography.body,
                        color = MockonomyTheme.colors.onSurface,
                        modifier = Modifier.padding(start = Spacing.s2),
                    )
                }
                Text(
                    text = if (sensitivePresent) {
                        "This environment has sensitive headers (Authorization, Cookie, …). " +
                            "Their values are replaced with ***REDACTED***."
                    } else {
                        "Values of headers like Authorization and Cookie are replaced with " +
                            "***REDACTED*** so tokens don't leave the device."
                    },
                    style = MockonomyTheme.typography.caption,
                    color = if (sensitivePresent) MockonomyTheme.colors.warning else MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.padding(top = Spacing.s2, start = Spacing.s1),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(redact) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** File name: derived from the environment name, unsafe characters become `_`. */
private fun exportFileName(name: String): String {
    val safe = name.trim().ifEmpty { "environment" }.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
    return "$safe.json"
}

/**
 * Shown instead of the real rows while the first `list()` is running. Sizes are close to
 * [EnvironmentRow] — avoids the odd flash of rows suddenly jumping into place. A soft alpha
 * pulse is a "live but quiet" texture; there is no need for a shimmer gradient, nor for the
 * extra drawing pass it would cost.
 */
@Composable
private fun EnvironmentListSkeleton(rowCount: Int = 3) {
    val transition = rememberInfiniteTransition(label = "skeleton-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    val baseColor = MockonomyTheme.colors.outlineSubtle
    Column(modifier = Modifier.fillMaxWidth()) {
        repeat(rowCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MockonomyMetrics.listRowHeight)
                    .padding(horizontal = Spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(color = baseColor.copy(alpha = alpha), shape = CircleShape),
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    SkeletonBar(width = 160.dp, height = 14.dp, color = baseColor.copy(alpha = alpha))
                    SkeletonBar(width = 220.dp, height = 12.dp, color = baseColor.copy(alpha = alpha * 0.75f))
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

/** Circular run/stop button, name (+ ACTIVE badge), mono meta, ⋮ menu. */
@Composable
private fun EnvironmentRow(
    summary: EnvironmentSummary,
    isActive: Boolean,
    onClick: () -> Unit,
    onToggleRunning: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = MockonomyMetrics.listRowHeight)
            .padding(horizontal = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (summary) {
            is EnvironmentSummary.Valid -> {
                PlayStopButton(isRunning = isActive, onClick = onToggleRunning)

                Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = summary.environment.name,
                            style = MockonomyTheme.typography.title,
                            color = MockonomyTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isActive) {
                            Text(
                                text = "ACTIVE",
                                style = MockonomyTheme.typography.overline,
                                color = MockonomyTheme.colors.success,
                                modifier = Modifier
                                    .padding(start = Spacing.s2)
                                    .border(
                                        width = 1.dp,
                                        color = MockonomyTheme.colors.success.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 5.dp, vertical = 3.dp),
                            )
                        }
                    }
                    val proxy = summary.environment.proxy
                    val meta = buildString {
                        append("127.0.0.1:${summary.environment.port} · ${summary.environment.routes.size} routes")
                        if (proxy?.enabled == true && proxy.target.isNotBlank()) {
                            append(" · proxy → ${proxyHost(proxy.target)}")
                        }
                    }
                    Text(
                        text = meta,
                        style = MockonomyTheme.typography.monoBody,
                        // While the proxy is on the device forwards traffic to a real server —
                        // the meta line makes that visible.
                        color = if (proxy?.enabled == true) MockonomyTheme.colors.primaryText
                        else MockonomyTheme.colors.onSurfaceFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            is EnvironmentSummary.Corrupt -> {
                MockonomyIcon(
                    icon = MockonomyIcons.Warning,
                    contentDescription = null,
                    tint = MockonomyTheme.colors.error,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                    Text(
                        text = summary.id,
                        style = MockonomyTheme.typography.title,
                        color = MockonomyTheme.colors.error,
                    )
                    Text(
                        text = summary.reason,
                        style = MockonomyTheme.typography.monoBody,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                    )
                }
            }
        }

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                MockonomyIcon(
                    icon = MockonomyIcons.DotsThreeVertical,
                    contentDescription = "More",
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                if (summary is EnvironmentSummary.Valid) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuExpanded = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Export") }, onClick = { menuExpanded = false; onExport() })
                }
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

/** `https://api.example.com/v1` → `api.example.com`; scheme and path are dropped. */
private fun proxyHost(target: String): String =
    target.substringAfter("://").substringBefore('/').ifBlank { target }

/** A 40dp circular single button — accent-filled while running, neutral-bordered while stopped. */
@Composable
private fun PlayStopButton(isRunning: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .border(
                width = 1.dp,
                color = if (isRunning) MockonomyTheme.colors.primary else MockonomyTheme.colors.outline,
                shape = CircleShape,
            )
            .background(
                color = if (isRunning) MockonomyTheme.colors.primaryContainer else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MockonomyIcon(
            icon = if (isRunning) MockonomyIcons.StopFill else MockonomyIcons.PlayFill,
            contentDescription = if (isRunning) "Stop" else "Start",
            tint = if (isRunning) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceVariant,
            size = 13.dp,
        )
    }
}

/** The bottom-right pill from the design: 52dp tall, 16dp corners, accent border, surface fill. */
@Composable
private fun NewEnvironmentButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
                text = "New",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier.padding(start = Spacing.s2),
            )
        }
    }
}

@Composable
private fun CreateEnvironmentSheet(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    MockonomySheet(onDismissRequest = onDismiss) {
        Text(
            text = "Create environment",
            style = MockonomyTheme.typography.titleLarge,
            color = MockonomyTheme.colors.onSurface,
            modifier = Modifier.padding(Spacing.s4),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s4),
        )
        SheetActionRow(
            cancelLabel = "Cancel",
            onCancel = onDismiss,
            confirmLabel = "Create",
            onConfirm = { onCreate(name.trim()) },
            confirmEnabled = name.isNotBlank(),
        )
    }
}

@Composable
private fun RenameEnvironmentSheet(currentName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(currentName) }
    MockonomySheet(onDismissRequest = onDismiss) {
        Text(
            text = "Rename environment",
            style = MockonomyTheme.typography.titleLarge,
            color = MockonomyTheme.colors.onSurface,
            modifier = Modifier.padding(Spacing.s4),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s4),
        )
        SheetActionRow(
            cancelLabel = "Cancel",
            onCancel = onDismiss,
            confirmLabel = "Save",
            onConfirm = { onRename(name.trim()) },
            confirmEnabled = name.isNotBlank(),
        )
    }
}

/** Data the user may have spent hours on — never deleted silently. */
@Composable
private fun DeleteConfirmationDialog(environmentName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"$environmentName\"?") },
        text = { Text("This removes the environment file from the device. This can't be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MockonomyTheme.colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** SAF: the JSON MIME type for export. */
private const val JSON_MIME = "application/json"

/**
 * Quickly switches the running environment's active state from below the ServingStrip.
 * Each state is a chip; the active one is highlighted. A shortcut to the control on the
 * Settings tab — the most frequent action during manual testing.
 */
@Composable
private fun StateQuickSwitch(
    states: List<String>,
    activeState: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        verticalArrangement = Arrangement.spacedBy(Spacing.s2),
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.Pulse,
            contentDescription = null,
            tint = MockonomyTheme.colors.primaryText,
            size = 13.dp,
        )
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
