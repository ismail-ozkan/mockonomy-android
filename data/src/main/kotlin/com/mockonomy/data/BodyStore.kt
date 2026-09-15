package com.mockonomy.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * File storage for large response bodies (spec/schema/environment-v1.md, `bodyFile`).
 *
 * Why: the environment file is serialized and written to disk from scratch on every
 * edit (debounced, but still). A 1 MB body inside that file would mean copying
 * megabytes on every save while the editor keeps the document in memory. Bodies
 * above [LIMIT] move to `bodies/<responseId>.json`, and only the file name stays in
 * the environment file.
 */
interface BodyStore {
    /** `null` if the file does not exist or cannot be read — a broken body does not crash the app. */
    suspend fun read(fileName: String): String?

    /** Writes the body and returns the file name to put into the environment file. */
    suspend fun write(responseId: String, content: String): String

    /** Succeeds silently if the file does not exist (idempotent). */
    suspend fun delete(fileName: String)

    companion object {
        /** 100 KB. */
        const val LIMIT: Int = 100 * 1024

        fun fileNameFor(responseId: String): String = "$responseId.json"
    }
}

class FileBodyStore(
    private val fs: FileSystem,
    private val root: Path,
) : BodyStore {

    override suspend fun read(fileName: String): String? = withContext(Dispatchers.IO) {
        val path = root.resolve(fileName)
        if (!fs.exists(path)) return@withContext null
        try {
            fs.read(path) { readUtf8() }
        } catch (error: IOException) {
            null
        }
    }

    override suspend fun write(responseId: String, content: String): String = withContext(Dispatchers.IO) {
        fs.createDirectories(root, mustCreate = false)
        val fileName = BodyStore.fileNameFor(responseId)
        val target = root.resolve(fileName)
        // Atomic, like the environment file: an interrupted write does not corrupt
        // the existing body.
        val temp = root.resolve("$fileName$TEMP_SUFFIX")
        fs.write(temp) { writeUtf8(content) }
        fs.atomicMove(temp, target)
        fileName
    }

    override suspend fun delete(fileName: String): Unit = withContext(Dispatchers.IO) {
        fs.delete(root.resolve(fileName), mustExist = false)
    }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}
