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
 */
internal class PersistentReceiveSink(
    private val sessionId: String,
    private val entries: List<FileEntry>,
    private val chunkSize: Int,
    private val store: DestinationStore,
    private val dao: TransferDao,
) : ReceiveSink {

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
        val previous = dao.filesOf(sessionId).firstOrNull { it.fileId == entry.fileId }?.temporaryUri
        val temp: String
        if (offset > 0) {
            temp = previous ?: throw IOException("Partial file is missing")
        } else {
            previous?.let { store.discard(it) }
            temp = store.createTemp(entry.relativePath)
            dao.setTemporaryUri(sessionId, entry.fileId, temp)
        }
        val file = store.open(temp)
        try {
            if (file.size() < offset) throw IOException("Partial file is shorter than its checkpoint")
            // Anything beyond the checkpoint was never acknowledged; drop it.
            file.truncate(offset)
        } catch (e: IOException) {
            file.close()
            throw e
        }
        dao.updateFileProgress(sessionId, entry.fileId, offset, FileStatus.Transferring.name)

        return object : FileSink {
            private var position = offset
            private var version = 0

            override fun readPrefix(digest: MessageDigest, length: Long) = file.digestPrefix(digest, length)

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                file.write(buffer, offset, length, position)
                position += length
            }

            override fun commit(position: Long) {
                file.force()
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
                dao.updateFileProgress(sessionId, entry.fileId, position, FileStatus.Transferring.name)
            }

            override fun complete(sha256: ByteArray) {
                file.close()
                val destination = store.finalize(temp, entry.relativePath)
                dao.finishFile(sessionId, entry.fileId, FileStatus.Completed.name, entry.fileSize, Hashing.hex(sha256), destination)
                dao.deleteCheckpoint(sessionId, entry.fileId)
            }

            override fun discard() {
                file.close()
                store.discard(temp)
                dao.finishFile(sessionId, entry.fileId, FileStatus.Failed.name, 0, null, null)
                dao.deleteCheckpoint(sessionId, entry.fileId)
            }

            override fun close() = file.close()
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
}
