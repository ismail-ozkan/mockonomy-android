package com.mockonomy.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mockonomy.ui.theme.MockonomyMetrics
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * The shared presentation mechanism for quick actions (switch the active response, pick a
 * status code, set latency, quickly add a route).
 *
 * Screens do not use this directly; their own sheet composables (such as
 * `SwitchResponseSheet`) are built on top of it. The tokens (28dp top corners, `surface`
 * background, [MockonomyDragHandle]) are gathered here in one place — no sheet invents its
 * own corner radius.
 *
 * `sheetState` is deliberately not exposed: M3's `SheetState` type is still experimental
 * (`@ExperimentalMaterial3Api`) — keeping it in the public signature would leak that
 * opt-in into every calling screen. The experimental usage stays entirely inside this file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MockonomySheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MockonomyTheme.colors.surface,
        contentColor = MockonomyTheme.colors.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.66f),
        shape = RoundedCornerShape(
            topStart = MockonomyMetrics.sheetCornerRadius,
            topEnd = MockonomyMetrics.sheetCornerRadius,
        ),
        dragHandle = { MockonomyDragHandle(modifier = Modifier.padding(vertical = Spacing.s2)) },
    ) {
        Column { content() }
    }
}
