package com.mockonomy.data

import com.mockonomy.engine.model.Environment

/**
 * A real copy of an environment.
 *
 * Copying used to change only the environment id while route and response ids were
 * shared. Once body files existed this became data loss: the file name is
 * `<responseId>.json`, so two environments pointed at the same file, and deleting one
 * took the other's body with it.
 *
 * A copy now gets new ids at every level and body files are duplicated too — what
 * the word "copy" already promises.
 */
suspend fun duplicateEnvironment(source: Environment, bodyStore: BodyStore): Environment {
    val routes = source.routes.map { route ->
        route.copy(
            id = IdGenerator.generate("route"),
            responses = route.responses.map { response ->
                val copiedId = IdGenerator.generate("resp")
                val copiedFile = response.bodyFile?.let { fileName ->
                    bodyStore.write(copiedId, bodyStore.read(fileName).orEmpty())
                }
                response.copy(id = copiedId, bodyFile = copiedFile)
            },
        )
    }

    return source.copy(
        id = IdGenerator.generate("env"),
        name = "${source.name} copy",
        routes = routes,
    )
}
