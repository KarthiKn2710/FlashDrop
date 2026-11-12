package com.flashdrop.protocol

import com.flashdrop.core.model.TransferStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.MessageDigest

/** Server/receiver side of an FDTP v1 session. */
class FdtpReceiver(
    private val link: FdtpLink,
    private val local: LocalDevice,
    private val callbacks: ReceiverCallbacks,
    private val control: SessionControl,
    private val limits: ReceiveLimits = ReceiveLimits(),
    private val maxChunkSize: Int = Fdtp.DEFAULT_CHUNK,
) {
    @Volatile
    private var connection: FdtpConnection? = null

    fun sendCancel(reason: String) {
        connection?.sendCancel(reason)
    }

    suspend fun run(): TransferResult = runSession(link, { connection = it }) { conn -> execute(this, conn) }

    private class OpenFile(val index: Int, val entry: FileEntry, val sink: FileSink, val digest: MessageDigest, var position: Long) {
        var committed = position
        var lastCommitAt = System.nanoTime()
    }

    private suspend fun execute(scope: CoroutineScope, conn: FdtpConnection): TransferResult {
        // ---- handshake -------------------------------------------------------------------
        callbacks.onStatus(TransferStatus.Authenticating)
        val hello = conn.expect<ClientHello>(FrameType.CLIENT_HELLO)
        if (hello.versionMajor != Fdtp.VERSION_MAJOR) {
            throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Incompatible protocol version ${hello.versionMajor}")
        }
        if (hello.requiredCapabilities and Fdtp.CAP_RESUME.inv() != 0L) {
            throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "The other device requires an unsupported feature")
        }
        if (hello.nonceCommitment.size != 32) throw ProtocolException(ErrorCode.AUTH_FAILED, "Invalid nonce commitment")

        val serverNonce = Hashing.randomBytes(Pairing.NONCE_SIZE)
        conn.send(
            FrameType.SERVER_HELLO,
            ServerHello(
                versionMajor = Fdtp.VERSION_MAJOR,
                versionMinor = minOf(Fdtp.VERSION_MINOR, hello.versionMinor),
                serverNonce = serverNonce,
                deviceId = local.deviceId,
                displayName = local.displayName,
                capabilities = Fdtp.CAP_RESUME,
                requiredCapabilities = 0,
                maxChunkSize = maxChunkSize,
            ),
        )
        val auth = conn.expect<SessionAuth>(FrameType.SESSION_AUTH)
        if (auth.clientNonce.size != Pairing.NONCE_SIZE ||
            !MessageDigest.isEqual(Pairing.commitment(auth.clientNonce), hello.nonceCommitment)
        ) {
            conn.send(FrameType.AUTH_RESULT, AuthResult(false, "Authentication failed"))
            throw PairingFailedException("Authentication failed", byPeer = false)
        }
        val peer = PeerInfo(hello.deviceId.take(64), hello.displayName.take(64), link.peerFingerprint)
        val code = Pairing.shortCode(link.peerFingerprint, link.localFingerprint, auth.clientNonce, serverNonce)
        val confirmed = conn.awaitOrAbort(scope, "pairing confirmation") { callbacks.confirmPairing(peer, code) }
        conn.send(FrameType.AUTH_RESULT, AuthResult(confirmed, if (confirmed) "" else "Pairing declined"))
        if (!confirmed) throw PairingFailedException("Pairing declined", byPeer = false)

        // ---- negotiation -----------------------------------------------------------------
        callbacks.onStatus(TransferStatus.Negotiating)
        val init = conn.expect<SessionInit>(FrameType.SESSION_INIT)
        ManifestValidator.validateSessionId(init.sessionId)
        val sessionId = init.sessionId
        if (init.chunkSize < Fdtp.MIN_CHUNK) throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Chunk size too small")
        val chunkSize = minOf(init.chunkSize, maxChunkSize, Fdtp.MAX_CHUNK)
        conn.maxDataPayload = chunkSize + Fdtp.CHUNK_HEADER
        conn.send(FrameType.SESSION_READY, SessionReady(sessionId, chunkSize, resumeSupported = true))

        val entries = ArrayList<FileEntry>()
        var totalBytes = 0L
        var totalFiles = 0
        var pageIndex = 0
        while (true) {
            val page = conn.expect<TransferManifest>(FrameType.TRANSFER_MANIFEST)
            if (page.sessionId != sessionId || page.pageIndex != pageIndex++) {
                throw ProtocolException(ErrorCode.MANIFEST_INVALID, "Manifest page out of order")
            }
            if (entries.size + page.files.size > limits.maxFiles) {
                throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Too many files")
            }
            entries.addAll(page.files)
            totalBytes = page.totalBytes
            totalFiles = page.totalFiles
            if (page.lastPage) break
        }
        ManifestValidator.validate(sessionId, entries, totalBytes, totalFiles, chunkSize, limits)
        val manifestDigest = ManifestValidator.digest(sessionId, entries)

        callbacks.onStatus(TransferStatus.AwaitingApproval)
        val approval = conn.awaitOrAbort(scope, "approval") {
            callbacks.approve(peer, sessionId, manifestDigest, entries, totalBytes, chunkSize)
        }
        val sink = when (approval) {
            is Approval.Rejected -> {
                conn.send(FrameType.TRANSFER_REJECT, TransferReject(sessionId, approval.reason))
                throw TransferRejectedException(approval.reason, byPeer = false)
            }
            is Approval.Accepted -> approval.sink
        }

        val indexById = entries.withIndex().associate { it.value.fileId to it.index }
        val resumeOffsets = HashMap<Int, Long>()
        val completed = HashSet<Int>()
        val failed = HashSet<Int>()
        val resumeState = try {
            entries.forEachIndexed { index, entry -> if (entry.isDirectory) sink.createDirectory(index) }
            sink.resumeState()
        } catch (e: IOException) {
            throw storageError(e)
        }
        for (state in resumeState) {
            val index = indexById[state.fileId] ?: continue
            if (state.verified) completed.add(index) else resumeOffsets[index] = state.committedBytes
        }
        conn.send(FrameType.TRANSFER_ACCEPT, TransferAccept(sessionId))
        conn.send(FrameType.RESUME_STATE, ResumeState(resumeState))
        conn.expect<TransferStart>(FrameType.TRANSFER_START)

        // ---- data ------------------------------------------------------------------------
        val remotePaused = MutableStateFlow(false)
        scope.launch {
            var lastLocal = false
            combine(control.localPaused, remotePaused) { l, r -> l to r }.collect { (localPaused, remote) ->
                if (localPaused != lastLocal) {
                    conn.send(if (localPaused) FrameType.TRANSFER_PAUSE else FrameType.TRANSFER_RESUME, TransferPause(sessionId))
                    lastLocal = localPaused
                }
                callbacks.onStatus(if (localPaused || remote) TransferStatus.Paused else TransferStatus.Transferring)
            }
        }
        for (index in completed) {
            callbacks.onBytes(index, entries[index].fileSize)
            callbacks.onFileFinished(index, true)
        }

        var current: OpenFile? = null
        var connectionBytes = 0L
        val ackBytes = chunkSize.toLong() * Fdtp.ACK_CHUNKS

        fun open(index: Int, offset: Long): OpenFile {
            val entry = entries.getOrNull(index)
            if (entry == null || entry.isDirectory || index in completed || index in failed) {
                throw ProtocolException(ErrorCode.INVALID_CHUNK, "Data for an unexpected file")
            }
            val expected = resumeOffsets[index] ?: 0L
            if (offset != expected) throw ProtocolException(ErrorCode.INVALID_CHUNK, "Unexpected file offset")
            val digest = Hashing.sha256()
            try {
                val fileSink = sink.openFile(index, expected)
                if (expected > 0) fileSink.readPrefix(digest, expected)
                callbacks.onFileStarted(index, expected)
                return OpenFile(index, entry, fileSink, digest, expected)
            } catch (e: IOException) {
                throw storageError(e)
            }
        }

        fun ack(file: OpenFile) {
            conn.send(FrameType.CHUNK_ACK, ChunkAck(file.entry.fileId, file.committed, connectionBytes))
        }

        try {
            while (true) {
                val frame = conn.next()
                when (frame.type) {
                    FrameType.DATA_CHUNK -> {
                        val index = readInt(frame.payload, 0)
                        val offset = readLong(frame.payload, 4)
                        val length = frame.length - Fdtp.CHUNK_HEADER
                        val file = current ?: open(index, offset).also { current = it }
                        if (index != file.index || offset != file.position || length > chunkSize ||
                            offset + length > file.entry.fileSize
                        ) {
                            throw ProtocolException(ErrorCode.INVALID_CHUNK, "Chunk does not match the expected range")
                        }
                        try {
                            file.sink.write(frame.payload, Fdtp.CHUNK_HEADER, length)
                        } catch (e: IOException) {
                            throw storageError(e)
                        }
                        file.digest.update(frame.payload, Fdtp.CHUNK_HEADER, length)
                        conn.recycle(frame)
                        file.position += length
                        connectionBytes += length
                        callbacks.onBytes(index, file.position)

                        val now = System.nanoTime()
                        if (file.position - file.committed >= ackBytes ||
                            now - file.lastCommitAt >= Fdtp.ACK_INTERVAL_MS * 1_000_000
                        ) {
                            try {
                                file.sink.commit(file.position)
                            } catch (e: IOException) {
                                throw storageError(e)
                            }
                            file.committed = file.position
                            file.lastCommitAt = now
                            ack(file)
                        }
                    }

                    FrameType.FILE_END -> {
                        val end = conn.decode(FileEnd.serializer(), frame)
                        val index = indexById[end.fileId]
                            ?: throw ProtocolException(ErrorCode.INVALID_CHUNK, "FILE_END for an unknown file")
                        // Empty files and files whose data was fully committed earlier have no chunks.
                        val file = current ?: open(index, resumeOffsets[index] ?: 0L)
                        current = null
                        if (file.index != index) throw ProtocolException(ErrorCode.INVALID_CHUNK, "FILE_END for the wrong file")

                        val ok = try {
                            when {
                                end.aborted -> {
                                    file.sink.discard()
                                    false
                                }
                                file.position != file.entry.fileSize ->
                                    throw ProtocolException(ErrorCode.INVALID_CHUNK, "File ended at the wrong size")
                                MessageDigest.isEqual(file.digest.digest(), end.sha256) -> {
                                    file.sink.complete(end.sha256)
                                    true
                                }
                                else -> {
                                    file.sink.discard() // tampered or corrupted: never commit
                                    false
                                }
                            }
                        } catch (e: IOException) {
                            throw storageError(e)
                        }
                        file.committed = file.position
                        resumeOffsets.remove(index)
                        if (ok) completed.add(index) else failed.add(index)
                        conn.send(FrameType.FILE_VERIFIED, FileVerified(end.fileId, ok))
                        ack(file)
                        callbacks.onFileFinished(index, ok)
                    }

                    FrameType.TRANSFER_PAUSE -> remotePaused.value = true
                    FrameType.TRANSFER_RESUME -> remotePaused.value = false

                    FrameType.TRANSFER_COMPLETE -> {
                        callbacks.onStatus(TransferStatus.Verifying)
                        conn.sendQuietly(FrameType.TRANSFER_COMPLETE, FdtpConnection.EMPTY)
                        val regular = entries.indices.filter { !entries[it].isDirectory }
                        return TransferResult(completed, regular.filter { it !in completed }.toSet())
                    }

                    else -> conn.unexpected(frame, "DATA_CHUNK")
                }
            }
        } finally {
            // Interrupted mid-file: make what we have durable so it can be resumed, then let go.
            current?.let { file ->
                try {
                    file.sink.commit(file.position)
                } catch (_: Exception) {
                }
                try {
                    file.sink.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun storageError(e: IOException): ProtocolException =
        ProtocolException(ErrorCode.STORAGE_ERROR, "Storage error: ${e.message ?: "write failed"}")
}
