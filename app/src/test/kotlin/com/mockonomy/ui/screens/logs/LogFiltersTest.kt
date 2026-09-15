package com.mockonomy.ui.screens.logs

import com.mockonomy.data.log.RequestLogSummary
import kotlin.test.Test
import kotlin.test.assertEquals

class LogFiltersTest {

    private fun summary(
        id: String,
        method: String = "GET",
        path: String = "/users/7",
        query: String = "",
        statusCode: Int = 200,
    ) = RequestLogSummary(
        id = id,
        timestampMs = 0,
        method = method,
        path = path,
        query = query,
        statusCode = statusCode,
        durationMs = 3,
    )

    private val logs = listOf(
        summary("a", path = "/users/7"),
        summary("b", method = "POST", path = "/auth/login", statusCode = 401),
        summary("c", path = "/users/9", query = "simulate=missing", statusCode = 404),
        summary("d", path = "/boom", statusCode = 500),
    )

    @Test
    fun `everything is visible without a filter`() {
        assertEquals(logs, logs.filterLogs(query = "", statusClass = StatusClass.All))
    }

    @Test
    fun `path search`() {
        assertEquals(listOf("a", "c"), logs.filterLogs("users", StatusClass.All).map { it.id })
    }

    @Test
    fun `method search needs no separate control`() {
        assertEquals(listOf("b"), logs.filterLogs("post", StatusClass.All).map { it.id })
    }

    @Test
    fun `the query string is searched too`() {
        assertEquals(listOf("c"), logs.filterLogs("simulate", StatusClass.All).map { it.id })
    }

    @Test
    fun `status class filters`() {
        assertEquals(listOf("a"), logs.filterLogs("", StatusClass.Success).map { it.id })
        assertEquals(listOf("b", "c"), logs.filterLogs("", StatusClass.ClientError).map { it.id })
        assertEquals(listOf("d"), logs.filterLogs("", StatusClass.ServerError).map { it.id })
    }

    @Test
    fun `search and class work together`() {
        assertEquals(listOf("c"), logs.filterLogs("users", StatusClass.ClientError).map { it.id })
    }

    @Test
    fun `whitespace is ignored`() {
        assertEquals(listOf("b"), logs.filterLogs("  login  ", StatusClass.All).map { it.id })
    }
}
