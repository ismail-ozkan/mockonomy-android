package com.mockonomy.ui.screens.logs

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.data.log.RequestLogSummary
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.ui.components.ChipSize
import com.mockonomy.ui.components.EmptyState
import com.mockonomy.ui.components.MethodChip
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The request log.
 *
 * New requests appear on top and flow in live; the list comes from Room's `Flow`. The row
 * design speaks the same language as the route list: time · method chip · mono path ·
 * status chip · duration.
 *
 * Filtering uses a single field + class chips: instead of a separate dropdown for the
 * method, typing `POST` into the search field is enough (the same decision as in
 * `RoutesTab`). The status **class** is a chip: the question during manual testing is "did
 * something break".
 */
@Composable
fun LogsTab(
    onLogClick: (logId: String) -> Unit,
    onBackToRoutes: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsViewModel = hiltViewModel(),
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val initialLoadComplete by viewModel.initialLoadComplete.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var statusClass by rememberSaveable { mutableStateOf(StatusClass.All) }

    // Selection mode for bulk "create mock". Selection is kept by id, so marks survive
    // filter changes.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }

    val visible = remember(logs, query, statusClass) { logs.filterLogs(query, statusClass) }

    // A new entry is added at the top. With stable `key`s LazyColumn keeps the old first item
    // visually fixed (anchor); if the list is longer than a screen the new item sits above
    // the viewport, off screen, and the live stream looks "stopped" (verified on a device —
    // the Flow emitted correctly, the screen did not show it).
    //
    // If the user is already at the top we keep them there; if they are reading older entries
    // further down we do not move them. After the anchor shift "the top" is
    // firstVisibleItemIndex 0 (a short list that cannot scroll) or 1 (after a prepend).
    val listState = rememberLazyListState()
    val newestId = visible.firstOrNull()?.id
    LaunchedEffect(newestId) {
        if (newestId != null && listState.firstVisibleItemIndex <= 1) {
            listState.scrollToItem(0)
        }
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // SAF: the user decides where to write the file, so the app needs no storage permission.
    // (Import uses it too.)
    val harPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(HAR_MIME),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val har = viewModel.buildHar(visible.map { it.id })
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(har.toByteArray()) }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (logs.isEmpty() && !initialLoadComplete) {
            // A skeleton until Room's flow emits for the first time — otherwise the "No
            // requests yet" empty state would flash for a frame (the same pattern as the
            // environment list and routes).
            LogsSkeleton()
        } else if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(top = 44.dp), contentAlignment = Alignment.TopCenter) {
                EmptyState(
                    title = "No requests yet",
                    description = "Start this environment and send a request — every call it answers " +
                        "shows up here, with the reason it picked that response.",
                    primaryActionLabel = "Back to routes",
                    onPrimaryAction = onBackToRoutes,
                    icon = MockonomyIcons.Pulse,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                if (selectionMode) {
                    SelectionBar(
                        count = selectedIds.size,
                        onCreate = {
                            val ids = selectedIds.toList()
                            viewModel.createMocksFromLogs(ids) { created ->
                                selectionMode = false
                                selectedIds.clear()
                                Toast.makeText(
                                    context,
                                    "Created $created mock${if (created == 1) "" else "s"} in this environment.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onCancel = {
                            selectionMode = false
                            selectedIds.clear()
                        },
                        modifier = Modifier.padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                    )
                } else {
                    FilterRow(
                        query = query,
                        onQueryChange = { query = it },
                        statusClass = statusClass,
                        onStatusClassChange = { statusClass = it },
                        onClear = viewModel::clear,
                        onExport = { harPicker.launch(harFileName()) },
                        onSelect = { selectionMode = true },
                        modifier = Modifier.padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                    )
                }
                HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)

                if (visible.isEmpty()) {
                    Text(
                        text = "No request matches this filter.",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                        modifier = Modifier.padding(Spacing.s4),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.id }) { log ->
                            LogRow(
                                log = log,
                                selectionMode = selectionMode,
                                selected = log.id in selectedIds,
                                onClick = {
                                    if (selectionMode) {
                                        if (log.id in selectedIds) selectedIds.remove(log.id) else selectedIds.add(log.id)
                                    } else {
                                        onLogClick(log.id)
                                    }
                                },
                                onLongClick = {
                                    // The standard Android pattern: a long press enters
                                    // selection mode and the pressed item comes selected.
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedIds.clear()
                                    }
                                    if (log.id !in selectedIds) selectedIds.add(log.id)
                                },
                            )
                            HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
                        }
                        item {
                            Text(
                                text = "Logs stay on this device and are never uploaded.",
                                style = MockonomyTheme.typography.caption,
                                color = MockonomyTheme.colors.onSurfaceFaint,
                                modifier = Modifier.padding(horizontal = Spacing.s4, vertical = Spacing.s3),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    query: String,
    onQueryChange: (String) -> Unit,
    statusClass: StatusClass,
    onStatusClassChange: (StatusClass) -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusClass.entries.forEach { candidate ->
                FilterChip(
                    label = candidate.label,
                    selected = candidate == statusClass,
                    onClick = { onStatusClassChange(candidate) },
                )
            }
            Box(modifier = Modifier.weight(1f))
            // Export takes the entries **visible on screen** (filter = selection). Bulk
            // "create mock" needs row-by-row marking as well — hence the entry into a
            // selection mode here.
            Text(
                text = "Select",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier
                    .clickable(onClick = onSelect)
                    .padding(horizontal = Spacing.s2, vertical = 6.dp),
            )
            // Export is one of the screen's main goals — it is highlighted like a chip so it
            // does not blend in with the other two actions (Select is a secondary mode switch,
            // Clear a destructive red). primaryContainer + primaryText: the same role family
            // but more legible, giving a "primary action" feel.
            Text(
                text = "Export",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier
                    .clickable(onClick = onExport)
                    .background(MockonomyTheme.colors.primaryContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            // "Clear" is destructive but not a painful loss: the log is a ring buffer anyway
            // and the user can fill it again by sending requests. Asking for confirmation would
            // slow down a frequent action.
            Text(
                text = "Clear",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.error,
                modifier = Modifier
                    .clickable(onClick = onClear)
                    .padding(horizontal = Spacing.s2, vertical = 6.dp),
            )
        }
    }
}

/**
 * The selection mode action bar. It replaces the filter row — filtering during selection
 * would confuse the marks, and the two are not needed at the same time.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onCreate: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "Select requests" else "$count selected",
            style = MockonomyTheme.typography.label,
            color = MockonomyTheme.colors.onSurface,
        )
        Box(modifier = Modifier.weight(1f))
        Text(
            text = "Create mock",
            style = MockonomyTheme.typography.label,
            color = if (count == 0) MockonomyTheme.colors.onSurfaceFaint else MockonomyTheme.colors.primaryText,
            modifier = Modifier
                .clickable(enabled = count > 0, onClick = onCreate)
                .padding(horizontal = Spacing.s2, vertical = 6.dp),
        )
        Text(
            text = "Cancel",
            style = MockonomyTheme.typography.label,
            color = MockonomyTheme.colors.onSurfaceVariant,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = Spacing.s2, vertical = 6.dp),
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MockonomyTheme.typography.monoChip,
        color = if (selected) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurfaceVariant,
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) MockonomyTheme.colors.primary else MockonomyTheme.colors.outlineSubtle,
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    log: RequestLogSummary,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = Spacing.s1),
            )
        }
        Text(
            text = formatLogTime(log.timestampMs),
            style = MockonomyTheme.typography.monoMeta,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        MethodLabel(method = log.method, modifier = Modifier.padding(start = 10.dp))
        Text(
            text = log.path + if (log.query.isEmpty()) "" else "?${log.query}",
            style = MockonomyTheme.typography.monoPath,
            color = MockonomyTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        StatusCodeChip(statusCode = log.statusCode, modifier = Modifier.padding(start = Spacing.s2))
        Text(
            text = "${log.durationMs} ms",
            style = MockonomyTheme.typography.monoMeta,
            color = MockonomyTheme.colors.onSurfaceFaint,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

/**
 * The method in the log is the raw text the request sent — the mock server can receive
 * `BREW` too. Known methods use the same chip as the route list; an unknown one is not
 * painted with a made-up color but written as is.
 */
@Composable
private fun MethodLabel(method: String, modifier: Modifier = Modifier) {
    val known = remember(method) { HttpMethod.entries.firstOrNull { it.name == method } }
    if (known != null) {
        MethodChip(method = known, size = ChipSize.Small, modifier = modifier)
    } else {
        Text(
            text = method,
            style = MockonomyTheme.typography.monoChip,
            color = MockonomyTheme.colors.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

/**
 * Shown instead of `LogRow` until Room's flow emits for the first time. Sizes are close to
 * `LogRow`: time + method chip + path + status chip + duration. The same alpha pulse pattern
 * as the environment list and routes skeletons — the third copy, so moving it into a shared
 * component under `ui/components/` makes sense on the next touch.
 */
@Composable
private fun LogsSkeleton(rowCount: Int = 8) {
    val transition = rememberInfiniteTransition(label = "logs-skeleton-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logs-skeleton-alpha",
    )
    val baseColor = MockonomyTheme.colors.outlineSubtle
    Column(modifier = Modifier.fillMaxSize()) {
        repeat(rowCount) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 52.dp)
                    .padding(horizontal = Spacing.s3, vertical = Spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBar(width = 48.dp, height = 10.dp, color = baseColor.copy(alpha = alpha))
                Box(modifier = Modifier.size(width = 10.dp, height = 1.dp))
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 20.dp)
                        .background(baseColor.copy(alpha = alpha), shape = RoundedCornerShape(4.dp)),
                )
                Box(modifier = Modifier.size(width = 10.dp, height = 1.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .size(height = 12.dp, width = 0.dp)
                        .background(baseColor.copy(alpha = alpha * 0.85f), shape = RoundedCornerShape(4.dp)),
                )
                Box(modifier = Modifier.size(width = Spacing.s2, height = 1.dp))
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 20.dp)
                        .background(baseColor.copy(alpha = alpha), shape = RoundedCornerShape(4.dp)),
                )
                Box(modifier = Modifier.size(width = 7.dp, height = 1.dp))
                SkeletonBar(width = 40.dp, height = 10.dp, color = baseColor.copy(alpha = alpha * 0.75f))
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

/**
 * A `.har` extension and a timestamp with second resolution: exporting twice into the same
 * folder must not overwrite each other.
 */
private fun harFileName(): String = "mockonomy-${formatFileStamp(System.currentTimeMillis())}.har"

/**
 * A wildcard MIME type, not `application/json`: the system save screen "fixes" an extension
 * that does not match the MIME type and the file gets saved as `.har.json` (seen on a
 * device). With a wildcard the name stays as we gave it — HAR viewers recognize the file
 * by its content anyway.
 */
private const val HAR_MIME = "*/*"
