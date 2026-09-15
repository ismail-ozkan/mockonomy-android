package com.mockonomy.data.log

import com.mockonomy.engine.MatchTrace
import com.mockonomy.engine.RouteAttempt
import com.mockonomy.engine.RuleEvaluation
import com.mockonomy.engine.SkipReason
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.Rule
import com.mockonomy.engine.model.RuleOperator
import com.mockonomy.engine.model.RuleTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RequestLogStoreTest {

    private val dao = FakeRequestLogDao()

    private fun log(
        id: String = "log-1",
        environmentId: String = "env-1",
        timestampMs: Long = 1_000,
        path: String = "/users/7",
        requestBody: String? = null,
        responseBody: String = """{"ok":true}""",
        trace: MatchTrace? = null,
    ) = RequestLog(
        id = id,
        environmentId = environmentId,
        timestampMs = timestampMs,
        method = "GET",
        path = path,
        query = "simulate=missing",
        requestHeaders = listOf(HeaderPair("Accept", "application/json")),
        requestBody = requestBody,
        statusCode = 200,
        responseHeaders = listOf(HeaderPair("Content-Type", "application/json")),
        responseBody = responseBody,
        durationMs = 12,
        matchedRouteId = "route-1",
        matchedResponseId = "resp-1",
        trace = trace,
    )

    @Test
    fun `entries are written in batches`() = runTest {
        val store = RoomRequestLogStore(dao, flowOf(LogRetention()), backgroundScope)

        repeat(3) { index -> store.record(log(id = "log-$index", timestampMs = index.toLong())) }
        runCurrent()

        assertEquals(3, dao.stored.size)
        assertEquals(1, dao.insertCount, "three entries should have gone in a single write")
    }

    @Test
    fun `nothing is written while recording is off`() = runTest {
        val store = RoomRequestLogStore(dao, flowOf(LogRetention(enabled = false)), backgroundScope)
        runCurrent()

        assertFalse(store.isRecording)
        store.record(log())
        runCurrent()

        assertEquals(0, dao.stored.size)
    }

    @Test
    fun `recording turns back on when the setting changes`() = runTest {
        val retention = MutableStateFlow(LogRetention(enabled = false))
        val store = RoomRequestLogStore(dao, retention, backgroundScope)
        runCurrent()

        retention.value = LogRetention(enabled = true)
        runCurrent()
        store.record(log())
        runCurrent()

        assertEquals(1, dao.stored.size)
    }

    @Test
    fun `a large body is truncated and marked`() = runTest {
        val limit = 64
        val store = RoomRequestLogStore(dao, flowOf(LogRetention()), backgroundScope, bodyLimit = limit)

        store.record(log(requestBody = "r".repeat(limit * 2), responseBody = "x".repeat(limit * 2)))
        runCurrent()

        val row = dao.stored.single()
        assertEquals(limit, row.requestBody?.length)
        assertEquals(limit, row.responseBody.length)
        assertTrue(row.requestBodyTruncated)
        assertTrue(row.responseBodyTruncated)
    }

    @Test
    fun `a body under the limit is not marked truncated`() = runTest {
        val store = RoomRequestLogStore(dao, flowOf(LogRetention()), backgroundScope, bodyLimit = 64)

        store.record(log(responseBody = "x".repeat(64)))
        runCurrent()

        assertFalse(dao.stored.single().responseBodyTruncated)
    }

    @Test
    fun `oldest entries are deleted when the record count is exceeded`() = runTest {
        val store = RoomRequestLogStore(dao, flowOf(LogRetention(maxCount = 5)), backgroundScope)

        repeat(8) { index -> store.record(log(id = "log-$index", timestampMs = index.toLong())) }
        runCurrent()

        assertEquals(5, dao.stored.size)
        assertEquals(
            listOf("log-3", "log-4", "log-5", "log-6", "log-7"),
            dao.stored.map { it.id }.sorted(),
        )
    }

    @Test
    fun `oldest entries are deleted when the total size is exceeded`() = runTest {
        // Each entry's body is 100 characters; the limit is chosen to fit fewer than
        // three entries.
        val oneRow = RequestLogMapper.toEntity(log(responseBody = "x".repeat(100))).sizeBytes
        val store = RoomRequestLogStore(
            dao,
            flowOf(LogRetention(maxBytes = oneRow * 2)),
            backgroundScope,
        )

        repeat(5) { index ->
            store.record(
                log(id = "log-$index", timestampMs = index.toLong(), responseBody = "x".repeat(100)),
            )
        }
        runCurrent()

        assertTrue(dao.totalBytes() <= oneRow * 2, "pruning should have gone below the limit")
        assertTrue(dao.stored.all { it.id in listOf("log-3", "log-4") }, "the newest should have stayed")
    }

    @Test
    fun `an entry is read back exactly`() = runTest {
        val trace = MatchTrace(
            effectivePath = "/users/7",
            attemptedRoutes = listOf(RouteAttempt("route-1", "GET", "/users/:id", skipReason = null)),
            matchedRouteId = "route-1",
            evaluatedRules = listOf(
                RuleEvaluation(
                    responseId = "resp-1",
                    rule = Rule(RuleTarget.QUERY, "simulate", RuleOperator.EQUALS, "missing"),
                    readValue = "missing",
                    passed = true,
                ),
            ),
            matchedResponseId = "resp-1",
        )
        val store = RoomRequestLogStore(dao, flowOf(LogRetention()), backgroundScope)

        store.record(log(trace = trace))
        runCurrent()

        val stored = assertNotNull(store.find("log-1"))
        assertEquals(listOf(HeaderPair("Accept", "application/json")), stored.requestHeaders)
        assertEquals(trace, stored.trace)
    }

    @Test
    fun `the skip reason of a route is read back too`() = runTest {
        val trace = MatchTrace(
            effectivePath = "/users/7",
            attemptedRoutes = listOf(RouteAttempt("route-2", "GET", "/users", SkipReason.PATH_MISMATCH)),
        )
        val store = RoomRequestLogStore(dao, flowOf(LogRetention()), backgroundScope)

        store.record(log(trace = trace))
        runCurrent()

        assertEquals(trace, assertNotNull(store.find("log-1")).trace)
    }

    @Test
    fun `logs are scoped to the environment`() = runTest {
        val store = RoomRequestLogStore(dao, flowOf(LogRetention()), backgroundScope)

        store.record(log(id = "a", environmentId = "env-1"))
        store.record(log(id = "b", environmentId = "env-2"))
        runCurrent()

        assertEquals(listOf("a"), store.observeSummaries("env-1").first().map { it.id })

        store.clear("env-1")
        assertEquals(emptyList(), store.observeSummaries("env-1").first())
        assertEquals(listOf("b"), store.observeSummaries("env-2").first().map { it.id })
    }
}
