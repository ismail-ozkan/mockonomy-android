package com.mockonomy.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.openapi.ImportPreview
import com.mockonomy.ui.components.EmptyState
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.screens.importing.ImportUiState
import com.mockonomy.ui.screens.importing.ImportViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mock file import (OpenAPI and Mockoon). The format is picked from the file content inside
 * [ImportViewModel].
 *
 * The flow: pick a file → **preview** → pick a target → import. The preview step is not
 * decoration: it shows not only what the file provides but also **what it does not**,
 * because a silently skipped route would leave the user struggling with an incomplete mock
 * for hours.
 *
 * Thanks to SAF (`OpenDocument`) no storage permission is requested; the MIME type is
 * `* / *` because the MIME type of `.yaml` files on the device varies from provider to
 * provider — narrowing it would make the file impossible to pick.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onImported: (environmentId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { stream ->
                        checkNotNull(stream) { "The file could not be opened" }
                        // `readBytes()` reads without a limit, and the size reported by the
                        // provider cannot be trusted either. The limit is applied while reading.
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(READ_CHUNK)
                        while (true) {
                            val read = stream.read(chunk)
                            if (read < 0) break
                            buffer.write(chunk, 0, read)
                            check(buffer.size() <= MAX_FILE_BYTES) {
                                "The file is larger than ${MAX_FILE_BYTES / 1024 / 1024} MB"
                            }
                        }
                        buffer.toByteArray().decodeToString()
                    }
                }
            }
            text.fold(
                onSuccess = viewModel::parse,
                onFailure = { viewModel.failed(it.message ?: "The file could not be read") },
            )
        }
    }

    LaunchedEffect(state) {
        (state as? ImportUiState.Imported)?.let { onImported(it.environmentId) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.s2),
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
                text = "Import",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = Spacing.s2),
            )
        }

        when (val current = state) {
            is ImportUiState.Empty -> ChooseFile(onChoose = { picker.launch(arrayOf(ANY_MIME)) })

            is ImportUiState.Parsing -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MockonomyTheme.colors.primary)
            }

            is ImportUiState.Failed -> Failure(
                message = current.message,
                onRetry = {
                    viewModel.reset()
                    picker.launch(arrayOf(ANY_MIME))
                },
            )

            is ImportUiState.Ready -> Preview(
                preview = current.preview,
                targets = targets,
                targetId = targetId,
                onTargetChange = { targetId = it },
                onImport = { viewModel.import(current.preview, targetId) },
            )

            // Navigation happens in a [LaunchedEffect]; the screen stays empty meanwhile.
            is ImportUiState.Imported -> Unit
        }
    }
}

@Composable
private fun ChooseFile(onChoose: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(Spacing.s5),
        contentAlignment = Alignment.TopCenter,
    ) {
        EmptyState(
            title = "Import a mock file",
            description = "Pick an OpenAPI 3.x document (JSON or YAML) or a Mockoon export. " +
                "The format is detected automatically; every operation becomes a route.",
            primaryActionLabel = "Choose file",
            onPrimaryAction = onChoose,
            icon = MockonomyIcons.FileCode,
        )
    }
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.Warning,
            contentDescription = null,
            tint = MockonomyTheme.colors.error,
            size = 22.dp,
        )
        Spacer(Modifier.height(Spacing.s3))
        Text(
            text = "The file could not be imported",
            style = MockonomyTheme.typography.title,
            color = MockonomyTheme.colors.onSurface,
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = message,
            style = MockonomyTheme.typography.monoBody,
            color = MockonomyTheme.colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.s4))
        OutlinedButton(onClick = onRetry) {
            Text("Choose another file", style = MockonomyTheme.typography.label)
        }
    }
}

