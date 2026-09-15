package com.mockonomy.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * First-launch state.
 *
 * Seeding the example environment "when the list is empty" would be wrong: if the
 * user deliberately deleted the example, it would come back on every launch. Instead
 * a one-way flag is kept — once seeded, it never happens again.
 */
class OnboardingStore(private val dataStore: DataStore<Preferences>) {

    val exampleSeeded: Flow<Boolean> = dataStore.data.map { it[KEY] == true }

    suspend fun markExampleSeeded() {
        dataStore.edit { it[KEY] = true }
    }

    private companion object {
        val KEY = booleanPreferencesKey("example_seeded")
    }
}
