package com.mockonomy.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Remembers that the proxy recording (recordUnmatched) privacy warning was shown once.
 *
 * Recorded real traffic may contain headers such as `Authorization`; the user is
 * warned once, the **first time** they turn on `recordUnmatched`. A one-way flag —
 * the same pattern as [OnboardingStore]: once shown, never shown again.
 */
class ProxyPrivacyStore(private val dataStore: DataStore<Preferences>) {

    val recordingWarningShown: Flow<Boolean> = dataStore.data.map { it[KEY] == true }

    suspend fun markRecordingWarningShown() {
        dataStore.edit { it[KEY] = true }
    }

    private companion object {
        val KEY = booleanPreferencesKey("proxy_recording_warning_shown")
    }
}
