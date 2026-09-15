package com.mockonomy.ui.theme

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Added to a preview function, it makes Android Studio render both light and dark themes
 * automatically, so every component's preview can be checked in both themes.
 *
 * Usage: the function body must be wrapped in `MockonomyTheme { ... }` — this annotation
 * only triggers two different `uiMode` renders; it does not apply the theme itself.
 */
@Preview(name = "Light", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class MockonomyThemePreview
