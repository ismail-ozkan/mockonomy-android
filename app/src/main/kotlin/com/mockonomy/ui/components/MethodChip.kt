package com.mockonomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.MockonomyThemePreview
import com.mockonomy.ui.theme.Spacing
import com.mockonomy.ui.theme.chipColors

enum class ChipSize { Regular, Small }

/**
 * A fixed color per method — theme-independent (does not change between dark and light)
 * and never recolored by state. A disabled route dims the whole row, not the chip.
 */
@Composable
fun MethodChip(
    method: HttpMethod,
    modifier: Modifier = Modifier,
    size: ChipSize = ChipSize.Regular,
) {
    val pair = method.chipColors()
    val minWidth = if (size == ChipSize.Regular) 46.dp else 38.dp
    val fontSize = if (size == ChipSize.Regular) 10.5.sp else 9.5.sp
    val padding = if (size == ChipSize.Regular) {
        PaddingValues(horizontal = 7.dp, vertical = 5.dp)
    } else {
        PaddingValues(horizontal = 6.dp, vertical = 4.dp)
    }
    val shape = RoundedCornerShape(MockonomyMetrics.chipCornerRadius)

    Text(
        text = method.name,
        modifier = modifier
            .defaultMinSize(minWidth = minWidth)
            .background(pair.background, shape)
            .padding(padding),
        color = pair.foreground,
        style = MockonomyTheme.typography.monoChip.copy(
            fontSize = fontSize,
            letterSpacing = 0.06f.em,
        ),
        textAlign = TextAlign.Center,
    )
}

@MockonomyThemePreview
@Composable
private fun MethodChipAllVariantsPreview() {
    MockonomyTheme {
        FlowRow(
            modifier = Modifier
                .background(MockonomyTheme.colors.background)
                .padding(Spacing.s4),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            verticalArrangement = Arrangement.spacedBy(Spacing.s2),
        ) {
            for (method in HttpMethod.entries) {
                MethodChip(method = method, size = ChipSize.Regular)
            }
            for (method in HttpMethod.entries) {
                MethodChip(method = method, size = ChipSize.Small)
            }
        }
    }
}
