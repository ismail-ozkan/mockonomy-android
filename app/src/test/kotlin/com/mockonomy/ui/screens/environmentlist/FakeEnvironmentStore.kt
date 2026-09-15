package com.mockonomy.ui.screens.environmentlist

import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.engine.model.Environment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** An in-memory fake store — keeps ViewModel tests independent of the file system. */
class FakeEnvironmentStore : EnvironmentStore {
    private val environments = mutableMapOf<String, Environment>()

    private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val saved: Flow<String> = _saved.asSharedFlow()

    override suspend fun list(): List<EnvironmentSummary> =
        environments.values.sortedBy { it.id }.map { EnvironmentSummary.Valid(it) }

    override suspend fun load(id: String): Environment? = environments[id]

    override suspend fun save(environment: Environment) {
        environments[environment.id] = environment
        _saved.tryEmit(environment.id)
    }

    override suspend fun delete(id: String) {
        environments.remove(id)
    }
}
