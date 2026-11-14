package com.mockonomy.engine.model

import kotlinx.serialization.Serializable

/**
 * A complete mock configuration. Stored on the device as `environments/<id>.json`;
 * this is also the exported file.
 *
 * Schema: `spec/schema/environment-v1.md` — this file mirrors that specification
 * one to one. Update the spec before adding or removing fields.
 *
 * There is no `enabled` field: whether an environment is running is runtime state,
 * not configuration, so it is not stored in the file.
 */
@Serializable
data class Environment(
    val schemaVersion: Int,
    val id: String,
    /** Readable address for the automation API. Falls back to `id` when empty. */
    val key: String? = null,
    val name: String,
    val port: Int,
    val prefix: String = "",
    val defaultLatencyMs: Int = 0,
    val cors: Boolean = false,
    val headers: List<HeaderPair> = emptyList(),
    val proxy: ProxyConfig? = null,
    /** Named state machine. The state feature is off when empty. */
    val states: List<String> = emptyList(),
    /** Initial state. Meaningless when `states` is empty. */
    val initialState: String? = null,
    val routes: List<Route>,
) {
    companion object {
        /** The schema version this code base reads and writes. */
        const val CURRENT_SCHEMA_VERSION: Int = 2

        /** The initial port used when a new environment is created. */
        const val DEFAULT_PORT: Int = 8080

        /**
         * Lowest bindable port. Privileged ports (<1024) cannot be bound on loopback
         * without root, so they are rejected before a socket is opened.
         */
        const val MIN_PORT: Int = 1024

        /** Highest bindable port; above this the value is not a TCP port at all. */
        const val MAX_PORT: Int = 65535
    }
}

@Serializable
data class Route(
    val id: String,
    /** Readable address for the automation API. Falls back to `id` when empty. */
    val key: String? = null,
    val method: HttpMethod,
    val path: String,
    val enabled: Boolean = true,
    val responseMode: ResponseMode = ResponseMode.RULES,
    /** What happens at the end of the list when `responseMode = SEQUENTIAL`. */
    val sequentialBehavior: SequentialBehavior = SequentialBehavior.STAY_ON_LAST,
    val responses: List<RouteResponse>,
)

@Serializable
data class RouteResponse(
    val id: String,
    /** Readable address for the automation API. Falls back to `id` when empty. */
    val key: String? = null,
    val label: String = "",
    val statusCode: Int,
    val headers: List<HeaderPair> = emptyList(),
    val bodyType: BodyType = BodyType.INLINE,
    val body: String = "",
    val bodyFile: String? = null,
    val latencyMs: Int = 0,
    val isDefault: Boolean = false,
    val rulesOperator: RulesOperator = RulesOperator.AND,
    val rules: List<Rule> = emptyList(),
    /** Served only while this state is active. Eligible in every state when empty. */
    val whenState: String? = null,
    /** Changes the active state to this value after being served. */
    val setState: String? = null,
)

/** Evaluated by the rule engine (`spec/behavior/rules.md`). */
@Serializable
data class Rule(
    val target: RuleTarget,
    val key: String = "",
    val operator: RuleOperator,
    val value: String = "",
    val invert: Boolean = false,
)

/** Proxy settings for requests that match no route. */
@Serializable
data class ProxyConfig(
    val enabled: Boolean = false,
    val target: String = "",
    val recordUnmatched: Boolean = false,
    val ignoreTlsErrors: Boolean = false,
)

/** The same `key` may appear more than once (multi-value headers). */
@Serializable
data class HeaderPair(
    val key: String,
    val value: String,
)
