package com.mockonomy.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockonomy.data.AppearanceSettingsStore
import com.mockonomy.data.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The theme preference picker. The segmented control on the settings screen uses this
 * ViewModel. Persisted by `AppearanceSettingsStore`.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: AppearanceSettingsStore,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = store.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ThemeMode.DARK)

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { store.setThemeMode(mode) }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
