package com.mockonomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing
import com.mockonomy.ui.theme.statusChipColors

/**
 * Colored by the status code's first digit (the hundreds) only. Never carries an icon —
 * the number is the only signal.
 */
@Composable
fun StatusCodeChip(
    statusCode: Int,
    modifier: Modifier = Modifier,
    size: ChipSize = ChipSize.Regular,
) {
    val pair = statusCode.statusChipColors()
    val fontSize = if (size == ChipSize.Regular) 11.sp else 10.sp
    val padding = if (size == ChipSize.Regular) {
        PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    } else {
        PaddingValues(horizontal = 5.dp, vertical = 3.dp)
    }
    val shape = RoundedCornerShape(MockonomyMetrics.statusChipCornerRadius)

    Text(
        text = statusCode.toString(),
        modifier = modifier
            .background(pair.background, shape)
            .padding(padding),
        color = pair.foreground,
        style = MockonomyTheme.typography.monoChip.copy(fontSize = fontSize),
    )
}

@MockonomyThemePreview
@Composable
private fun StatusCodeChipAllVariantsPreview() {
    MockonomyTheme {
        Row(
            modifier = Modifier
                .background(MockonomyTheme.colors.background)
                .padding(Spacing.s4),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            for (code in listOf(200, 304, 404, 500)) {
                StatusCodeChip(statusCode = code, size = ChipSize.Regular)
            }
        }
    }
}
