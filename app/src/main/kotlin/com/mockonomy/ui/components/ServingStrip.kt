package com.mockonomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing
import java.util.Locale

/**
 * A persistent accent-outlined strip: success dot, "SERVING · name" overline, mono address,
 * Stop button. The app's single source of truth for "what is running right now".
 */
@Composable
fun ServingStrip(
    name: String,
    address: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MockonomyTheme.colors.surfaceVariant, RoundedCornerShape(MockonomyMetrics.cardCornerRadius))
            .border(1.dp, MockonomyTheme.colors.primaryOutline, RoundedCornerShape(MockonomyMetrics.cardCornerRadius))
            .padding(horizontal = Spacing.s3, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(MockonomyTheme.colors.success.copy(alpha = 0.18f), CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(MockonomyTheme.colors.success, CircleShape),
            )
        }

        Column(modifier = Modifier.weight(1f).padding(start = Spacing.s3)) {
            Text(
                text = "SERVING · ${name.uppercase(Locale.ROOT)}",
                style = MockonomyTheme.typography.overline,
                color = MockonomyTheme.colors.success,
            )
            Text(
                text = address,
                style = MockonomyTheme.typography.monoPath,
                color = MockonomyTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.defaultMinSize(minHeight = MockonomyMetrics.minTouchTarget),
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.StopFill,
                contentDescription = null,
                tint = MockonomyTheme.colors.onSurface,
                size = 11.dp,
            )
            Text(
                text = "Stop",
                style = MockonomyTheme.typography.label,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@MockonomyThemePreview
@Composable
private fun ServingStripPreview() {
    MockonomyTheme {
        Box(modifier = Modifier.background(MockonomyTheme.colors.background).padding(Spacing.s4)) {
            ServingStrip(name = "staging", address = "127.0.0.1:8080", onStop = {})
        }
    }
}
