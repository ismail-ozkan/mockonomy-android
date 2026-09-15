package com.mockonomy.data

import com.mockonomy.engine.model.Environment
import com.mockonomy.engine.model.EnvironmentJson
import com.mockonomy.engine.model.SchemaMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okio.FileSystem
import okio.IOException
import okio.Path

/** A `schemaVersion` that this version of the app does not understand. */
class UnsupportedSchemaVersionException(version: Int) : IllegalStateException(
    "Unsupported schemaVersion: $version (this app understands ${Environment.CURRENT_SCHEMA_VERSION})",
)

/**
 * The real, file-system-based implementation of [EnvironmentStore].
 *
 * [fs] and [root] are injected so tests can run in milliseconds with
 * `okio.fakefilesystem.FakeFileSystem` without touching the real disk.
 */
class FileEnvironmentStore(
    private val fs: FileSystem,
    private val root: Path,
) : EnvironmentStore {

    private val _saved = MutableSharedFlow<String>(
        extraBufferCapacity = SAVE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val saved: Flow<String> = _saved.asSharedFlow()

    override suspend fun list(): List<EnvironmentSummary> = withContext(Dispatchers.IO) {
        ensureRoot()
        fs.list(root)
            .filter { it.name.endsWith(EXTENSION) && !it.name.endsWith(TEMP_SUFFIX) }
            .map(::readSummary)
            .sortedBy { it.id }
    }

    override suspend fun load(id: String): Environment? = withContext(Dispatchers.IO) {
        readEnvironmentOrNull(pathFor(id))
    }

    override suspend fun save(environment: Environment): Unit = withContext(Dispatchers.IO) {
        ensureRoot()
        writeAtomically(pathFor(environment.id), EnvironmentJson.encode(environment))
        _saved.tryEmit(environment.id)
    }

    override suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        fs.delete(pathFor(id), mustExist = false)
    }

    private fun ensureRoot() {
        fs.createDirectories(root, mustCreate = false)
    }

    private fun pathFor(id: String): Path = root.resolve("$id$EXTENSION")

    /** Write to a temp file, then move atomically — an interrupted write does not corrupt the file. */
    private fun writeAtomically(target: Path, text: String) {
        val temp = root.resolve("${target.name}$TEMP_SUFFIX")
        fs.write(temp) { writeUtf8(text) }
        fs.atomicMove(temp, target)
    }

    private fun readSummary(path: Path): EnvironmentSummary {
        val id = path.name.removeSuffix(EXTENSION)
        return try {
            EnvironmentSummary.Valid(decode(fs.read(path) { readUtf8() }))
        } catch (error: SerializationException) {
            EnvironmentSummary.Corrupt(id, error.message ?: "Invalid JSON")
        } catch (error: UnsupportedSchemaVersionException) {
            EnvironmentSummary.Corrupt(id, error.message ?: "Unsupported schema version")
        } catch (error: IOException) {
            EnvironmentSummary.Corrupt(id, error.message ?: "Unable to read file")
        }
    }

    private fun readEnvironmentOrNull(path: Path): Environment? {
        if (!fs.exists(path)) return null
        return try {
            decode(fs.read(path) { readUtf8() })
        } catch (error: SerializationException) {
            null
        } catch (error: UnsupportedSchemaVersionException) {
            null
        } catch (error: IOException) {
            null
        }
    }

    /**
     * Upgrades the file that was read to the current schema version. A newer version
     * (one this app does not understand) is an error; older versions are migrated
     * with [SchemaMigration].
     */
    private fun decode(text: String): Environment {
        val environment = EnvironmentJson.decode(text)
        // Outside the valid range (too new, OR meaninglessly low such as 0/negative) is a
        // corrupt file: turn it into a catchable error so it never reaches migration and
        // throws an uncaught one — the app must not crash (spec, "Corrupt file behavior").
        if (environment.schemaVersion !in 1..Environment.CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaVersionException(environment.schemaVersion)
        }
        return SchemaMigration.migrate(environment)
    }

    private companion object {
        const val EXTENSION = ".json"
        const val TEMP_SUFFIX = ".tmp"

        /** The settings screen writes with a 500 ms debounce; nothing piles up without subscribers. */
        const val SAVE_BUFFER = 8
    }
}
