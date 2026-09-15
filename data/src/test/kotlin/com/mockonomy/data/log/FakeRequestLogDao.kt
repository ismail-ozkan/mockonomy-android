package com.mockonomy.data.log

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The Room-generated DAO needs an emulator; the store's logic (buffering, truncation,
 * pruning) does not. This fake mimics the **meaning** of the queries in memory — the
 * SQL itself is verified on a device.
 */
class FakeRequestLogDao : RequestLogDao {

    private val rows = MutableStateFlow<List<RequestLogEntity>>(emptyList())

    /** In insertion order. */
    val stored: List<RequestLogEntity> get() = rows.value

    /** How many times `insert` was called — the counter that shows buffering works. */
    var insertCount: Int = 0
        private set

    override suspend fun insert(entries: List<RequestLogEntity>) {
        insertCount++
        rows.value = rows.value + entries
    }

    override fun observeSummaries(environmentId: String): Flow<List<RequestLogSummary>> =
        rows.map { all ->
            all.filter { it.environmentId == environmentId }
                .sortedByDescending { it.timestampMs }
                .map {
                    RequestLogSummary(
                        id = it.id,
                        timestampMs = it.timestampMs,
                        method = it.method,
                        path = it.path,
                        query = it.query,
                        statusCode = it.statusCode,
                        durationMs = it.durationMs,
                    )
                }
        }

    override suspend fun find(id: String): RequestLogEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun findAll(ids: List<String>): List<RequestLogEntity> =
        rows.value.filter { it.id in ids }

    override suspend fun clear(environmentId: String) {
        rows.value = rows.value.filterNot { it.environmentId == environmentId }
    }

    override suspend fun totalBytes(): Long = rows.value.sumOf { it.sizeBytes }

    override suspend fun trimToCount(keep: Int) {
        rows.value = rows.value.sortedByDescending { it.timestampMs }.take(keep)
    }

    override suspend fun oldest(limit: Int): List<LogSize> =
        rows.value.sortedBy { it.timestampMs }.take(limit).map { LogSize(it.id, it.sizeBytes) }

    override suspend fun deleteByIds(ids: List<String>) {
        rows.value = rows.value.filterNot { it.id in ids }
    }
}
