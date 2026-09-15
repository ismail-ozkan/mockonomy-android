package com.mockonomy.ui.screens.environmentlist

import com.mockonomy.data.BodyStore

/** An in-memory fake body store — file names follow the same rule as the real one. */
class FakeBodyStore : BodyStore {
    private val files = mutableMapOf<String, String>()

    override suspend fun read(fileName: String): String? = files[fileName]

    override suspend fun write(responseId: String, content: String): String {
        val fileName = BodyStore.fileNameFor(responseId)
        files[fileName] = content
        return fileName
    }

    override suspend fun delete(fileName: String) {
        files.remove(fileName)
    }

    fun fileNames(): Set<String> = files.keys.toSet()
}
