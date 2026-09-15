package com.mockonomy.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.ProxyConfig
import com.mockonomy.ui.screens.detail.EnvironmentDetailViewModel
import com.mockonomy.ui.components.AddKeyValueRow
import com.mockonomy.ui.components.AdvancedSection
import com.mockonomy.ui.components.KeyValueRow
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * Environment settings.
 *
 * Port/prefix/latency on top; then the Proxy section; CORS and default headers under
 * Advanced.
 *
 * `environment` and the ViewModel come from outside: **the same**
 * [EnvironmentDetailViewModel] instance is shared with the Routes tab.
 */
@Composable
fun EnvironmentSettingsTab(
    viewModel: EnvironmentDetailViewModel,
    current: Environment,
    modifier: Modifier = Modifier,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.s4),
    ) {
        OutlinedTextField(
            value = current.port.toString(),
            onValueChange = { text ->
                text.filter { it.isDigit() }.take(5).toIntOrNull()?.let { port ->
                    viewModel.update { it.copy(port = port) }
                }
            },
            label = { Text("Port") },
            supportingText = { Text("Loopback only; 1024-65535") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3),
        )

        OutlinedTextField(
            value = current.prefix,
            onValueChange = { viewModel.update { env -> env.copy(prefix = it) } },
            label = { Text("Path prefix") },
            supportingText = { Text("Prepended to every route") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3),
        )

        OutlinedTextField(
            value = current.defaultLatencyMs.toString(),
            onValueChange = { text ->
                text.filter { it.isDigit() }.take(6).toIntOrNull()?.let { latency ->
                    viewModel.update { it.copy(defaultLatencyMs = latency) }
                }
            },
            label = { Text("Default latency (ms)") },
            supportingText = { Text("Added to every response") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s2),
        )

        StateMachineSection(viewModel = viewModel, current = current)

        ProxySection(viewModel = viewModel, current = current)

        AdvancedSection(
            summary = if (current.cors) "CORS on · ${current.headers.size} headers" else "${current.headers.size} headers",
            expanded = advancedExpanded,
            onExpandedChange = { advancedExpanded = it },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Auto-answer preflight (CORS)",
                    style = MockonomyTheme.typography.body,
                    color = MockonomyTheme.colors.onSurface,
                )
                Switch(
                    checked = current.cors,
                    onCheckedChange = { checked -> viewModel.update { it.copy(cors = checked) } },
                )
            }

            SectionHeader(text = "Default headers", modifier = Modifier.padding(vertical = Spacing.s2))

            current.headers.forEachIndexed { index, header ->
                KeyValueRow(
                    key = header.key,
                    value = header.value,
                    onKeyChange = { newKey ->
                        viewModel.update { env -> env.copy(headers = env.headers.replaceAt(index) { it.copy(key = newKey) }) }
                    },
                    onValueChange = { newValue ->
                        viewModel.update { env -> env.copy(headers = env.headers.replaceAt(index) { it.copy(value = newValue) }) }
                    },
                    onRemove = {
                        viewModel.update { env -> env.copy(headers = env.headers.filterIndexed { i, _ -> i != index }) }
                    },
                )
                HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle)
            }

            AddKeyValueRow(
                onAdd = {
                    viewModel.update { env -> env.copy(headers = env.headers + HeaderPair("", "")) }
                },
            )
        }

        Text(
            text = "Port, prefix and latency apply on restart.",
            style = MockonomyTheme.typography.caption,
            color = MockonomyTheme.colors.onSurfaceFaint,
            modifier = Modifier.padding(top = Spacing.s4),
        )
    }
}

/**
 * The Proxy section. The proxy must be visible for behavioral and security reasons — at the
 * top level, not hidden under "Advanced". The `recordUnmatched` setting is saved here.
 */
@Composable
private fun ProxySection(
    viewModel: EnvironmentDetailViewModel,
    current: Environment,
) {
    val proxy = current.proxy ?: ProxyConfig()
    val targetValid = proxy.target.isBlank() ||
        proxy.target.startsWith("http://", ignoreCase = true) ||
        proxy.target.startsWith("https://", ignoreCase = true)

    val warningShown by viewModel.proxyRecordingWarningShown.collectAsStateWithLifecycle()
    var showPrivacyWarning by rememberSaveable { mutableStateOf(false) }

    fun updateProxy(transform: (ProxyConfig) -> ProxyConfig) =
        viewModel.update { it.copy(proxy = transform(it.proxy ?: ProxyConfig())) }

    SectionHeader(text = "Proxy", modifier = Modifier.padding(vertical = Spacing.s3))

    ToggleRow(
        label = "Forward unmatched requests to Proxy URL",
        checked = proxy.enabled,
        onCheckedChange = { checked -> updateProxy { it.copy(enabled = checked) } },
    )

    if (proxy.enabled) {
        OutlinedTextField(
            value = proxy.target,
            onValueChange = { value -> updateProxy { it.copy(target = value.trim()) } },
            label = { Text("Proxy URL") },
            isError = !targetValid,
            supportingText = {
                Text(
                    if (targetValid) "Unmatched requests are forwarded here"
                    else "Must start with http:// or https://",
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3, bottom = Spacing.s3),
        )

        ToggleRow(
            label = "Record proxied responses",
            supporting = "Saved to the request log once recording lands (phase 4)",
            checked = proxy.recordUnmatched,
            onCheckedChange = { checked ->
                updateProxy { it.copy(recordUnmatched = checked) }
                // Recorded real traffic may contain credentials — warn the first time it is turned on.
                if (checked && !warningShown) showPrivacyWarning = true
            },
        )

        ToggleRow(
            label = "Ignore TLS errors",
            supporting = "Disables certificate checks; only for trusted self-signed staging",
            checked = proxy.ignoreTlsErrors,
            onCheckedChange = { checked -> updateProxy { it.copy(ignoreTlsErrors = checked) } },
        )
    }

    if (showPrivacyWarning) {
        val dismiss = {
            showPrivacyWarning = false
            viewModel.markProxyRecordingWarningShown()
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Recording proxied responses") },
            text = {
                Text(
                    "Recorded responses may include Authorization headers or cookies. " +
                        "Use the redaction option in export before sharing.",
                )
            },
            confirmButton = { TextButton(onClick = dismiss) { Text("Got it") } },
        )
    }
}

/** Label (+ optional description) on the left, Switch on the right. */
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
            Text(
                text = label,
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurface,
            )
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

private inline fun List<HeaderPair>.replaceAt(index: Int, transform: (HeaderPair) -> HeaderPair): List<HeaderPair> =
    mapIndexed { i, header -> if (i == index) transform(header) else header }
