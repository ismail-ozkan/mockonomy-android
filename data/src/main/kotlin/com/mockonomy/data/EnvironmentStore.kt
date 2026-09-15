package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import kotlinx.coroutines.flow.Flow

/**
 * The single read/write point for environment files. Because every change is saved
 * immediately, [save] may be called often; the caller (a ViewModel) is expected to
 * debounce bursts of calls, while this interface writes every call to disk as is.
 */
interface EnvironmentStore {
    /**
     * Ids of environments that were saved successfully.
     *
     * The hot-reload trigger: `MockServerService` listens to this and updates a running
     * environment without restarting the server when it is edited.
     */
    val saved: Flow<String>

    /** All valid and corrupt files — a corrupt file does not stop listing. */
    suspend fun list(): List<EnvironmentSummary>

    /** `null` if `id` is not found or the file is corrupt. */
    suspend fun load(id: String): Environment?

    /** Writes atomically: an interrupted operation does not corrupt the existing file. */
    suspend fun save(environment: Environment)

    /** Succeeds silently if `id` does not exist (idempotent). */
    suspend fun delete(id: String)
}
