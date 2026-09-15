package com.mockonomy.ui.screens.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mockonomy.engine.model.RouteResponse
import com.mockonomy.ui.components.MockonomySheet
import com.mockonomy.ui.components.StatusCodeChip
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * Switch the active response with one tap.
 *
 * One of the most valuable features: even without rules, QA can say "now let me test the
 * 500 scenario". The choice is written to disk immediately and the running server starts
 * returning the new response through hot reload — without leaving the screen.
 *
 * With rules the schema does not change: rules are evaluated first, and the choice made
 * here (`isDefault`) becomes the fallback.
 */
@Composable
fun SwitchResponseSheet(
    responses: List<RouteResponse>,
    onDismiss: () -> Unit,
    onSelect: (responseId: String) -> Unit,
) {
    MockonomySheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s4, vertical = Spacing.s2)) {
            Text(
                text = "Switch response",
                style = MockonomyTheme.typography.titleLarge,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = Spacing.s2),
            )
            Text(
                // Not entirely accurate once rules exist: rules run first. If the text did not
                // say so, the user would think their choice did not work when a response whose
                // rule passes is returned.
                text = "The selected response is served when no rule matches.",
                style = MockonomyTheme.typography.caption,
                color = MockonomyTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Spacing.s3),
            )

            responses.forEach { response ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(response.id) }
                        .padding(vertical = Spacing.s3),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s3),
                ) {
                    RadioButton(selected = response.isDefault, onClick = { onSelect(response.id) })
                    StatusCodeChip(statusCode = response.statusCode)
                    Text(
                        text = response.label.ifBlank { "Untitled response" },
                        style = MockonomyTheme.typography.body,
                        color = MockonomyTheme.colors.onSurface,
                    )
                }
            }
        }
    }
}
