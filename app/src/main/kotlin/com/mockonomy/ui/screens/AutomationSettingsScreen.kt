package com.mockonomy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.screens.settings.AutomationSettingsViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Automation (admin API) settings screen.
 *
 * By the security model the admin API is **off** by default and can only be turned on
 * here. The warning text says plainly that every app on the device can reach `127.0.0.1` —
 * the decision must be a conscious one.
 */
@Composable
fun AutomationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AutomationSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

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
                text = "Automation",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = Spacing.s2),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4),
        ) {
            Text(
                text = "Drive the mock server from your test suite over HTTP. Your test picks which " +
                    "environment is active, forces a specific response, sets the state machine, resets " +
                    "counters, then reads the request log to assert what the app under test sent.",
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s2),
            )
            Text(
                text = "Every call lives under http://127.0.0.1:<port>/__mockonomy — plain HTTP, no " +
                    "SDK required. Send them with curl, OkHttp, URLSession, or your existing test client.",
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s2),
            )
            Text(
                text = "Setup (before a test)",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s1),
            )
            Text(
                text = "• POST /environments/{key}/activate  — swap the running environment\n" +
                    "• PUT  /routes/{key}/response  {\"key\":\"not-found\"}  — pin a route to one response\n" +
                    "• POST /state  {\"state\":\"logged_in\"}  — jump the state machine\n" +
                    "• POST /reset  — reset counters, active state and pins\n" +
                    "• DELETE /logs  — clear the request log",
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s2),
            )
            Text(
                text = "Verification (after a test)",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s1),
            )
            Text(
                text = "• GET /logs?method=POST&path=/orders&limit=50  — recorded requests, newest first\n" +
                    "• GET /health  — server status, active environment, state, port\n" +
                    "• GET /environments  — list environments with an active flag",
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s2),
            )
            Text(
                text = "How it ties back to routes",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s1),
            )
            Text(
                text = "Give each Route and each Response a readable key in the editor (e.g. " +
                    "\"get-user\", \"not-found\"). The API uses those keys instead of UUIDs — a test can " +
                    "call PUT /routes/get-user/response {\"key\":\"not-found\"} to force a 404 for the " +
                    "next call, run the app, then GET /logs to check what actually reached the server. " +
                    "Every test starts from a known state without touching the UI.",
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s3),
            )

            ToggleRow(
                label = "Enable automation API",
                supporting = "Off by default. Any app on this device can reach 127.0.0.1 — only turn " +
                    "this on when you need it.",
                checked = settings.enabled,
                onCheckedChange = viewModel::setEnabled,
            )

            if (settings.enabled) {
                HorizontalDivider(
                    color = MockonomyTheme.colors.outlineSubtle,
                    modifier = Modifier.padding(vertical = Spacing.s3),
                )

                SectionHeader(
                    text = if (running.size > 1) "Base URLs" else "Base URL",
                    modifier = Modifier.padding(bottom = Spacing.s2),
                )
                // Each server serves its own `/__mockonomy` endpoints on its own port; with
                // multiple servers the port is already the target, so all of them are listed.
                if (running.isNotEmpty()) {
                    running.forEach { server ->
                        if (running.size > 1) {
                            Text(
                                text = server.environmentName,
                                style = MockonomyTheme.typography.caption,
                                color = MockonomyTheme.colors.onSurfaceFaint,
                            )
                        }
                        UrlBlock(
                            url = "http://127.0.0.1:${server.port}/__mockonomy",
                            onCopy = { scope.launch { clipboard.copy(it) } },
                        )
                    }
                } else {
                    Text(
                        text = "Start a server to see its automation address.",
                        style = MockonomyTheme.typography.caption,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                        modifier = Modifier.padding(bottom = Spacing.s2),
                    )
                }

                SectionHeader(
                    text = "Security",
                    modifier = Modifier.padding(top = Spacing.s4, bottom = Spacing.s2),
                )

                ToggleRow(
                    label = "Redact sensitive headers in reads",
                    supporting = "Masks Authorization, Cookie and similar values in GET /logs output.",
                    checked = settings.redactReads,
                    onCheckedChange = viewModel::setRedactReads,
                )

                Text(
                    text = "Shared token (optional)",
                    style = MockonomyTheme.typography.label,
                    color = MockonomyTheme.colors.onSurface,
                    modifier = Modifier.padding(top = Spacing.s3, bottom = Spacing.s1),
                )
                Text(
                    text = "When set, every request must send it as the X-Mockonomy-Token header.",
                    style = MockonomyTheme.typography.caption,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.padding(bottom = Spacing.s2),
                )
                OutlinedTextField(
                    value = settings.token.orEmpty(),
                    onValueChange = { viewModel.setToken(it) },
                    singleLine = true,
                    placeholder = { Text("No token — API open on loopback") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s2),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                ) {
                    OutlinedButton(onClick = { viewModel.generateToken() }) {
                        Text("Generate", style = MockonomyTheme.typography.label)
                    }
                    if (settings.token != null) {
                        TextButton(onClick = { viewModel.setToken(null) }) {
                            Text("Clear", style = MockonomyTheme.typography.label)
                        }
                    }
                }

                Text(
                    text = "Endpoints, error codes and copy-paste snippets (curl, adb, Espresso, " +
                        "Appium): see automation-api.md in the project docs.",
                    style = MockonomyTheme.typography.caption,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                    modifier = Modifier.padding(top = Spacing.s4, bottom = Spacing.s3),
                )
            }
        }
    }
}

/** Label (+ description) on the left, Switch on the right. */
@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.s3)) {
            Text(text = label, style = MockonomyTheme.typography.body, color = MockonomyTheme.colors.onSurface)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MockonomyTheme.typography.caption,
                    color = MockonomyTheme.colors.onSurfaceFaint,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun UrlBlock(url: String, onCopy: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MockonomyTheme.colors.surfaceSunken, shape)
            .padding(start = 12.dp, end = Spacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = url,
            style = MockonomyTheme.typography.monoBody,
            color = MockonomyTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 11.dp),
        )
        TextButton(onClick = { onCopy(url) }) {
            Text(
                text = "COPY",
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.primaryText,
            )
        }
    }
}

private suspend fun androidx.compose.ui.platform.Clipboard.copy(text: String) {
    setClipEntry(
        androidx.compose.ui.platform.ClipEntry(android.content.ClipData.newPlainText("Mockonomy", text)),
    )
}
