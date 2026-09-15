package com.mockonomy.server

import com.mockonomy.engine.model.HeaderPair
import kotlinx.serialization.Serializable

/**
 * Wire types of the admin API. Kept separate from engine/data models: the automation
 * contract must not break when internal models change. The field names are the
 * contract documented in `docs/automation-api.md`.
 */

@Serializable
internal data class ErrorBody(
    val error: String,
    val message: String,
    /** The available options for an unknown key/state/id — makes debugging easier. */
    val available: List<String>? = null,
)

@Serializable
internal data class OkBody(val message: String)

@Serializable
internal data class HealthBody(
    val status: String,
    val running: Boolean,
    val environment: String?,
    val environmentKey: String?,
    val activeState: String?,
    val port: Int?,
)

@Serializable
internal data class EnvironmentBody(
    val id: String,
    val key: String?,
    val name: String,
    val port: Int,
    val active: Boolean,
)

@Serializable
internal data class EnvironmentListBody(val environments: List<EnvironmentBody>)

/** Body of `PUT /routes/{key}/response`: the response `key` or `id`. */
@Serializable
internal data class OverrideRequest(val key: String? = null, val id: String? = null)

/** Body of `POST /state`. */
@Serializable
internal data class StateRequest(val state: String)

@Serializable
internal data class LogBody(
    val id: String,
    val timestampMs: Long,
    val method: String,
    val path: String,
    val query: String,
    val statusCode: Int,
    val durationMs: Long,
    val requestHeaders: List<HeaderPair>,
    val requestBody: String?,
    val responseHeaders: List<HeaderPair>,
    val responseBody: String,
    val matchedRouteId: String?,
    val matchedResponseId: String?,
)

@Serializable
internal data class LogListBody(val count: Int, val logs: List<LogBody>)
