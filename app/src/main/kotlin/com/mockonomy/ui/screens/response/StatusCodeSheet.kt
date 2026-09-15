package com.mockonomy.ui.screens.response

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * Status code picker.
 *
 * Making the user type a three-digit number on a phone keyboard is a bad trade: frequently
 * used codes are one tap, and anything else can still be typed. The chip's color shows the
 * result's class even before a choice is made.
 */
@Composable
fun StatusCodeSheet(
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (statusCode: Int) -> Unit,
) {
    var custom by remember { mutableStateOf(current.toString()) }
    val customCode = custom.toIntOrNull()

    MockonomySheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s4, vertical = Spacing.s2)) {
            Text(
                text = "Status code",
                style = MockonomyTheme.typography.titleLarge,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = Spacing.s3),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                COMMON_STATUS_CODES.forEach { code ->
                    StatusCodeChip(
                        statusCode = code,
                        modifier = Modifier.clickable { onSelect(code) },
                    )
                }
            }

            OutlinedTextField(
                value = custom,
                onValueChange = { text -> custom = text.filter { it.isDigit() }.take(3) },
                label = { Text("Other") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(customCode?.let(::reasonPhraseOf)?.ifBlank { " " } ?: "Three digits")
                },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s3),
            )
        }

        SheetActionRow(
            cancelLabel = "Cancel",
            onCancel = onDismiss,
            confirmLabel = "Use",
            onConfirm = { customCode?.let(onSelect) },
            confirmEnabled = customCode != null && customCode >= MIN_STATUS_CODE,
        )
    }
}

/** Codes below 100 are undefined in HTTP; the engine's neutral fallback starts there too. */
private const val MIN_STATUS_CODE = 100
