package com.mockonomy.ui.editing

import com.mockonomy.data.EnvironmentStore
import com.mockonomy.engine.model.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * An editable copy of an environment file.
 *
 * **Why it exists:** three screens edit the same file — environment detail, route edit and
 * response edit. They are separate back stack entries, so they cannot share a single
 * ViewModel instance; each keeps its own copy. If these copies are not kept in sync, the
 * last writer overwrites the other's change — this happened on a device: a `500` status
 * code set on a response disappeared after going back to the route screen and adding a
 * response.
 *
 * The fix is synchronization through the file: every copy listens to
 * [EnvironmentStore.saved] and refreshes itself when it sees *someone else's* write.
 *
 * Saving happens at two speeds:
 * - [update] for text input, with a 500 ms debounce
 * - [updateNow] for structural changes, immediately — the user may navigate into what they
 *   just added before the debounce window ends
 */
@OptIn(FlowPreview::class)
class EnvironmentDocument(
    private val environmentId: String,
    private val environmentStore: EnvironmentStore,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<Environment?>(null)
    val state: StateFlow<Environment?> = _state.asStateFlow()

    /**
     * Whether the first read attempt has finished. `state == null` alone cannot tell
     * "loading" from "file missing"; without that distinction, opening an environment whose
     * file was deleted would leave the user on an empty screen with no explanation.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * `DROP_OLDEST` is required: with the default `SUSPEND`, the second of two back-to-back
     * `tryEmit` calls is silently dropped — and since the debounce wants exactly that *last*
     * value, the save would silently be lost.
     */
    private val saveRequests = MutableSharedFlow<Environment>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Whether there is a local edit that has not reached the disk yet.
     *
     * The refresh decision looks at this: if we read our own write back and overwrote the
     * state, keys typed while the write was in progress would be erased. An external change
     * that arrives while dirty is ignored — two screens cannot be in the foreground at the
     * same time, so in practice this is not a conflict but the echo of our own write.
     */
    @Volatile
    private var dirty: Boolean = false

    init {
        scope.launch {
            _state.value = environmentStore.load(environmentId)
            _loaded.value = true
        }

        scope.launch {
            saveRequests.debounce(SAVE_DEBOUNCE_MS).collect { write(it) }
        }

        scope.launch {
            environmentStore.saved.filter { it == environmentId }.collect {
                if (dirty) return@collect
                val fresh = environmentStore.load(environmentId) ?: return@collect
                if (fresh != _state.value) _state.value = fresh
            }
        }
    }

    fun update(transform: (Environment) -> Environment) {
        val updated = transform(_state.value ?: return)
        _state.value = updated
        dirty = true
        saveRequests.tryEmit(updated)
    }

    fun updateNow(transform: (Environment) -> Environment) {
        val updated = transform(_state.value ?: return)
        _state.value = updated
        dirty = true
        // A pending debounced write may hold an old copy; drop the latest one there too so
        // it does not overwrite this later.
        saveRequests.tryEmit(updated)
        scope.launch { write(updated) }
    }

    /** For writing after a suspending step such as moving a body to a file. */
    suspend fun replace(updated: Environment) {
        _state.value = updated
        dirty = true
        saveRequests.tryEmit(updated)
        write(updated)
    }

    private suspend fun write(environment: Environment) {
        environmentStore.save(environment)
        // The user may have kept typing during the write; then we are still dirty.
        if (_state.value == environment) dirty = false
    }

    private companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
