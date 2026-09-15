package com.mockonomy.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing
import java.util.Locale

/**
 * A collapsible "Advanced" section. State is hoisted (controlled component) — if the
 * open/closed state should be remembered per response, that is the screen/ViewModel
 * layer's job, not the component's own `remember`. Showing it collapsed by default on
 * every screen is the caller's responsibility (pass `false` as the initial `expanded`).
 */
@Composable
fun AdvancedSection(
    summary: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Advanced",
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "advancedCaret")

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = Spacing.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.CaretDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MockonomyTheme.colors.onSurfaceFaint,
                size = 16.dp,
                modifier = Modifier.rotate(rotation),
            )
            Text(
                text = title.uppercase(Locale.ROOT),
                style = MockonomyTheme.typography.overline,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.s2),
            )
            Text(
                text = summary,
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
        }
        if (expanded) {
            Column(content = content)
        }
    }
}

@MockonomyThemePreview
@Composable
private fun AdvancedSectionCollapsedAndExpandedPreview() {
    MockonomyTheme {
        Column(modifier = Modifier.background(MockonomyTheme.colors.background)) {
            AdvancedSection(
                summary = "JSON · default · AND",
                expanded = false,
                onExpandedChange = {},
            ) {}
            AdvancedSection(
                summary = "JSON · default · AND",
                expanded = true,
                onExpandedChange = {},
            ) {
                Text(
                    text = "Body type: JSON",
                    style = MockonomyTheme.typography.body,
                    color = MockonomyTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.s3),
                )
            }
        }
    }
}
