package com.mockonomy.server

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse

/**
 * A fixed environment used by the server tests.
 */
internal object SkeletonEnvironment {

    const val DEFAULT_PORT: Int = Environment.DEFAULT_PORT

    fun create(port: Int = DEFAULT_PORT) = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = "env-skeleton",
        name = "Skeleton",
        port = port,
        headers = listOf(HeaderPair("Content-Type", "application/json")),
        routes = listOf(
            Route(
                id = "route-ping",
                method = HttpMethod.GET,
                path = "/ping",
                responses = listOf(
                    RouteResponse(
                        id = "resp-ok",
                        label = "200 - ok",
                        statusCode = 200,
                        body = """{"status":"ok"}""",
                        isDefault = true,
                    ),
                ),
            ),
            // An extra route with a realistic name.
            Route(
                id = "route-dashboard",
                method = HttpMethod.GET,
                path = "/getDashboardDataV2",
                responses = listOf(
                    RouteResponse(
                        id = "resp-dashboard-ok",
                        label = "200 - ok",
                        statusCode = 200,
                        body = """{"status":"ok","message":"test passed"}""",
                        isDefault = true,
                    ),
                ),
            ),
        ),
    )
}
