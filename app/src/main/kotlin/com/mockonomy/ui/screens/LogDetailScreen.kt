package com.mockonomy.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.export.CurlCommand
import com.mockonomy.engine.JsonBody
import com.mockonomy.engine.PathParameterization
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.ui.components.EmptyState
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.screens.logs.DedupMode
import com.mockonomy.ui.screens.logs.LogDetailViewModel
import com.mockonomy.ui.screens.logs.MockTarget
import com.mockonomy.ui.screens.routes.httpMethodOf
import com.mockonomy.ui.screens.routes.routeSignature
import com.mockonomy.ui.screens.logs.formatLogTimestamp
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Log detail.
 *
 * Two tabs (request · response).
 */
@Composable
fun LogDetailScreen(
    onBack: () -> Unit,
    onCreateMock: (environmentId: String, routeId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogDetailViewModel = hiltViewModel(),
) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DetailTab.Request) }

    Column(modifier = modifier.fillMaxSize()) {
        val current = log
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
                    text = current?.let { "${it.method} ${it.path}" } ?: "Request",
                    style = MockonomyTheme.typography.title,
                    color = MockonomyTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = current?.let { formatLogTimestamp(it.timestampMs) } ?: "",
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                )
            }
            if (current != null) {
                StatusCodeChip(statusCode = current.statusCode)
                Text(
                    text = "${current.durationMs} ms",
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.padding(start = 7.dp),
                )
            }
        }
        HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)

        when {
            current == null && loaded -> MissingLogPlaceholder(onBack)
            current == null -> Unit
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(top = Spacing.s3, bottom = Spacing.s5),
            ) {
                CurlActions(log = current)

                CreateMockAction(
                    log = current,
                    targets = targets,
                    currentEnvironmentId = viewModel.currentEnvironmentId,
                    onCreate = { targetId, path, dedup ->
                        viewModel.createMock(targetId, path, dedup, onCreateMock)
                    },
                )

                Row(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s4)) {
                    DetailTab.entries.forEach { candidate ->
                        TabButton(
                            label = candidate.label,
                            selected = candidate == tab,
                            onClick = { tab = candidate },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                when (tab) {
                    DetailTab.Request -> RequestPane(current)
                    DetailTab.Response -> ResponsePane(current)
                }
            }
        }
    }
}

private enum class DetailTab(val label: String) {
    Request("Request"),
    Response("Response"),
}

/**
 * cURL export.
 *
 * The command is not shown on screen; it goes straight to the clipboard or the share sheet:
 * reading a 10-line command on a phone helps nobody, moving it to the terminal on your
 * computer does.
 *
 * For an entry with a truncated body the command does **not** send the same request; so
 * this is said before copying, not after.
 */
@Composable
private fun CurlActions(log: RequestLog) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s4)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("curl", CurlCommand.of(log))),
                        )
                        copied = true
                    }
                },
                modifier = Modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
            ) {
                MockonomyIcon(
                    icon = MockonomyIcons.Copy,
                    contentDescription = null,
                    tint = MockonomyTheme.colors.onSurfaceVariant,
                    size = 13.dp,
                )
                Text(
                    text = if (copied) "Copied" else "Copy as cURL",
                    style = MockonomyTheme.typography.label,
                    modifier = Modifier.padding(start = Spacing.s2),
                )
            }
            OutlinedButton(
                onClick = { context.shareText(CurlCommand.of(log), "cURL command") },
                modifier = Modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
            ) {
                Text(text = "Share", style = MockonomyTheme.typography.label)
            }
        }
        if (log.requestBodyTruncated) {
            Text(
                text = "The request body was truncated in the log, so this command sends a " +
                    "shorter body than the original request.",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.warning,
                modifier = Modifier.padding(top = Spacing.s2),
            )
        }
    }
}

/**
 * "Create mock from this request" (record & replay).
 *
 * Turns a real response that was proxied and logged into a persistent mock: a target
 * environment is picked (this log's environment by default), the route is added and the
 * user is taken to the edit screen to see the result right away. If there is no valid
 * environment (all corrupt) the button stays disabled.
 */
