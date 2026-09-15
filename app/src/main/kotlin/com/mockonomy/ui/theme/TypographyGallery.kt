package com.mockonomy.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * A permanent living reference that shows every normal + mono style with real samples on a
 * single screen — the monospace scale is checked here against real path/JSON snippets.
 * Do not delete or move it; it stays as a reference.
 */
private data class TypeSample(val name: String, val style: TextStyle, val sample: String)

@MockonomyThemePreview
@Composable
private fun TypographyGalleryPreview() {
    MockonomyTheme {
        val t = MockonomyTheme.typography
        val samples = listOf(
            TypeSample("display", t.display, "Environments"),
            TypeSample("title-lg", t.titleLarge, "Response"),
            TypeSample("title", t.title, "error scenarios"),
            TypeSample("body", t.body, "Served when no rule matches"),
            TypeSample("label", t.label, "Paste & format"),
            TypeSample("caption", t.caption, "Applies on restart"),
            TypeSample("overline", t.overline, "ADVANCED"),
            TypeSample("mono-display", t.monoDisplay, "127.0.0.1:8080"),
            TypeSample("mono-path", t.monoPath, "/v2/users/{id}"),
            TypeSample("mono-body", t.monoBody, "\"name\": \"Ada Byron\""),
            TypeSample("mono-chip", t.monoChip, "DELETE"),
            TypeSample("mono-meta", t.monoMeta, "14:32:07 · 42 ms"),
        )

        Column(
            modifier = Modifier
                .background(MockonomyTheme.colors.background)
                .padding(Spacing.s4),
        ) {
            for (sample in samples) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sample.name,
                        style = t.monoMeta,
                        color = MockonomyTheme.colors.onSurfaceFaint,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        text = sample.sample,
                        style = sample.style,
                        color = MockonomyTheme.colors.onSurface,
                    )
                }
                HorizontalDivider(color = MockonomyTheme.colors.outlineSubtle, thickness = 1.dp)
            }
        }
    }
}
