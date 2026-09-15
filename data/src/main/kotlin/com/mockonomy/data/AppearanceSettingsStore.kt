package com.mockonomy.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's theme preference.
 *
 * Defaults to [DARK]: the app was designed for the dark palette and opens dark
 * until the user changes it. `SYSTEM` follows the device's day/night setting.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

class AppearanceSettingsStore(private val dataStore: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("appearance_theme_mode")
    }
}
