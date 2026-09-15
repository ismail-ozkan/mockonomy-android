package com.mockonomy.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Safe defaults of the admin API settings and token normalization. */
class AdminApiSettingsStoreTest {

    private fun store() = AdminApiSettingsStore(
        PreferenceDataStoreFactory.create(
            produceFile = { File.createTempFile("admin-api", ".preferences_pb").apply { deleteOnExit() } },
        ),
    )

    @Test
    fun `defaults sit on the cheap side of a mistake`() = runTest {
        val settings = store().settings.first()
        assertFalse(settings.enabled) // OFF by default
        assertTrue(settings.redactReads) // ON by default
        assertNull(settings.token) // no token
    }

    @Test
    fun `enabled and redact can be changed`() = runTest {
        val store = store()
        store.setEnabled(true)
        store.setRedactReads(false)
        val settings = store.settings.first()
        assertTrue(settings.enabled)
        assertFalse(settings.redactReads)
    }

    @Test
    fun `token can be set and is cleared with a blank value`() = runTest {
        val store = store()
        store.setToken("secret")
        assertEquals("secret", store.settings.first().token)

        store.setToken("   ")
        assertNull(store.settings.first().token) // blank/whitespace → no token

        store.setToken("secret2")
        assertEquals("secret2", store.settings.first().token)
        store.setToken(null)
        assertNull(store.settings.first().token)
    }
}
