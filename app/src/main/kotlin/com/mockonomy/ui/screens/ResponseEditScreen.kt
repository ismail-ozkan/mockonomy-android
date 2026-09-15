package com.mockonomy.ui.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.engine.JsonBody
import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.ui.components.AddKeyValueRow
import com.mockonomy.ui.components.dashedBorder
import com.mockonomy.ui.components.KeyValueRow
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.screens.response.LatencySheet
import com.mockonomy.ui.screens.response.HIGHLIGHT_LIMIT
import com.mockonomy.ui.screens.response.rememberBodyHighlight
import com.mockonomy.ui.screens.response.syntaxTransformation
import com.mockonomy.ui.screens.response.ResponseEditViewModel
import com.mockonomy.ui.screens.response.RulesSection
import com.mockonomy.ui.screens.response.StatusCodeSheet
import com.mockonomy.ui.screens.response.reasonPhraseOf
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import com.mockonomy.ui.theme.statusChipColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Response editing and the body editor.
 *
 * Progressive disclosure: status · latency · body · headers on top; `label` and where the
 * body is stored under Advanced.
 *
 * The real investment is not in the editor but in **the ways to fill it**: nobody types a
 * 200-line JSON on a phone keyboard. That is why paste from clipboard and load from file
 * sit right above the body, not hidden.
 */
@Composable
fun ResponseEditScreen(
    environmentId: String,
    routeId: String,
    responseId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ResponseEditViewModel = hiltViewModel(),
) {
    val response by viewModel.response.collectAsStateWithLifecycle()
    val body by viewModel.body.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val routeSummary by viewModel.routeSummary.collectAsStateWithLifecycle()
    val states by viewModel.states.collectAsStateWithLifecycle()
    val current = response

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var problem by remember { mutableStateOf<String?>(null) }
    var statusSheetVisible by rememberSaveable { mutableStateOf(false) }
    var latencySheetVisible by rememberSaveable { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text != null) {
                // If the JSON from the file is already formatted this changes nothing; if not,
                // there is no need to ask — this is what the user wanted.
                viewModel.replaceBody(JsonBody.format(text) ?: text)
                problem = null
            }
        }
    }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current?.label?.ifBlank { null } ?: "Response",
                    style = MockonomyTheme.typography.title,
                    color = MockonomyTheme.colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = routeSummary,
                    style = MockonomyTheme.typography.monoMeta,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (current == null) return@Column

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(top = Spacing.s3, bottom = Spacing.s5),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s4),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                FieldCard(
                    label = "STATUS",
                    value = current.statusCode.toString(),
                    valueColor = current.statusCode.statusChipColors().foreground,
                    hint = reasonPhraseOf(current.statusCode),
                    onClick = { statusSheetVisible = true },
                    modifier = Modifier.weight(1f),
                )
                FieldCard(
                    label = "LATENCY",
                    value = current.latencyMs.toString(),
                    valueColor = MockonomyTheme.colors.onSurface,
                    hint = "ms",
                    onClick = { latencySheetVisible = true },
                    modifier = Modifier.weight(1f),
                )
            }

            // The label sits directly above Body. The supporting text was removed — the box
            // alone is enough.
            OutlinedTextField(
                value = current.label,
                onValueChange = { text -> viewModel.updateResponse { it.copy(label = text) } },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3),
            )

            SectionHeader(
                text = "Body",
                modifier = Modifier.padding(bottom = Spacing.s2),
                trailing = {
                    Text(
                        text = bodyMeta(body),
                        style = MockonomyTheme.typography.monoMeta,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                    )
                },
            )

            BodySourceChips(
                onPaste = {
                    scope.launch {
                        val pasted = clipboard.readPlainText(context)
                        if (pasted.isNullOrEmpty()) return@launch
                        viewModel.replaceBody(JsonBody.format(pasted) ?: pasted)
                        problem = null
                    }
                },
                onLoadFile = { filePicker.launch(arrayOf(JSON_MIME, ANY_MIME)) },
                modifier = Modifier.padding(bottom = 9.dp),
            )

            if (current.bodyType == BodyType.FILE) {
                LargeBodyNotice()
            }

            if (body.isEmpty()) {
                EmptyBodyFrame(modifier = Modifier.padding(bottom = Spacing.s4))
            }

            BodyEditor(
                body = body,
                problem = problem,
                onBodyChange = {
                    viewModel.updateBody(it)
                    problem = null
                },
                onFormat = {
                    val formatted = JsonBody.format(body)
                    if (formatted == null) {
                        problem = "Can't format — this isn't valid JSON."
                    } else {
                        viewModel.updateBody(formatted)
                        problem = null
                    }
                },
                onClear = {
                    viewModel.updateBody("")
                    problem = null
                },
                modifier = Modifier.padding(bottom = Spacing.s4),
            )

            RulesSection(
                response = current,
                onUpdate = { transform -> viewModel.updateResponse(transform) },
                modifier = Modifier.padding(bottom = Spacing.s4),
            )

            // When a state machine is defined the STATE CONFIG group sits above Headers.
            // Otherwise it is not shown at all.
            if (states.isNotEmpty()) {
                SectionHeader(text = "STATE CONFIG", modifier = Modifier.padding(bottom = Spacing.s2))
                StateDropdown(
                    title = "Only serve in state",
                    noneLabel = "Any state",
                    value = current.whenState,
                    states = states,
                    onSelect = { state -> viewModel.updateResponse { it.copy(whenState = state) } },
                )
                StateDropdown(
                    title = "Change state after serving",
                    noneLabel = "No change",
                    value = current.setState,
                    states = states,
                    onSelect = { state -> viewModel.updateResponse { it.copy(setState = state) } },
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = Spacing.s3))
            }

            SectionHeader(text = "Headers", modifier = Modifier.padding(bottom = Spacing.s2))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MockonomyTheme.colors.outlineSubtle,
                        shape = RoundedCornerShape(MockonomyMetrics.keyValueRowCornerRadius),
                    ),
            ) {
                current.headers.forEachIndexed { index, header ->
                    KeyValueRow(
                        key = header.key,
                        value = header.value,
                        onKeyChange = { newKey ->
                            viewModel.updateResponse { r -> r.copy(headers = r.headers.replaceAt(index) { it.copy(key = newKey) }) }
                        },
                        onValueChange = { newValue ->
                            viewModel.updateResponse { r -> r.copy(headers = r.headers.replaceAt(index) { it.copy(value = newValue) }) }
                        },
                        onRemove = {
                            viewModel.updateResponse { r -> r.copy(headers = r.headers.filterIndexed { i, _ -> i != index }) }
                        },
                    )
                    HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
                }

                AddKeyValueRow(
                    onAdd = { viewModel.updateResponse { it.copy(headers = it.headers + HeaderPair("", "")) } },
                )
            }

        }
    }

    if (statusSheetVisible && current != null) {
        StatusCodeSheet(
            current = current.statusCode,
            onDismiss = { statusSheetVisible = false },
            onSelect = { code ->
                viewModel.updateResponse { it.copy(statusCode = code) }
                statusSheetVisible = false
            },
        )
    }

    if (latencySheetVisible && current != null) {
        LatencySheet(
            currentMs = current.latencyMs,
            onDismiss = { latencySheetVisible = false },
            onSelect = { ms ->
                viewModel.updateResponse { it.copy(latencyMs = ms) }
                latencySheetVisible = false
            },
        )
    }
}

