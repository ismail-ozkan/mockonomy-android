package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.HeaderPair
import com.mockonomy.engine.model.HttpMethod
import com.mockonomy.engine.model.Route
import com.mockonomy.engine.model.RouteResponse

/**
 * The example environment loaded on first launch.
 *
 * Goal: the user understands what the product does within 30 seconds. So the content
 * is realistic — a few routes, a path parameter, different status codes and
 * **multiple responses**. The last one matters most: even without rules, the "now
 * let me test the 401 scenario" flow becomes visible here.
 *
 * It lives in `:data` because both first-launch seeding and the "load example" action
 * in the empty state use the same content.
 */
object ExampleEnvironment {

    fun create(): Environment = Environment(
        schemaVersion = Environment.CURRENT_SCHEMA_VERSION,
        id = IdGenerator.generate("env"),
        name = "Example API",
        port = Environment.DEFAULT_PORT,
        headers = listOf(HeaderPair("Content-Type", "application/json")),
        routes = listOf(ping(), users(), userById(), login()),
    )

    private fun ping() = route(
        method = HttpMethod.GET,
        path = "/ping",
        responses = listOf(
            response(label = "ok", statusCode = 200, body = """{"status":"ok"}""", isDefault = true),
        ),
    )

    private fun users() = route(
        method = HttpMethod.GET,
        path = "/users",
        responses = listOf(
            response(
                label = "three users",
                statusCode = 200,
                isDefault = true,
                body = """
                    [
                      {"id": "1", "name": "Ada Lovelace", "email": "ada@example.com"},
                      {"id": "2", "name": "Alan Turing", "email": "alan@example.com"},
                      {"id": "3", "name": "Grace Hopper", "email": "grace@example.com"}
                    ]
                """.trimIndent(),
            ),
        ),
    )

    /** Two responses: the default 200 and a 404 that can be picked by hand (see "Switch response"). */
    private fun userById() = route(
        method = HttpMethod.GET,
        path = "/users/:id",
        responses = listOf(
            response(
                label = "found",
                statusCode = 200,
                isDefault = true,
                body = """{"id": "1", "name": "Ada Lovelace", "email": "ada@example.com"}""",
            ),
            response(
                label = "not found",
                statusCode = 404,
                body = """{"error": "user_not_found"}""",
            ),
        ),
    )

    private fun login() = route(
        method = HttpMethod.POST,
        path = "/auth/login",
        responses = listOf(
            response(
                label = "success",
                statusCode = 200,
                isDefault = true,
                body = """{"token": "eyJhbGciOiJIUzI1NiJ9.example", "expiresIn": 3600}""",
            ),
            response(
                label = "unauthorized",
                statusCode = 401,
                body = """{"error": "invalid_credentials"}""",
            ),
        ),
    )

    private fun route(method: HttpMethod, path: String, responses: List<RouteResponse>) = Route(
        id = IdGenerator.generate("route"),
        method = method,
        path = path,
        responses = responses,
    )

    private fun response(
        label: String,
        statusCode: Int,
        body: String,
        isDefault: Boolean = false,
    ) = RouteResponse(
        id = IdGenerator.generate("resp"),
        label = label,
        statusCode = statusCode,
        body = body,
        isDefault = isDefault,
    )
}
