package com.mockonomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing

/**
 * Visual building blocks for bottom sheets — the drag handle and the action row. For the
 * actual `ModalBottomSheet` wrapper see `MockonomySheet.kt`.
 */
@Composable
fun MockonomyDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 32.dp, height = 4.dp)
            .background(MockonomyTheme.colors.outline, RoundedCornerShape(2.dp)),
    )
}

/** Not used for any sheet that needs more than a single decision. */
@Composable
fun SheetActionRow(
    cancelLabel: String,
    onCancel: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.s3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text(cancelLabel, style = MockonomyTheme.typography.label)
        }
        OutlinedButton(onClick = onConfirm, enabled = confirmEnabled) {
            Text(confirmLabel, style = MockonomyTheme.typography.label)
        }
    }
}

@MockonomyThemePreview
@Composable
private fun SheetPrimitivesPreview() {
    MockonomyTheme {
        Column(
            modifier = Modifier
                .background(MockonomyTheme.colors.outlineSubtle) // scrim'i temsilen zemin
                .padding(Spacing.s5),
        ) {
            Column(
                modifier = Modifier
                    .background(
                        MockonomyTheme.colors.surface,
                        RoundedCornerShape(topStart = MockonomyMetrics.sheetCornerRadius, topEnd = MockonomyMetrics.sheetCornerRadius),
                    )
                    .padding(top = Spacing.s3),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MockonomyDragHandle()
                Text(
                    text = "Switch response",
                    style = MockonomyTheme.typography.titleLarge,
                    color = MockonomyTheme.colors.onSurface,
                    modifier = Modifier.padding(Spacing.s4),
                )
                SheetActionRow(
                    cancelLabel = "Cancel",
                    onCancel = {},
                    confirmLabel = "Apply",
                    onConfirm = {},
                )
            }
        }
    }
}
