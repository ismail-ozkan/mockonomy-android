package com.mockonomy.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing

/**
 * A centered vertical group: a dashed-frame glyph, title, body, one primary action and an
 * optional ghost secondary action. The text explains the consequence ("answers every
 * request with 404"), never just "there is nothing here".
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = MockonomyIcons.Info,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .dashedBorder(
                    color = MockonomyTheme.colors.outline,
                    cornerRadius = 14.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            MockonomyIcon(
                icon = icon,
                contentDescription = null,
                tint = MockonomyTheme.colors.onSurfaceFaint,
                size = 22.dp,
            )
        }

        Spacer(Modifier.height(Spacing.s4))

        Text(
            text = title,
            style = MockonomyTheme.typography.title,
            color = MockonomyTheme.colors.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Spacing.s2))

        Text(
            text = description,
            style = MockonomyTheme.typography.body,
            color = MockonomyTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 250.dp),
        )

        Spacer(Modifier.height(Spacing.s4))

        OutlinedButton(
            onClick = onPrimaryAction,
            modifier = Modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
        ) {
            Text(primaryActionLabel, style = MockonomyTheme.typography.label)
        }

        if (secondaryActionLabel != null && onSecondaryAction != null) {
            TextButton(
                onClick = onSecondaryAction,
                modifier = Modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
            ) {
                Text(secondaryActionLabel, style = MockonomyTheme.typography.label)
            }
        }
    }
}

@MockonomyThemePreview
@Composable
private fun EmptyStateWithSecondaryActionPreview() {
    MockonomyTheme {
        Box(modifier = Modifier.background(MockonomyTheme.colors.background).padding(Spacing.s5)) {
            EmptyState(
                title = "No routes yet",
                description = "This environment answers every request with 404 until you add a route.",
                primaryActionLabel = "Add route",
                onPrimaryAction = {},
                icon = MockonomyIcons.GitBranch,
                secondaryActionLabel = "Load example",
                onSecondaryAction = {},
            )
        }
    }
}

@MockonomyThemePreview
@Composable
private fun EmptyStateMinimalPreview() {
    MockonomyTheme {
        Box(modifier = Modifier.background(MockonomyTheme.colors.background).padding(Spacing.s5)) {
            EmptyState(
                title = "No environments",
                description = "Create your first environment to start mocking.",
                primaryActionLabel = "Create environment",
                onPrimaryAction = {},
            )
        }
    }
}
