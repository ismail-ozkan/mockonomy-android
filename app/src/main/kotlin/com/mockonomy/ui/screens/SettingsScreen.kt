package com.mockonomy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mockonomy.data.ThemeMode
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.screens.setup.AppearanceViewModel
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing

/**
 * The settings screen: the `Appearance` section and the version number. The setup guide
 * only covers connection diagnostics and the guide itself.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLicensesClick: () -> Unit,
    modifier: Modifier = Modifier,
    appearanceViewModel: AppearanceViewModel = hiltViewModel(),
) {
    val mode by appearanceViewModel.themeMode.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                MockonomyIcon(
                    icon = MockonomyIcons.ArrowLeft,
                    contentDescription = "Back",
                    tint = MockonomyTheme.colors.onSurface,
                )
            }
            Text(
                text = "Settings",
                style = MockonomyTheme.typography.title,
                color = MockonomyTheme.colors.onSurface,
                modifier = Modifier.padding(start = Spacing.s2),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.s4),
        ) {
            SectionHeader(
                text = "Appearance",
                modifier = Modifier.padding(top = Spacing.s2, bottom = Spacing.s2),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s2),
            ) {
                ThemeMode.values().forEach { candidate ->
                    ThemePill(
                        label = when (candidate) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                        selected = candidate == mode,
                        onClick = { appearanceViewModel.setThemeMode(candidate) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SectionHeader(
                text = "About",
                modifier = Modifier.padding(top = Spacing.s5, bottom = Spacing.s1),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onLicensesClick)
                    .padding(vertical = Spacing.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Open source licenses",
                    style = MockonomyTheme.typography.body,
                    color = MockonomyTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                MockonomyIcon(
                    icon = MockonomyIcons.CaretRight,
                    contentDescription = null,
                    tint = MockonomyTheme.colors.onSurfaceFaint,
                    size = 16.dp,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Centered at the bottom of the page, slightly larger type.
            Text(
                text = "Mockonomy v${com.mockonomy.BuildConfig.VERSION_NAME}",
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurfaceFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.s4),
            )
        }
    }
}

@Composable
private fun ThemePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val bg = if (selected) MockonomyTheme.colors.primaryContainer else MockonomyTheme.colors.surface
    val fg = if (selected) MockonomyTheme.colors.primaryText else MockonomyTheme.colors.onSurface
    val borderColor = if (selected) MockonomyTheme.colors.primary else MockonomyTheme.colors.outlineSubtle
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MockonomyTheme.typography.label,
            color = fg,
        )
    }
}