/**
 * whenState / setState picker. A "(none)" option + the defined states. If the current
 * value is not in the list (orphaned after a rename/delete) it is still shown so the user
 * can see and fix it.
 */
@Composable
private fun StateDropdown(
    title: String,
    noneLabel: String,
    value: String?,
    states: List<String>,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val orphan = value != null && value !in states
    val options = if (orphan) states + value!! else states

    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.s2)) {
        Text(
            text = title,
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.s1)
                    .border(1.dp, MockonomyTheme.colors.outline, RoundedCornerShape(8.dp))
                    .clickable { expanded = true }
                    .padding(horizontal = Spacing.s3, vertical = Spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = value ?: noneLabel,
                    style = MockonomyTheme.typography.monoBody,
                    color = if (value == null) MockonomyTheme.colors.onSurfaceFaint else MockonomyTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (orphan) {
                    MockonomyIcon(
                        icon = MockonomyIcons.Warning,
                        contentDescription = "Unknown state",
                        tint = MockonomyTheme.colors.warning,
                        size = 14.dp,
                    )
                }
                MockonomyIcon(
                    icon = MockonomyIcons.CaretDown,
                    contentDescription = null,
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                    size = 12.dp,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = MockonomyTheme.colors.surface,
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = noneLabel,
                            style = MockonomyTheme.typography.monoBody,
                            color = MockonomyTheme.colors.onSurfaceFaint,
                        )
                    },
                    onClick = { onSelect(null); expanded = false },
                )
                options.forEach { state ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = state,
                                style = MockonomyTheme.typography.monoBody,
                                color = MockonomyTheme.colors.onSurface,
                            )
                        },
                        onClick = { onSelect(state); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * A 60dp tappable field card: overline label, mono value, short description and a caret.
 * It opens a sheet instead of the keyboard — typing numbers on a phone is the slow way.
 */
@Composable
private fun FieldCard(
    label: String,
    value: String,
    valueColor: Color,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = 60.dp)
            .background(MockonomyTheme.colors.surfaceVariant, RoundedCornerShape(10.dp))
            .border(1.dp, MockonomyTheme.colors.outlineSubtle, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Text(
            text = label,
            style = MockonomyTheme.typography.overline,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = value, style = MockonomyTheme.typography.monoPath, color = valueColor)
            Text(
                text = hint,
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 7.dp),
            )
            MockonomyIcon(
                icon = MockonomyIcons.CaretDown,
                contentDescription = null,
                tint = MockonomyTheme.colors.onSurfaceFaint,
                size = 12.dp,
            )
        }
    }
}

