package com.mockonomy.data

import com.mockonomy.engine.model.BodyType
import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.RouteResponse

/**
 * The single place that decides whether a body is stored inline or in a file. The
 * user is not asked — the threshold is an implementation detail, not a decision for
 * the user.
 */

/** A body above the threshold moves to a file; one that shrinks below it moves back inline. */
suspend fun BodyStore.applyBody(response: RouteResponse, body: String): RouteResponse {
    val tooLarge = body.encodeToByteArray().size > BodyStore.LIMIT
    if (tooLarge) {
        val fileName = write(response.id, body)
        return response.copy(bodyType = BodyType.FILE, bodyFile = fileName, body = "")
    }

    // Do not leave the file of a body that shrank behind.
    response.bodyFile?.let { delete(it) }
    return response.copy(bodyType = BodyType.INLINE, body = body, bodyFile = null)
}

/** The body the editor shows. Empty if the file cannot be read — empty instead of crashing. */
suspend fun BodyStore.loadBody(response: RouteResponse): String =
    if (response.bodyType == BodyType.FILE) {
        response.bodyFile?.let { read(it) }.orEmpty()
    } else {
        response.body
    }

/**
 * Pulls file-backed bodies inline.
 *
 * `:engine` is pure and synchronous; it cannot and should not read files. So the
 * environment is resolved here **before** it is handed to the server; the engine
 * only ever sees `INLINE`, and the `bodyType=FILE` error path never runs.
 */
suspend fun BodyStore.hydrate(environment: Environment): Environment {
    if (environment.routes.none { route -> route.responses.any { it.bodyType == BodyType.FILE } }) {
        return environment
    }

    return environment.copy(
        routes = environment.routes.map { route ->
            route.copy(
                responses = route.responses.map { response ->
                    if (response.bodyType == BodyType.FILE) {
                        response.copy(bodyType = BodyType.INLINE, body = loadBody(response), bodyFile = null)
                    } else {
                        response
                    }
                },
            )
        },
    )
}

/** Also removes the body files of a deleted environment — otherwise orphan files remain. */
suspend fun BodyStore.deleteBodiesOf(environment: Environment) {
    environment.routes
        .flatMap { it.responses }
        .mapNotNull { it.bodyFile }
        .forEach { delete(it) }
}