@Composable
private fun CreateMockAction(
    log: RequestLog,
    targets: List<MockTarget>,
    currentEnvironmentId: String,
    onCreate: (targetEnvironmentId: String, path: String, dedup: DedupMode) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { showDialog = true },
        enabled = targets.isNotEmpty(),
        modifier = Modifier
            .padding(bottom = Spacing.s4)
            .defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
    ) {
        Text(text = "Create mock from this", style = MockonomyTheme.typography.label)
    }

    if (showDialog) {
        CreateMockDialog(
            log = log,
            targets = targets,
            currentEnvironmentId = currentEnvironmentId,
            onDismiss = { showDialog = false },
            onConfirm = { targetId, path, dedup ->
                showDialog = false
                onCreate(targetId, path, dedup)
            },
        )
    }
}

@Composable
private fun CreateMockDialog(
    log: RequestLog,
    targets: List<MockTarget>,
    currentEnvironmentId: String,
    onDismiss: () -> Unit,
    onConfirm: (targetEnvironmentId: String, path: String, dedup: DedupMode) -> Unit,
) {
    // Default target: the log's own environment; if it is not in the list (deleted), the
    // first valid one.
    val initial = remember(targets) {
        targets.firstOrNull { it.id == currentEnvironmentId }?.id ?: targets.first().id
    }
    var selected by remember { mutableStateOf(initial) }

    // Suggest parameterizing the concrete path; when there is a suggestion it is on by default.
    val suggestion = remember(log.path) { PathParameterization.suggest(log.path) }
    val hasSuggestion = suggestion.parameters.isNotEmpty()
    var parameterize by remember { mutableStateOf(hasSuggestion) }
    val effectivePath = if (parameterize && hasSuggestion) suggestion.suggestedPath else log.path

    // Deduplication: if the target already has the same method+path, offer a choice.
    val selectedTarget = targets.first { it.id == selected }
    val conflict = routeSignature(httpMethodOf(log.method), effectivePath) in selectedTarget.routeKeys
    var dedup by remember { mutableStateOf(DedupMode.AddResponse) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create mock") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${log.method} $effectivePath → ${log.statusCode}",
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.padding(bottom = Spacing.s3),
                )

                if (hasSuggestion) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { parameterize = !parameterize }
                            .padding(vertical = Spacing.s1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = parameterize, onCheckedChange = { parameterize = it })
                        Column(modifier = Modifier.padding(start = Spacing.s2)) {
                            Text(
                                text = "Parameterize path",
                                style = MockonomyTheme.typography.body,
                                color = MockonomyTheme.colors.onSurface,
                            )
                            Text(
                                text = suggestion.suggestedPath,
                                style = MockonomyTheme.typography.monoMeta,
                                color = MockonomyTheme.colors.onSurfaceFaint,
                            )
                        }
                    }
                }

                if (targets.size > 1) {
                    Text(
                        text = "Add to environment",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s1),
                    )
                    targets.forEach { target ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = target.id }
                                .padding(vertical = Spacing.s1),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = target.id == selected,
                                onClick = { selected = target.id },
                            )
                            Text(
                                text = target.name.ifBlank { "Untitled" },
                                style = MockonomyTheme.typography.body,
                                color = MockonomyTheme.colors.onSurface,
                                modifier = Modifier.padding(start = Spacing.s2),
                            )
                        }
                    }
                }

                if (conflict) {
                    Text(
                        text = "This route already exists here",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.warning,
                        modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s1),
                    )
                    DedupOption(DedupMode.AddResponse, "Add as a new response", dedup) { dedup = it }
                    DedupOption(DedupMode.UpdateResponse, "Update the default response", dedup) { dedup = it }
                    DedupOption(DedupMode.NewRoute, "Create a separate route anyway", dedup) { dedup = it }
                }

                if (log.responseBody.isEmpty()) {
                    Text(
                        text = "This response has no stored body, so the mock will return an empty body.",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.warning,
                        modifier = Modifier.padding(top = Spacing.s2),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected, effectivePath, if (conflict) dedup else DedupMode.NewRoute) },
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DedupOption(mode: DedupMode, label: String, selected: DedupMode, onSelect: (DedupMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) }
            .padding(vertical = Spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = { onSelect(mode) })
        Text(
            text = label,
            style = MockonomyTheme.typography.body,
            color = MockonomyTheme.colors.onSurface,
            modifier = Modifier.padding(start = Spacing.s2),
        )
    }
}