/** Ways to fill the body. Two future sources stay disabled with a dashed frame. */
@Composable
private fun BodySourceChips(onPaste: () -> Unit, onLoadFile: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        OutlinedButton(
            onClick = onPaste,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MockonomyTheme.colors.primary),
            modifier = Modifier.defaultMinSize(minHeight = 40.dp),
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.ClipboardText,
                contentDescription = null,
                tint = MockonomyTheme.colors.primaryText,
                size = 14.dp,
            )
            Text(
                text = "Paste & format",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.primaryText,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

        OutlinedButton(
            onClick = onLoadFile,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MockonomyTheme.colors.outline),
            modifier = Modifier.defaultMinSize(minHeight = 40.dp),
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.FileArrowUp,
                contentDescription = null,
                tint = MockonomyTheme.colors.onSurface,
                size = 14.dp,
            )
            Text(
                text = "From file",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }

    }
}

@Composable
private fun EmptyBodyFrame(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MockonomyTheme.colors.surfaceSunken, RoundedCornerShape(10.dp))
            .dashedBorder(MockonomyTheme.colors.outline, cornerRadius = 10.dp)
            .padding(horizontal = 18.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MockonomyIcon(
            icon = MockonomyIcons.BracketsCurly,
            contentDescription = null,
            tint = MockonomyTheme.colors.onSurfaceFaint,
            size = 22.dp,
        )
        Text(
            text = "Empty body. Paste from the clipboard, load a file, or type below — " +
                "typing is the slow path on purpose.",
            style = MockonomyTheme.typography.body,
            color = MockonomyTheme.colors.onSurfaceFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

/**
 * An editor with line numbers. Scrolling is horizontal: without turning line wrapping off
 * (`softWrap = false`) the numbers would lose their alignment with the text — in JSON long
 * lines are the rule, not the exception.
 *
 * Validation is always visible in the footer; there is no separate "Validate" button,
 * because the answer is already known on every keystroke. Invalid JSON is still saved: the
 * user may want to serve a deliberately broken body.
 *
 * The text is highlighted, the line with an error is marked in the gutter, and `{{ ... }}`
 * expressions the engine does not recognize are shown in the warning color.
 */
@Composable
private fun BodyEditor(
    body: String,
    problem: String?,
    onBodyChange: (String) -> Unit,
    onFormat: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val huge = body.length > HIGHLIGHT_LIMIT
    val lineCount = if (huge) 0 else body.count { it == '\n' } + 1
    val validation = remember(body) {
        // Parsing a 1 MB body on every keystroke is another cause of freezing.
        if (body.isBlank() || huge) null else JsonBody.validate(body)
    }
    val highlight by rememberBodyHighlight(body)
    val verdict = when {
        huge -> "READ-ONLY · ${body.length / BYTES_PER_KB} KB" to MockonomyTheme.colors.warning
        body.isBlank() -> "EMPTY" to MockonomyTheme.colors.onSurfaceFaint
        validation == null -> "JSON VALID" to MockonomyTheme.colors.success
        else -> "LINE ${validation.line}: ${validation.message}" to MockonomyTheme.colors.warning
    }
    val editorStyle = MockonomyTheme.typography.monoBody

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MockonomyTheme.colors.outlineSubtle, RoundedCornerShape(10.dp)),
        ) {
            if (huge) {
                HugeBodyPreview(body, editorStyle)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BODY_MIN_HEIGHT, max = BODY_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .background(MockonomyTheme.colors.surfaceSunken),
                ) {
                    Column(
                        modifier = Modifier
                            .background(MockonomyTheme.colors.surfaceVariant)
                            .width(GUTTER_WIDTH)
                            .padding(top = 10.dp, bottom = 12.dp, end = Spacing.s2),
                    ) {
                        for (line in 1..lineCount) {
                            // The error's line is marked in the gutter; the footer message
                            // answered "which line" but did not draw the eye there.
                            val faulty = validation != null && validation.line == line
                            Text(
                                text = line.toString(),
                                style = editorStyle,
                                color = if (faulty) {
                                    MockonomyTheme.colors.error
                                } else {
                                    MockonomyTheme.colors.onSurfaceFaint
                                },
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // `horizontalScroll` measures the content with infinite width, so lines
                    // never wrap and the gutter numbers keep their alignment.
                    BasicTextField(
                        value = body,
                        onValueChange = onBodyChange,
                        cursorBrush = SolidColor(MockonomyTheme.colors.primaryText),
                        textStyle = editorStyle.copy(color = MockonomyTheme.colors.onSurface),
                        visualTransformation = syntaxTransformation(highlight, MockonomyTheme.syntax),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = 12.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MockonomyTheme.colors.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = problem ?: verdict.first,
                    style = MockonomyTheme.typography.monoMeta,
                    color = if (problem != null) MockonomyTheme.colors.error else verdict.second,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!huge) {
                    TextButton(onClick = onFormat) {
                        Text(
                            "FORMAT",
                            style = MockonomyTheme.typography.monoMeta,
                            color = MockonomyTheme.colors.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onClear) {
                    Text("CLEAR", style = MockonomyTheme.typography.monoMeta, color = MockonomyTheme.colors.onSurfaceVariant)
                }
            }
        }

        if (huge) {
            EditorNotice(
                text = "This body is too large to edit on a phone, so it is shown read-only and " +
                    "without colors. Replace it from a file or the clipboard — it is served in full.",
                color = MockonomyTheme.colors.warning,
            )
        }

        // An unrecognized expression is not deleted; it is served as is. This is the only
        // place where the user learns that *beforehand*.
        highlight.unknownTemplates.firstOrNull()?.let { unknown ->
            val expression = body.substring(
                unknown.start.coerceAtMost(body.length),
                unknown.end.coerceAtMost(body.length),
            )
            EditorNotice(
                text = "$expression isn't a known expression — it will be served exactly as written.",
                color = MockonomyTheme.colors.warning,
            )
        }
    }
}

/**
 * A body over the threshold falls back to a read-only preview.
 *
 * The reason was measured on a device: opening a 973 KB body in the editor blocked the main
 * thread for **2715 frames** (~45 s). The cost is not highlighting (that already turns off at
 * the threshold) but measuring a 9000-line gutter and a single non-wrapping text field.
 * Virtualization is not possible with [BasicTextField]; turning editing off is an honest
 * solution compared to a frozen editor.
 *
 * The body **keeps being served in full** — only the part shown on screen is cut.
 */
@Composable
private fun HugeBodyPreview(body: String, style: androidx.compose.ui.text.TextStyle) {
    Text(
        text = body.take(PREVIEW_CHARS),
        style = style,
        color = MockonomyTheme.colors.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BODY_MIN_HEIGHT, max = BODY_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .background(MockonomyTheme.colors.surfaceSunken)
            .padding(10.dp),
    )
}

@Composable
private fun EditorNotice(text: String, color: Color) {
    Text(
        text = text,
        style = MockonomyTheme.typography.caption,
        color = color,
        modifier = Modifier.padding(top = Spacing.s2),
    )
}

/**
 * `bodyType` is not offered as an option: the threshold is an implementation detail, not a
 * decision for the user. But knowing where the body lives explains why the editor suggests
 * the "replace from file" flow.
 */
@Composable
private fun LargeBodyNotice() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s2),
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
            text = "This body is large and lives in its own file. Replacing it from a file is faster than editing it here.",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceVariant,
        )
    }
}

