package com.mockonomy.data.log

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Ring buffer limits for the request log.
 *
 * Both limits work together: pruning starts when either one is reached. A record
 * count alone would not be enough (1000 requests with large bodies can reach
 * gigabytes), and a size limit alone would not either (with small requests the list
 * would grow forever).
 *
 * The limits apply to **the whole database**, not per environment: the goal is to
 * bound the space we take on the device, and that is a property of the whole DB.
 */
data class LogRetention(
    val enabled: Boolean = true,
    val maxCount: Int = DEFAULT_MAX_COUNT,
    val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    companion object {
        const val DEFAULT_MAX_COUNT: Int = 1000
        const val DEFAULT_MAX_BYTES: Long = 50L * 1024 * 1024
    }
}

class LogSettingsStore(private val dataStore: DataStore<Preferences>) {

    val retention: Flow<LogRetention> = dataStore.data.map { prefs ->
        LogRetention(
            enabled = prefs[ENABLED] ?: true,
            maxCount = prefs[MAX_COUNT] ?: LogRetention.DEFAULT_MAX_COUNT,
            maxBytes = prefs[MAX_BYTES] ?: LogRetention.DEFAULT_MAX_BYTES,
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun setMaxCount(maxCount: Int) {
        dataStore.edit { it[MAX_COUNT] = maxCount }
    }

    suspend fun setMaxBytes(maxBytes: Long) {
        dataStore.edit { it[MAX_BYTES] = maxBytes }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("log_enabled")
        val MAX_COUNT = intPreferencesKey("log_max_count")
        val MAX_BYTES = longPreferencesKey("log_max_bytes")
    }
}
