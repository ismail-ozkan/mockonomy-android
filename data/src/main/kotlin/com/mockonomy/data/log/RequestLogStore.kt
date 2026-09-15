package com.mockonomy.data.log

import kotlinx.coroutines.flow.Flow

/**
 * The side of the log store that the server sees — **write only, never suspends.**
 *
 * [record] drops the entry into a queue and returns immediately: mock response
 * latency must not depend on how fast logs are written. The server never needs to
 * read from the store, so the interface ends here.
 */
interface RequestLogSink {
    /**
     * So the server does not pay for producing traces while recording is off — it can
     * call `RequestMatcher.serve` instead of `explain`.
     */
    val isRecording: Boolean

    /** Silently dropped when recording is off or the queue is full. */
    fun record(log: RequestLog)
}

/**
 * A sink that records nothing — for callers without a log store (e.g. `:server`
 * integration tests). Using this instead of `null` keeps a single code path in the
 * server: "is recording on?" has exactly one answer.
 */
object NoRequestLog : RequestLogSink {
    override val isRecording: Boolean = false
    override fun record(log: RequestLog) = Unit
}

/** The full interface the UI sees (log list and log detail screens). */
interface RequestLogStore : RequestLogSink {
    /** Newest requests first; a live stream. */
    fun observeSummaries(environmentId: String): Flow<List<RequestLogSummary>>

    suspend fun find(id: String): RequestLog?

    /**
     * Bulk read for export. Order is not preserved — HAR builds its own order by time
     * anyway.
     */
    suspend fun findAll(ids: List<String>): List<RequestLog>

    suspend fun clear(environmentId: String)
}
