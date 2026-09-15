package com.mockonomy.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings for the automation admin API.
 *
 * All three defaults sit on **the cheap side of a mistake**:
 * - [enabled] defaults to **off** — every app on the device can reach `127.0.0.1`;
 *   while the admin API is on, another app could change mock behavior and read
 *   recorded real traffic (including Authorization headers). So the feature only
 *   works once the user deliberately turns it on.
 * - [redactReads] defaults to **on** — read endpoints (`GET /logs`) mask sensitive
 *   headers; the same list and logic as environment export.
 * - [token] defaults to **none** — a shared token adds friction to test code, so it
 *   is opt-in; when set, the `X-Mockonomy-Token` header is required.
 */
data class AdminApiSettings(
    val enabled: Boolean = false,
    val redactReads: Boolean = true,
    val token: String? = null,
)

class AdminApiSettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AdminApiSettings> = dataStore.data.map { prefs ->
        AdminApiSettings(
            enabled = prefs[ENABLED] ?: false,
            redactReads = prefs[REDACT_READS] ?: true,
            // An empty string means "no token"; when the user clears the token the key
            // stays empty and is normalized to null.
            token = prefs[TOKEN]?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun setRedactReads(redact: Boolean) {
        dataStore.edit { it[REDACT_READS] = redact }
    }

    /** A blank or `null` token clears it (shared token off). */
    suspend fun setToken(token: String?) {
        dataStore.edit {
            if (token.isNullOrBlank()) it.remove(TOKEN) else it[TOKEN] = token
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("admin_api_enabled")
        val REDACT_READS = booleanPreferencesKey("admin_api_redact_reads")
        val TOKEN = stringPreferencesKey("admin_api_token")
    }
}