/** "12 lines · 1.4 KB" — a single line telling how large the body has grown. */
private fun bodyMeta(body: String): String {
    if (body.isEmpty()) return "empty"
    val lines = body.count { it == '\n' } + 1
    val bytes = body.toByteArray().size
    val size = if (bytes < BYTES_PER_KB) "$bytes B" else "${bytes / BYTES_PER_KB} KB"
    return "$lines lines · $size"
}

private inline fun List<HeaderPair>.replaceAt(index: Int, transform: (HeaderPair) -> HeaderPair): List<HeaderPair> =
    mapIndexed { i, header -> if (i == index) transform(header) else header }

/**
 * The text on the clipboard. `ClipEntry` carries a platform-specific `ClipData`; turning it
 * into plain text needs a Context (e.g. to resolve a URI held by the clipboard).
 */
private suspend fun Clipboard.readPlainText(context: Context): String? {
    val clip = getClipEntry()?.clipData ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

private const val JSON_MIME = "application/json"

/** Some file providers report a `.json` file as `text/plain`. */
private const val ANY_MIME = "text/*"

private const val BYTES_PER_KB = 1024

/** Number of characters shown in the preview — a few screens' worth is enough. */
private const val PREVIEW_CHARS = 4_000

private val BODY_MIN_HEIGHT = 180.dp
private val BODY_MAX_HEIGHT = 420.dp
private val GUTTER_WIDTH = 38.dp
