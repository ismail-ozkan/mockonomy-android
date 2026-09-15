package com.mockonomy.ui.screens.logs

import com.mockonomy.data.log.RequestLogSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A status **class** filter — not individual codes.
 *
 * The question during manual testing is not "did it return 404" but "did something break";
 * telling 401 from 404 is the job of reading the row, not of filtering the list.
 */
enum class StatusClass(val label: String, private val hundreds: Int?) {
    All("All", null),
    Success("2xx", 2),
    ClientError("4xx", 4),
    ServerError("5xx", 5),
    ;

    fun accepts(statusCode: Int): Boolean = hundreds == null || statusCode / 100 == hundreds
}

/**
 * A single search field filters both path and method — the same decision as the route list
 * (`RoutesTab`): a second dropdown is not easier than typing `POST`.
 */
fun List<RequestLogSummary>.filterLogs(query: String, statusClass: StatusClass): List<RequestLogSummary> {
    val trimmed = query.trim()
    return filter { log ->
        statusClass.accepts(log.statusCode) &&
            (
                trimmed.isEmpty() ||
                    log.path.contains(trimmed, ignoreCase = true) ||
                    log.method.contains(trimmed, ignoreCase = true) ||
                    log.query.contains(trimmed, ignoreCase = true)
                )
    }
}

/**
 * The time on a log row. No date: the ring buffer holds a few thousand entries and the user
 * is almost always looking at "the request I just sent". The device's local time is used —
 * the engine uses UTC for the output it produces, while here it is the user's own clock
 * being read.
 */
fun formatLogTime(timestampMs: Long): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

/** The full timestamp on the detail screen. */
fun formatLogTimestamp(timestampMs: Long): String =
    TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

/** A timestamp usable in a file name: no colons or spaces. */
fun formatFileStamp(timestampMs: Long): String =
    FILE_STAMP_FORMATTER.format(Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()))

private val FILE_STAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