/** The share sheet — which app it goes to is the user's decision. */
internal fun Context.shareText(text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_TITLE, title)
    }
    startActivity(Intent.createChooser(intent, title))
}

@Composable
private fun RequestPane(log: RequestLog) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3)) {
        MetaRow(label = "URL", value = log.path + if (log.query.isEmpty()) "" else "?${log.query}")
        HeaderList(log.requestHeaders)
        BodyBlock(
            body = log.requestBody.orEmpty(),
            truncated = log.requestBodyTruncated,
            emptyText = "No request body.",
        )
    }
}

@Composable
private fun ResponsePane(log: RequestLog) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3)) {
        MetaRow(label = "STATUS", value = "${log.statusCode} · ${log.durationMs} ms")
        HeaderList(log.responseHeaders)
        BodyBlock(
            body = log.responseBody,
            truncated = log.responseBodyTruncated,
            emptyText = "Empty response body.",
            // "Grab the whole response body" is a frequent need while debugging — one tap.
            // Not needed on the request side; onCopy is null there.
            onCopy = {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText("response body", log.responseBody)),
                    )
                }
            },
        )
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3)) {
        Text(
            text = label,
            style = MockonomyTheme.typography.overline,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        Text(
            text = value,
            style = MockonomyTheme.typography.monoBody,
            color = MockonomyTheme.colors.onSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun HeaderList(headers: List<HeaderPair>) {
    SectionHeader(text = "Headers", modifier = Modifier.padding(bottom = Spacing.s2))
    if (headers.isEmpty()) {
        Text(
            text = "None.",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        return
    }
    headers.forEach { header ->
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
            Text(
                text = header.key,
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurfaceVariant,
            )
            Text(
                text = header.value,
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * The body scrolls horizontally and lines do not wrap: in JSON indentation carries
 * information, and wrapped lines make it unreadable.
 */
@Composable
private fun BodyBlock(body: String, truncated: Boolean, emptyText: String, onCopy: (() -> Unit)? = null) {
    SectionHeader(
        text = "Body",
        modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s2),
        trailing = {
            if (truncated) {
                Text(
                    text = "truncated",
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.warning,
                )
            }
        },
    )
    if (body.isEmpty()) {
        Text(
            text = emptyText,
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        return
    }
    // Shown indented if it is JSON; as is if it cannot be parsed (plain text, truncated
    // body). What gets copied is always the raw body.
    val shown = remember(body) { JsonBody.format(body) ?: body }
    // The copy icon is overlaid on the Box's top-right corner; the body scroll lives in the
    // inner Box and the icon in the outer Box so the icon does not move with scrolling.
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = BODY_MAX_HEIGHT)
                .background(MockonomyTheme.colors.surfaceVariant, RoundedCornerShape(8.dp))
                .border(1.dp, MockonomyTheme.colors.outlineSubtle, RoundedCornerShape(8.dp))
                .padding(Spacing.s3)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = shown,
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.onSurface,
            )
        }
        if (onCopy != null) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp),
            ) {
                MockonomyIcon(
                    icon = MockonomyIcons.Copy,
                    contentDescription = "Copy body",
                    tint = MockonomyTheme.colors.onSurfaceVariant,
                    size = 16.dp,
                )
            }
        }
    }
    if (truncated) {
        Text(
            text = "Only the first 64 KB was stored.",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceFaint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** A long body must not push the screen; it scrolls inside the box. Same upper bound as the editor. */
private val BODY_MAX_HEIGHT = 420.dp

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MockonomyTheme.typography.label,
            color = if (selected) MockonomyTheme.colors.onSurface else MockonomyTheme.colors.onSurfaceVariant,
            modifier = Modifier.defaultMinSize(minHeight = 38.dp).padding(top = 10.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .background(if (selected) MockonomyTheme.colors.primary else Color.Transparent)
                .defaultMinSize(minHeight = 2.dp),
        )
    }
}

/** The entry may have been pruned: the oldest ones are deleted when the ring buffer fills up. */
@Composable
private fun MissingLogPlaceholder(onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            title = "This request is gone",
            description = "The log keeps a limited number of requests; older ones are dropped.",
            primaryActionLabel = "Back to logs",
            onPrimaryAction = onBack,
            icon = MockonomyIcons.Pulse,
        )
    }
}
