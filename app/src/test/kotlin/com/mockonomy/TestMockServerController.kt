package com.mockonomy

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.mockonomy.data.AdminApiSettingsStore
import com.mockonomy.data.EnvironmentStore
import com.mockonomy.data.EnvironmentSummary
import com.mockonomy.data.log.NoRequestLog
import com.mockonomy.data.log.RequestLog
import com.mockonomy.data.log.RequestLogStore
import com.mockonomy.data.log.RequestLogSummary
import com.mockonomy.engine.model.Environment
import com.mockonomy.server.MockServerController
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * A [MockServerController] factory for ViewModel tests. The admin API dependencies are not
 * exercised functionally in these tests — the server lifecycle and diagnostics are — so
 * no-op stores and temporary DataStores are enough.
 */
internal fun fakeMockServerController(): MockServerController {
    fun tempDataStore() = PreferenceDataStoreFactory.create(
        produceFile = { File.createTempFile("app-ctrl-test", ".preferences_pb").apply { deleteOnExit() } },
    )
    return MockServerController(
        logSink = NoRequestLog,
        environmentStore = NoopEnvironmentStore,
        logStore = NoopRequestLogStore,
        adminSettings = AdminApiSettingsStore(tempDataStore()),
    )
}

private object NoopEnvironmentStore : EnvironmentStore {
    override val saved: Flow<String> = emptyFlow()
    override suspend fun list(): List<EnvironmentSummary> = emptyList()
    override suspend fun load(id: String): Environment? = null
    override suspend fun save(environment: Environment) = Unit
    override suspend fun delete(id: String) = Unit
}

private object NoopRequestLogStore : RequestLogStore {
    override val isRecording: Boolean = false
    override fun record(log: RequestLog) = Unit
    override fun observeSummaries(environmentId: String): Flow<List<RequestLogSummary>> = emptyFlow()
    override suspend fun find(id: String): RequestLog? = null
    override suspend fun findAll(ids: List<String>): List<RequestLog> = emptyList()
    override suspend fun clear(environmentId: String) = Unit
}