@Composable
private fun Preview(
    preview: ImportPreview,
    targets: List<EnvironmentSummary.Valid>,
    targetId: String?,
    onTargetChange: (String?) -> Unit,
    onImport: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4),
        ) {
            Text(
                text = preview.title.ifBlank { "Untitled API" },
                style = MockonomyTheme.typography.titleLarge,
                color = MockonomyTheme.colors.onSurface,
            )
            Text(
                text = buildString {
                    if (preview.version.isNotBlank()) append("v${preview.version} · ")
                    append("${preview.routes.size} routes · ${preview.responseCount} responses")
                    if (preview.prefix.isNotEmpty()) append(" · prefix ${preview.prefix}")
                },
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )

            Spacer(Modifier.height(Spacing.s4))
            SectionHeader(text = "Status codes")
            Spacer(Modifier.height(Spacing.s2))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s2)) {
                preview.statusCodes.take(MAX_STATUS_CHIPS).forEach { StatusCodeChip(statusCode = it) }
                if (preview.statusCodes.size > MAX_STATUS_CHIPS) {
                    Text(
                        text = "+${preview.statusCodes.size - MAX_STATUS_CHIPS}",
                        style = MockonomyTheme.typography.monoMeta,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                    )
                }
            }

            if (preview.issues.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s4))
                NotImportedSection(preview)
            }

            Spacer(Modifier.height(Spacing.s4))
            SectionHeader(text = "Import into")
            Spacer(Modifier.height(Spacing.s2))
            TargetRow(
                label = preview.title.ifBlank { "Imported API" },
                detail = "New environment" + if (preview.prefix.isEmpty()) "" else " · prefix ${preview.prefix}",
                selected = targetId == null,
                onClick = { onTargetChange(null) },
            )
            targets.forEach { target ->
                TargetRow(
                    label = target.environment.name,
                    detail = "Add ${preview.routes.size} routes to the existing " +
                        "${target.environment.routes.size}",
                    selected = targetId == target.id,
                    onClick = { onTargetChange(target.id) },
                )
            }
            Spacer(Modifier.height(Spacing.s4))
        }

        HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
        Button(
            onClick = onImport,
            enabled = preview.routes.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.s4)
                .defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
        ) {
            Text(
                text = if (preview.routes.isEmpty()) "Nothing to import" else "Import ${preview.routes.size} routes",
                style = MockonomyTheme.typography.label,
            )
        }
    }
}

/**
 * Issues are grouped by reason: if "default response skipped" applies to 178 operations it
 * should be one row, not 178 — once the list becomes unreadable, the report loses its point.
 */
@Composable
private fun NotImportedSection(preview: ImportPreview) {
    val groups = remember(preview) {
        preview.issues.groupingBy { it.reason }.eachCount().entries.sortedByDescending { it.value }
    }

    SectionHeader(
        text = "Not imported",
        trailing = {
            Text(
                text = preview.issues.size.toString(),
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
        },
    )
    Spacer(Modifier.height(Spacing.s2))
    groups.take(MAX_ISSUE_GROUPS).forEach { (reason, count) ->
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s1)) {
            Text(
                text = "×$count",
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurfaceFaint,
                modifier = Modifier.padding(end = Spacing.s2),
            )
            Text(
                text = reason,
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
            )
        }
    }
    if (groups.size > MAX_ISSUE_GROUPS) {
        Text(
            text = "+${groups.size - MAX_ISSUE_GROUPS} more kinds",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
    }
}

@Composable
private fun TargetRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s1)
            .border(
                width = 1.dp,
                color = if (selected) MockonomyTheme.colors.primary else MockonomyTheme.colors.outlineSubtle,
                shape = RoundedCornerShape(MockonomyMetrics.cardCornerRadius),
            )
            .clickable(onClick = onClick)
            .padding(Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MockonomyIcon(
            icon = if (selected) MockonomyIcons.CheckCircleFill else MockonomyIcons.CircleDashed,
            contentDescription = null,
            tint = if (selected) MockonomyTheme.colors.primary else MockonomyTheme.colors.onSurfaceFaint,
            size = 16.dp,
        )
        Column(modifier = Modifier.padding(start = Spacing.s3)) {
            Text(
                text = label,
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
        }
    }
}

/** The MIME type of YAML files varies by provider; narrowing it would prevent picking them. */
private const val ANY_MIME = "*/*"

/**
 * The file is read fully into memory and then parsed: peak usage is several times the file
 * size (UTF-8 bytes → UTF-16 String ≈ 2×, plus the parsed tree). The per-app heap on a
 * device is around 256 MB (e.g. Galaxy A72), so the limit exists to give a clear message
 * instead of a crash.
 *
 * It used to be 12 MB, based on the assumption that "the largest real schema is 6 MB
 * (Stripe)", and a user's file broke that assumption. 30 MB is still well below the heap.
 */
private const val MAX_FILE_BYTES = 30 * 1024 * 1024

private const val READ_CHUNK = 64 * 1024

private const val MAX_STATUS_CHIPS = 8
private const val MAX_ISSUE_GROUPS = 6
