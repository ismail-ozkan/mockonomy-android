package com.mockonomy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing
import java.util.Locale

/**
 * An overline label + a thin line filling the remaining width + optional trailing content.
 * Used the same way in Response Edit, Route Edit and Log Detail.
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(Locale.ROOT), // NOT Locale.getDefault() — avoids the Turkish dotted/dotless "I" bug
            style = MockonomyTheme.typography.overline,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
        HorizontalDivider(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.s3),
            thickness = 1.dp,
            color = MockonomyTheme.colors.outlineSubtle,
        )
        if (trailing != null) {
            Row(modifier = Modifier.padding(start = Spacing.s3)) {
                trailing()
            }
        }
    }
}

@MockonomyThemePreview
@Composable
private fun SectionHeaderPreview() {
    MockonomyTheme {
        Column {
            SectionHeader(text = "Advanced", modifier = Modifier.padding(Spacing.s4))
            SectionHeader(
                text = "Headers",
                modifier = Modifier.padding(Spacing.s4),
                trailing = {
                    Text(
                        "2",
                        style = MockonomyTheme.typography.monoMeta,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                    )
                },
            )
        }
    }
}
