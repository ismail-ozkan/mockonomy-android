package com.mockonomy.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Persistent theme preference, DARK by default. */
class AppearanceSettingsStoreTest {

    private fun newStore() = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("appearance", ".preferences_pb").apply { deleteOnExit() } },
    ).let { it to AppearanceSettingsStore(it) }

    @Test
    fun `default is DARK`() = runTest {
        val (_, store) = newStore()
        assertEquals(ThemeMode.DARK, store.themeMode.first())
    }

    @Test
    fun `mode is set and read back`() = runTest {
        val (_, store) = newStore()
        store.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, store.themeMode.first())
        store.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(ThemeMode.SYSTEM, store.themeMode.first())
    }

    /**
     * If DataStore holds an unrecognized value (corrupted by hand, or a constant removed
     * later), the theme flow does not crash — it falls back to the default (DARK). This
     * proves that the Enum.valueOf exception is caught.
     */
    @Test
    fun `corrupt value falls back to the DARK default`() = runTest {
        val (dataStore, store) = newStore()
        dataStore.edit { it[stringPreferencesKey("appearance_theme_mode")] = "PURPLE" }
        assertEquals(ThemeMode.DARK, store.themeMode.first())
    }
}
