package com.flashdrop.data.transfer

import com.flashdrop.core.database.TransferCheckpointEntity
import com.flashdrop.core.database.TransferDao
import com.flashdrop.core.model.FileStatus
import com.flashdrop.protocol.FileEntry
import com.flashdrop.protocol.FileResume
import com.flashdrop.protocol.FileSink
import com.flashdrop.protocol.Hashing
import com.flashdrop.protocol.ReceiveSink
import com.flashdrop.storage.DestinationStore
import java.io.IOException
import java.security.MessageDigest

/**
 * Receive-side storage for one session: temporary documents plus Room checkpoints.
 *
 * A checkpoint row is written only after the bytes it covers were flushed to stable storage, so
 * whatever [resumeState] reports is both verified by the protocol and durably committed. All
 * methods are blocking and run on the engine's I/O threads.
 *
 * Database work is kept off the per-file fast path: a file that arrives whole between two
 * checkpoints costs a single row update, which matters for batches of thousands of small files.
 */
internal class PersistentReceiveSink(
    private val sessionId: String,
    private val entries: List<FileEntry>,
    private val chunkSize: Int,
    private val store: DestinationStore,
    private val dao: TransferDao,
) : ReceiveSink {

    /** Temporary document per file id, loaded once instead of being queried for every file. */
    private val temporaryUris: HashMap<String, String?> by lazy {
        HashMap<String, String?>().also { map -> dao.filesOf(sessionId).forEach { map[it.fileId] = it.temporaryUri } }
    }

    override fun resumeState(): List<FileResume> {
        val rows = dao.filesOf(sessionId).associateBy { it.fileId }
        val checkpoints = dao.checkpointsOf(sessionId).associateBy { it.fileId }
        return entries.mapNotNull { entry ->
            if (entry.isDirectory) return@mapNotNull null
            val row = rows[entry.fileId] ?: return@mapNotNull null
            if (row.status == FileStatus.Completed.name) {
                return@mapNotNull FileResume(entry.fileId, entry.fileSize, verified = true)
            }
            val checkpoint = checkpoints[entry.fileId] ?: return@mapNotNull null
            val committed = checkpoint.committedBytes
            val temp = row.temporaryUri
            val usable = committed > 0 && committed <= entry.fileSize && temp != null && store.exists(temp) &&
                (committed % chunkSize == 0L || committed == entry.fileSize)
            if (usable) FileResume(entry.fileId, committed, verified = false) else null
        }
    }

    override fun createDirectory(index: Int) = store.createDirectory(entries[index].relativePath)

    override fun openFile(index: Int, offset: Long): FileSink {
        val entry = entries[index]
        val previous = temporaryUris[entry.fileId]
        val temp: String
        // Whether the database already knows about this temporary document.
        var recorded: Boolean
        if (offset > 0) {
            temp = previous ?: throw IOException("Partial file is missing")
            recorded = true
        } else {
            previous?.let { store.discard(it) }
            temp = store.createTemp(entry.relativePath)
            temporaryUris[entry.fileId] = temp
            recorded = false
        }
        val file = store.open(temp)
        try {
            if (file.size() < offset) throw IOException("Partial file is shorter than its checkpoint")
            // Anything beyond the checkpoint was never acknowledged; drop it.
            if (offset > 0) file.truncate(offset)
        } catch (e: IOException) {
            file.close()
            throw e
        }

        return object : FileSink {
            private var position = offset
            private var version = 0
            private var checkpointed = offset > 0

            /** Records the temporary document so a partial file can be resumed or cleaned up. */
            private fun record(bytes: Long) {
                dao.startFile(sessionId, entry.fileId, temp, FileStatus.Transferring.name, bytes)
                recorded = true
            }

            override fun readPrefix(digest: MessageDigest, length: Long) = file.digestPrefix(digest, length)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                file.write(buffer, offset, length, position)
                position += length
            }

            override fun commit(position: Long) {
                file.force()
                record(position)
                dao.upsertCheckpoint(
                    TransferCheckpointEntity(
                        sessionId = sessionId,
                        fileId = entry.fileId,
                        checkpointVersion = ++version,
                        chunkSize = chunkSize,
                        verifiedRanges = "0-$position",
                        committedBytes = position,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                checkpointed = true
            }

            override fun complete(sha256: ByteArray) {
                // Large files are flushed to stable storage before being published. Small ones
                // are left to the system's writeback: an fsync per file would dominate the cost
                // of a many-small-files transfer, and they are cheap to send again.
                if (entry.fileSize >= SYNC_THRESHOLD) file.force()
                file.close()
                val destination = store.finalize(temp, entry.relativePath)
                temporaryUris[entry.fileId] = null
                dao.finishFile(sessionId, entry.fileId, FileStatus.Completed.name, entry.fileSize, Hashing.hex(sha256), destination)
                if (checkpointed) dao.deleteCheckpoint(sessionId, entry.fileId)
            }

            override fun discard() {
                file.close()
                store.discard(temp)
                temporaryUris[entry.fileId] = null
                dao.finishFile(sessionId, entry.fileId, FileStatus.Failed.name, 0, null, null)
                if (checkpointed) dao.deleteCheckpoint(sessionId, entry.fileId)
            }

            override fun close() {
                if (!recorded) {
                    try {
                        record(0)
                    } catch (_: Exception) {
                    }
                }
                file.close()
            }
        }
    }

    /** Removes every unfinished temporary file, e.g. after the transfer was cancelled. */
    fun discardPartials() {
        for (row in dao.filesOf(sessionId)) {
            val temp = row.temporaryUri ?: continue
            if (row.status == FileStatus.Completed.name) continue
            store.discard(temp)
            dao.finishFile(sessionId, row.fileId, FileStatus.Failed.name, 0, null, null)
            dao.deleteCheckpoint(sessionId, row.fileId)
        }
    }

    private companion object {
        const val SYNC_THRESHOLD = 1L shl 20
    }
}
