package com.mockonomy.data.log

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mockonomy.engine.MatchTrace
import com.mockonomy.engine.model.HeaderPair
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The on-disk form of a log row.
 *
 * Headers and the trace are stored as JSON text. An explicit mapper
 * ([RequestLogMapper]) is used instead of Room type converters, so the conversion
 * can be tested without an emulator.
 *
 * [sizeBytes] is for pruning — it is measured once on write instead of reading the
 * bodies to compute the total size every time.
 */
@Entity(
    tableName = "request_log",
    indices = [Index("environmentId", "timestampMs")],
)
data class RequestLogEntity(
    @PrimaryKey val id: String,
    val environmentId: String,
    val timestampMs: Long,
    val method: String,
    val path: String,
    val query: String,
    val requestHeaders: String,
    val requestBody: String?,
    val requestBodyTruncated: Boolean,
    val statusCode: Int,
    val responseHeaders: String,
    val responseBody: String,
    val responseBodyTruncated: Boolean,
    val durationMs: Long,
    val matchedRouteId: String?,
    val matchedResponseId: String?,
    val matchTrace: String?,
    val sizeBytes: Long,
)

/** The two fields the pruning query reads. */
data class LogSize(
    val id: String,
    val sizeBytes: Long,
)

@Dao
interface RequestLogDao {

    @Insert
    suspend fun insert(entries: List<RequestLogEntity>)

    @Query(
        "SELECT id, timestampMs, method, path, query, statusCode, durationMs " +
            "FROM request_log WHERE environmentId = :environmentId " +
            "ORDER BY timestampMs DESC, id DESC",
    )
    fun observeSummaries(environmentId: String): Flow<List<RequestLogSummary>>

    @Query("SELECT * FROM request_log WHERE id = :id")
    suspend fun find(id: String): RequestLogEntity?

    @Query("SELECT * FROM request_log WHERE id IN (:ids)")
    suspend fun findAll(ids: List<String>): List<RequestLogEntity>

    @Query("DELETE FROM request_log WHERE environmentId = :environmentId")
    suspend fun clear(environmentId: String)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM request_log")
    suspend fun totalBytes(): Long

    /**
     * Keeps the newest [keep] records and deletes the rest. In SQLite
     * `LIMIT -1 OFFSET :keep` means "skip the first N, take all the rest".
     */
    @Query(
        "DELETE FROM request_log WHERE id IN (" +
            "SELECT id FROM request_log ORDER BY timestampMs DESC, id DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun trimToCount(keep: Int)

    @Query("SELECT id, sizeBytes FROM request_log ORDER BY timestampMs ASC, id ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<LogSize>

    @Query("DELETE FROM request_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}

/**
 * Field-by-field mapping. With broken JSON (e.g. a trace written by an older version)
 * the record is not dropped; only that field stays empty — an incomplete log beats an
 * unreadable one.
 */
object RequestLogMapper {

    private val json = Json { ignoreUnknownKeys = true }
    private val headerListSerializer = ListSerializer(HeaderPair.serializer())

    fun toEntity(log: RequestLog): RequestLogEntity {
        val requestHeaders = json.encodeToString(headerListSerializer, log.requestHeaders)
        val responseHeaders = json.encodeToString(headerListSerializer, log.responseHeaders)
        val trace = log.trace?.let { json.encodeToString(MatchTrace.serializer(), it) }
        return RequestLogEntity(
            id = log.id,
            environmentId = log.environmentId,
            timestampMs = log.timestampMs,
            method = log.method,
            path = log.path,
            query = log.query,
            requestHeaders = requestHeaders,
            requestBody = log.requestBody,
            requestBodyTruncated = log.requestBodyTruncated,
            statusCode = log.statusCode,
            responseHeaders = responseHeaders,
            responseBody = log.responseBody,
            responseBodyTruncated = log.responseBodyTruncated,
            durationMs = log.durationMs,
            matchedRouteId = log.matchedRouteId,
            matchedResponseId = log.matchedResponseId,
            matchTrace = trace,
            sizeBytes = (
                (log.requestBody?.length ?: 0) + log.responseBody.length +
                    requestHeaders.length + responseHeaders.length + (trace?.length ?: 0)
                ).toLong(),
        )
    }

    fun toDomain(entity: RequestLogEntity) = RequestLog(
        id = entity.id,
        environmentId = entity.environmentId,
        timestampMs = entity.timestampMs,
        method = entity.method,
        path = entity.path,
        query = entity.query,
        requestHeaders = decodeHeaders(entity.requestHeaders),
        requestBody = entity.requestBody,
        requestBodyTruncated = entity.requestBodyTruncated,
        statusCode = entity.statusCode,
        responseHeaders = decodeHeaders(entity.responseHeaders),
        responseBody = entity.responseBody,
        responseBodyTruncated = entity.responseBodyTruncated,
        durationMs = entity.durationMs,
        matchedRouteId = entity.matchedRouteId,
        matchedResponseId = entity.matchedResponseId,
        trace = entity.matchTrace?.let {
            runCatching { json.decodeFromString(MatchTrace.serializer(), it) }.getOrNull()
        },
    )

    private fun decodeHeaders(raw: String): List<HeaderPair> =
        runCatching { json.decodeFromString(headerListSerializer, raw) }.getOrDefault(emptyList())
}
