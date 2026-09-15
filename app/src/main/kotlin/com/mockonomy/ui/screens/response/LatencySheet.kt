package com.mockonomy.ui.screens.response

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.mockonomy.ui.components.MockonomySheet
import com.mockonomy.ui.components.SheetActionRow
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * The latency added to this response. It adds up with the environment's overall latency —
 * `spec/behavior/matching.md`, "latency sum".
 */
@Composable
fun LatencySheet(
    currentMs: Int,
    onDismiss: () -> Unit,
    onSelect: (latencyMs: Int) -> Unit,
) {
    var custom by remember { mutableStateOf(currentMs.toString()) }
    val customMs = custom.toIntOrNull()

    MockonomySheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s4, vertical = Spacing.s2)) {
            Text(
                text = "Latency",
                style = MockonomyTheme.typography.titleLarge,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = Spacing.s2),
            )
            Text(
                text = "Added to this response only, on top of the environment's global latency.",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s3),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                PRESETS_MS.forEach { preset ->
                    OutlinedButton(
                        onClick = { onSelect(preset) },
                        modifier = Modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
                    ) {
                        Text(
                            text = if (preset == 0) "None" else "$preset ms",
                            style = MockonomyTheme.typography.monoChip,
                            color = if (preset == currentMs) {
                                MockonomyTheme.colors.primaryText
                            } else {
                                MockonomyTheme.colors.onSurface
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = custom,
                onValueChange = { text -> custom = text.filter { it.isDigit() }.take(6) },
                label = { Text("Other") },
                suffix = { Text("ms") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
            )
        }

        SheetActionRow(
            cancelLabel = "Cancel",
            onCancel = onDismiss,
            confirmLabel = "Use",
            onConfirm = { customMs?.let(onSelect) },
            confirmEnabled = customMs != null,
        )
    }
}

private val PRESETS_MS = listOf(0, 200, 500, 1000, 3000)
