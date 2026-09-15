package com.mockonomy.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mockonomy.R
import com.mockonomy.ui.components.SectionHeader
import com.mockonomy.ui.screens.licenses.LicenseEntry
import com.mockonomy.ui.screens.licenses.bundledNotices
import com.mockonomy.ui.screens.licenses.parseLibraryDefinitions
import com.mockonomy.ui.theme.MockonomyIcon
import com.mockonomy.ui.theme.MockonomyIcons
import com.mockonomy.ui.theme.MockonomyTheme
import com.mockonomy.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open source licenses (reached from Settings). Fonts and icons are listed by hand; the
 * Gradle dependencies come from `raw/aboutlibraries.json`, which the AboutLibraries plugin
 * generates at build time. This reference from code is also what keeps the JSON out of
 * reach of resource shrinking.
 */
@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    onLicenseClick: (LicenseEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val libraries by produceState(initialValue = emptyList<LicenseEntry>()) {
        value = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use {
                parseLibraryDefinitions(it.readText())
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TitleBar(title = "Open source licenses", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                SectionHeader(
                    text = "Fonts & icons",
                    modifier = Modifier.padding(horizontal = Spacing.s4, vertical = Spacing.s2),
                )
            }
            items(bundledNotices) { entry ->
                LicenseRow(entry = entry, onClick = { onLicenseClick(entry) })
            }
            item {
                SectionHeader(
                    text = "Libraries",
                    modifier = Modifier.padding(
                        start = Spacing.s4,
                        end = Spacing.s4,
                        top = Spacing.s4,
                        bottom = Spacing.s2,
                    ),
                )
            }
            items(libraries) { entry ->
                LicenseRow(entry = entry, onClick = { onLicenseClick(entry) })
            }
        }
    }
}

/** The full text of a single license; [assetPath] is a `licenses/` file embedded in the assets. */
@Composable
fun LicenseTextScreen(
    title: String,
    assetPath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val text by produceState(initialValue = "", assetPath) {
        value = withContext(Dispatchers.IO) {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TitleBar(title = title, onBack = onBack)
        Text(
            text = text,
            style = MockonomyTheme.typography.monoMeta,
            color = MockonomyTheme.colors.onSurfaceVariant,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.s4, vertical = Spacing.s2),
        )
    }
}

@Composable
private fun TitleBar(title: String, onBack: () -> Unit) {
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
            text = title,
            style = MockonomyTheme.typography.title,
            color = MockonomyTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Spacing.s2),
        )
    }
}

@Composable
private fun LicenseRow(entry: LicenseEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.s4, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MockonomyTheme.typography.body,
                color = MockonomyTheme.colors.onSurface,
            )
            Text(
                text = entry.detail,
                style = MockonomyTheme.typography.monoMeta,
                color = MockonomyTheme.colors.onSurfaceFaint,
            )
        }
        MockonomyIcon(
            icon = MockonomyIcons.CaretRight,
            contentDescription = null,
            tint = MockonomyTheme.colors.onSurfaceFaint,
            size = 16.dp,
        )
    }
}
