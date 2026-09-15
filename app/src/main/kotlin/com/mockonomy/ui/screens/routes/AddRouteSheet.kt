package com.mockonomy.ui.screens.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.mockonomy.engine.RoutePath
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.ui.components.MethodChip
import com.mockonomy.ui.components.MockonomySheet
import com.mockonomy.ui.components.SheetActionRow
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * Quick route add: pick a method + type a path + add. The goal is "in three seconds without
 * leaving the screen", so there are no other fields here — status code, latency and body
 * all live in route editing.
 */
@Composable
fun AddRouteSheet(
    onDismiss: () -> Unit,
    onAdd: (HttpMethod, String) -> Unit,
) {
    var method by remember { mutableStateOf(HttpMethod.GET) }
    var path by remember { mutableStateOf("/") }

    val problem = RoutePath.validate(path)

    MockonomySheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.s4)) {
            Text(
                text = "Add route",
                style = MockonomyTheme.typography.titleLarge,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(bottom = Spacing.s3),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.s3),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
                verticalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                for (candidate in HttpMethod.entries) {
                    MethodChip(
                        method = candidate,
                        modifier = Modifier
                            .clickable { method = candidate }
                            // Unselected ones are faded: method colors are fixed, so the
                            // selection is shown with opacity, not color.
                            .alpha(if (candidate == method) 1f else UNSELECTED_ALPHA),
                    )
                }
            }

            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Path") },
                singleLine = true,
                isError = problem != null,
                supportingText = { Text(problem?.let(::pathProblemMessage) ?: "e.g. /users/:id") },
                modifier = Modifier.fillMaxWidth(),
            )

            SheetActionRow(
                cancelLabel = "Cancel",
                onCancel = onDismiss,
                confirmLabel = "Add",
                onConfirm = { onAdd(method, path) },
                confirmEnabled = problem == null,
            )
        }
    }
}

private const val UNSELECTED_ALPHA = 0.35f
