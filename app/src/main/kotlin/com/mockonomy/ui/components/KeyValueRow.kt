package com.mockonomy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/**
 * An editable row for header/query pairs. When several rows are shown as one group, the
 * caller wraps them with `Modifier.border(1.dp, outline, RoundedCornerShape(10.dp))` and
 * puts a [HorizontalDivider] between rows — see the preview below.
 *
 * `onKeyChange` defaults to `null`: the key is shown as fixed text. Where the key must be
 * editable too (such as the environment settings screen), this parameter is passed — a
 * backward-compatible extension.
 */
@Composable
fun KeyValueRow(
    key: String,
    value: String,
    onValueChange: (String) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    onKeyChange: ((String) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = MockonomyMetrics.keyValueRowMinHeight)
            .padding(horizontal = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onKeyChange != null) {
            BasicTextField(
                value = key,
                onValueChange = onKeyChange,
                singleLine = true,
                textStyle = MockonomyTheme.typography.monoBody.copy(color = MockonomyTheme.colors.primaryText),
                modifier = Modifier.width(MockonomyMetrics.keyValueKeyColumnWidth),
            )
        } else {
            Text(
                text = key,
                style = MockonomyTheme.typography.monoBody,
                color = MockonomyTheme.colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(MockonomyMetrics.keyValueKeyColumnWidth),
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MockonomyTheme.colors.outlineSubtle),
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MockonomyTheme.typography.monoBody.copy(color = MockonomyTheme.colors.onSurface),
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.s3),
        )

        IconButton(
            onClick = onRemove,
            interactionSource = interactionSource,
            modifier = Modifier.size(width = 38.dp, height = MockonomyMetrics.minTouchTarget),
        ) {
            MockonomyIcon(
                icon = MockonomyIcons.X,
                contentDescription = "Remove",
                tint = if (isHovered) MockonomyTheme.colors.error else MockonomyTheme.colors.onSurfaceFaint,
                size = 16.dp,
            )
        }
    }
}

/** An empty "key : value" ghost row — fully clickable, an invitation to add a new pair. */
@Composable
fun AddKeyValueRow(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onAdd)
            .defaultMinSize(minHeight = MockonomyMetrics.keyValueRowMinHeight)
            .padding(horizontal = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "key : value",
            style = MockonomyTheme.typography.monoBody,
            color = MockonomyTheme.colors.onSurfaceFaint,
        )
    }
}

@MockonomyThemePreview
@Composable
private fun KeyValueRowGroupPreview() {
    MockonomyTheme {
        Column(
            modifier = Modifier
                .background(MockonomyTheme.colors.background)
                .padding(Spacing.s4)
                .border(1.dp, MockonomyTheme.colors.outline, RoundedCornerShape(MockonomyMetrics.keyValueRowCornerRadius)),
        ) {
            KeyValueRow(key = "Content-Type", value = "application/json", onValueChange = {}, onRemove = {})
            HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle, thickness = 1.dp)
            KeyValueRow(key = "X-Request-Id", value = "8412", onValueChange = {}, onRemove = {})
            HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle, thickness = 1.dp)
            AddKeyValueRow(onAdd = {})
        }
    }
}
