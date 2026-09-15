package com.mockonomy.data.log

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A buffered log store on top of Room.
 *
 * **Writes never block the server thread.** [record] drops the entry into a channel
 * and returns immediately; disk writes happen in batches in a single consumer loop in
 * [scope]. Mock response latency must not depend on log writes — if latency is the
 * one thing the product measures, distorting it with our own infrastructure is
 * unacceptable.
 *
 * When the queue is full **the oldest entry is dropped** ([BufferOverflow.DROP_OLDEST]).
 * The alternative was suspending, which would break the promise of not blocking.
 * Losing a few log entries under load is better than delaying the response.
 */
class RoomRequestLogStore(
    private val dao: RequestLogDao,
    retention: Flow<LogRetention>,
    scope: CoroutineScope,
    private val bodyLimit: Int = RequestLog.BODY_LIMIT,
) : RequestLogStore {

    @Volatile
    private var limits = LogRetention()

    override val isRecording: Boolean get() = limits.enabled

    private val queue = Channel<RequestLog>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { retention.collect { limits = it } }
        scope.launch { drain() }
    }

    override fun record(log: RequestLog) {
        if (!limits.enabled) return
        queue.trySend(log)
    }

    override fun observeSummaries(environmentId: String): Flow<List<RequestLogSummary>> =
        dao.observeSummaries(environmentId)

    override suspend fun find(id: String): RequestLog? =
        dao.find(id)?.let(RequestLogMapper::toDomain)

    /**
     * SQLite limits the number of variables per query (999); since this can be called
     * with thousands of ids, the read is split into chunks.
     */
    override suspend fun findAll(ids: List<String>): List<RequestLog> =
        ids.chunked(ID_CHUNK).flatMap { chunk -> dao.findAll(chunk).map(RequestLogMapper::toDomain) }

    override suspend fun clear(environmentId: String) = dao.clear(environmentId)

    /**
     * Waits for one entry, then collects whatever else is ready in the queue and writes
     * it in one go. This is what reduces write count under load: 50 requests become
     * one transaction, not 50.
     */
    private suspend fun drain() {
        while (true) {
            val batch = ArrayList<RequestLog>(BATCH_SIZE)
            batch += queue.receive()
            while (batch.size < BATCH_SIZE) {
                batch += queue.tryReceive().getOrNull() ?: break
            }
            dao.insert(batch.map { RequestLogMapper.toEntity(it.truncated(bodyLimit)) })
            prune()
        }
    }

    /**
     * Record count first, then total size. Size pruning uses a loop because how many
     * records must go is only known once their sizes are added up; a single SQL query
     * with window functions might not work on the SQLite versions of older Android
     * releases (minSdk 26).
     */
    private suspend fun prune() {
        dao.trimToCount(limits.maxCount)

        var total = dao.totalBytes()
        while (total > limits.maxBytes) {
            val oldest = dao.oldest(PRUNE_BATCH_SIZE)
            if (oldest.isEmpty()) break

            val doomed = mutableListOf<String>()
            for (row in oldest) {
                doomed += row.id
                total -= row.sizeBytes
                if (total <= limits.maxBytes) break
            }
            dao.deleteByIds(doomed)
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 256
        const val BATCH_SIZE = 50
        const val PRUNE_BATCH_SIZE = 100
        const val ID_CHUNK = 500
    }
}
