package com.mockonomy.ui.screens.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.mockoon.MockoonImport
import com.mockonomy.data.openapi.ImportPreview
import com.mockonomy.data.openapi.ImportResult
import com.mockonomy.data.openapi.OpenApiImport
import com.mockonomy.data.openapi.toEnvironment
import com.mockonomy.data.openapi.withImported
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Mock file import (OpenAPI and Mockoon). */
sealed interface ImportUiState {
    data object Empty : ImportUiState
    data object Parsing : ImportUiState
    data class Failed(val message: String) : ImportUiState
    data class Ready(val preview: ImportPreview) : ImportUiState
    data class Imported(val environmentId: String) : ImportUiState
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val environmentStore: EnvironmentStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Empty)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Environments the import can be added to — corrupt files cannot be targets. */
    private val _targets = MutableStateFlow<List<EnvironmentSummary.Valid>>(emptyList())
    val targets: StateFlow<List<EnvironmentSummary.Valid>> = _targets.asStateFlow()

    init {
        viewModelScope.launch {
            _targets.value = environmentStore.list().filterIsInstance<EnvironmentSummary.Valid>()
        }
    }

    /**
     * Parsing does not happen on the main thread: real files can be megabytes (Stripe's
     * schema is 6 MB).
     *
     * The format is picked from the file content: Mockoon if it looks like Mockoon, OpenAPI
     * otherwise. Both parsers produce the same [ImportResult]/[ImportPreview]; nothing below
     * tells them apart.
     */
    fun parse(text: String) {
        _state.value = ImportUiState.Parsing
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                if (MockoonImport.looksLikeMockoon(text)) MockoonImport.parse(text) else OpenApiImport.parse(text)
            }
            _state.value = when (result) {
                is ImportResult.Failure -> ImportUiState.Failed(result.message)
                is ImportResult.Success -> ImportUiState.Ready(result.preview)
            }
        }
    }

    /** The file could not be read — the reason appears the same way, where the user looks. */
    fun failed(message: String) {
        _state.value = ImportUiState.Failed(message)
    }

    fun reset() {
        _state.value = ImportUiState.Empty
    }

    /** With a `null` [targetId] a new environment is created; otherwise the routes are appended. */
    fun import(preview: ImportPreview, targetId: String?) {
        viewModelScope.launch {
            val environment = if (targetId == null) {
                preview.toEnvironment()
            } else {
                environmentStore.load(targetId)?.withImported(preview) ?: return@launch
            }
            environmentStore.save(environment)
            _state.value = ImportUiState.Imported(environment.id)
        }
    }
}
