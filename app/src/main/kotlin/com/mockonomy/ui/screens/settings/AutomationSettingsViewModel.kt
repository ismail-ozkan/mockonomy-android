package com.mockonomy.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockonomy.data.AdminApiSettings
import com.mockonomy.data.AdminApiSettingsStore
import com.mockonomy.server.MockServerController
import com.mockonomy.server.RunningServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State of the automation (admin API) settings screen.
 *
 * By the security model this feature only works once the user deliberately turns it on. The
 * screen also reads the server state so it can show the base URL — while running it gives a
 * copy-paste address.
 */
@HiltViewModel
class AutomationSettingsViewModel @Inject constructor(
    private val store: AdminApiSettingsStore,
    serverController: MockServerController,
) : ViewModel() {

    val settings: StateFlow<AdminApiSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AdminApiSettings())

    /** Running servers: each one serves its own admin API on its own port. */
    val running: StateFlow<List<RunningServer>> = serverController.running

    fun setEnabled(enabled: Boolean) = viewModelScope.launch { store.setEnabled(enabled) }

    fun setRedactReads(redact: Boolean) = viewModelScope.launch { store.setRedactReads(redact) }

    fun setToken(token: String?) = viewModelScope.launch { store.setToken(token) }

    /** Generates a readable but unguessable token per install. */
    fun generateToken() = viewModelScope.launch { store.setToken(UUID.randomUUID().toString()) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
