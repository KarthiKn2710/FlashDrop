package com.flashdrop.protocol

import com.flashdrop.core.model.TransferStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Client/sender side of an FDTP v1 session.
 *
 * [files] is the manifest without chunk information; chunk fields are filled in once the chunk
 * size has been negotiated. When [resume] is set the receiver is asked to continue [sessionId].
 */
class FdtpSender(
    private val link: FdtpLink,
    private val local: LocalDevice,
    private val sessionId: String,
    private val files: List<FileEntry>,
    private val resume: Boolean,
    private val callbacks: SenderCallbacks,
    private val control: SessionControl,
    private val requestedChunkSize: Int = Fdtp.DEFAULT_CHUNK,
) {
    @Volatile
    private var connection: FdtpConnection? = null

    /** Best-effort TRANSFER_CANCEL; the owner then cancels the coroutine running [run]. */
    fun sendCancel(reason: String) {
        connection?.sendCancel(reason)
    }

    suspend fun run(): TransferResult = runSession(link, { connection = it }) { conn -> execute(this, conn) }

    private suspend fun execute(scope: CoroutineScope, conn: FdtpConnection): TransferResult {
        // ---- handshake -------------------------------------------------------------------
        callbacks.onStatus(TransferStatus.Authenticating)
        val clientNonce = Hashing.randomBytes(Pairing.NONCE_SIZE)
        conn.send(
            FrameType.CLIENT_HELLO,
            ClientHello(
                versionMajor = Fdtp.VERSION_MAJOR,
                versionMinor = Fdtp.VERSION_MINOR,
                nonceCommitment = Pairing.commitment(clientNonce),
                deviceId = local.deviceId,
                displayName = local.displayName,
                capabilities = Fdtp.CAP_RESUME,
                requiredCapabilities = 0,
                maxChunkSize = Fdtp.MAX_CHUNK,
            ),
        )
        val hello = conn.expect<ServerHello>(FrameType.SERVER_HELLO)
        if (hello.versionMajor != Fdtp.VERSION_MAJOR) {
            throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Incompatible protocol version ${hello.versionMajor}")
        }
        if (hello.requiredCapabilities and Fdtp.CAP_RESUME.inv() != 0L) {
            throw ProtocolException(ErrorCode.UNSUPPORTED_CAPABILITY, "The other device requires an unsupported feature")
        }
        if (hello.serverNonce.size != Pairing.NONCE_SIZE) {
            throw ProtocolException(ErrorCode.AUTH_FAILED, "Invalid server nonce")
        }
        val peer = PeerInfo(hello.deviceId.take(64), hello.displayName.take(64), link.peerFingerprint)
        conn.send(FrameType.SESSION_AUTH, SessionAuth(clientNonce))
        val code = Pairing.shortCode(link.localFingerprint, link.peerFingerprint, clientNonce, hello.serverNonce)
        authenticate(scope, conn, peer, code)

        // ---- negotiation -----------------------------------------------------------------
        callbacks.onStatus(TransferStatus.Negotiating)
        conn.send(FrameType.SESSION_INIT, SessionInit(sessionId, resume, requestedChunkSize))
        val ready = conn.expect<SessionReady>(FrameType.SESSION_READY)
        val chunkSize = ready.chunkSize
        if (ready.sessionId != sessionId || chunkSize < Fdtp.MIN_CHUNK || chunkSize > requestedChunkSize) {
            throw ProtocolException(ErrorCode.UNEXPECTED_MESSAGE, "Invalid session parameters")
        }

        val entries = files.map {
            if (it.isDirectory) it
            else it.copy(chunkSize = chunkSize, totalChunks = ManifestValidator.chunkCount(it.fileSize, chunkSize))
        }
        val totalBytes = entries.sumOf { it.fileSize }
        val pages = entries.chunked(Fdtp.MANIFEST_PAGE_ENTRIES)
        pages.forEachIndexed { index, page ->
            conn.send(
                FrameType.TRANSFER_MANIFEST,
                TransferManifest(sessionId, page, totalBytes, entries.size, index, index == pages.lastIndex),
            )
        }

        callbacks.onStatus(TransferStatus.AwaitingApproval)
        val decision = conn.next()
        when (decision.type) {
            FrameType.TRANSFER_ACCEPT -> Unit
            FrameType.TRANSFER_REJECT -> throw TransferRejectedException(
                conn.decode(TransferReject.serializer(), decision).reason.take(200).ifEmpty { "Declined" },
                byPeer = true,
            )
            else -> conn.unexpected(decision, "TRANSFER_ACCEPT")
        }
        val resumeState = conn.expect<ResumeState>(FrameType.RESUME_STATE).files.associateBy { it.fileId }
        conn.send(FrameType.TRANSFER_START, TransferStart(sessionId))

        // ---- data ------------------------------------------------------------------------
        val indexById = entries.withIndex().associate { it.value.fileId to it.index }
        val acked = MutableStateFlow(0L)
        val remotePaused = MutableStateFlow(false)
        val verifiedCount = MutableStateFlow(0)
        val results = ConcurrentHashMap<Int, Boolean>()
        val remoteComplete = CompletableDeferred<Unit>()
        var allVerified = false

        scope.launch {
            try {
                for (frame in conn.incoming) {
                    when (frame.type) {
                        FrameType.CHUNK_ACK -> {
                            val ack = conn.decode(ChunkAck.serializer(), frame)
                            if (ack.connectionBytes > acked.value) acked.value = ack.connectionBytes
                        }
                        FrameType.FILE_VERIFIED -> {
                            val verified = conn.decode(FileVerified.serializer(), frame)
                            val index = indexById[verified.fileId]
                                ?: throw ProtocolException(ErrorCode.UNEXPECTED_MESSAGE, "Unknown file verified")
                            if (results.putIfAbsent(index, verified.ok) == null) {
                                callbacks.onFileFinished(index, verified.ok)
                                verifiedCount.value = verifiedCount.value + 1
                            }
                        }
                        FrameType.TRANSFER_PAUSE -> remotePaused.value = true
                        FrameType.TRANSFER_RESUME -> remotePaused.value = false
                        FrameType.TRANSFER_COMPLETE -> remoteComplete.complete(Unit)
                        else -> conn.unexpected(frame, "CHUNK_ACK")
                    }
                }
            } catch (e: IOException) {
                // A disconnect after every file was verified does not invalidate the transfer.
                if (!allVerified) throw e
            }
        }

        val pauseJob = scope.launch {
            var lastLocal = false
            combine(control.localPaused, remotePaused) { l, r -> l to r }.collect { (localPaused, remote) ->
                if (localPaused != lastLocal) {
                    conn.send(if (localPaused) FrameType.TRANSFER_PAUSE else FrameType.TRANSFER_RESUME, TransferPause(sessionId))
                    lastLocal = localPaused
                }
                callbacks.onStatus(if (localPaused || remote) TransferStatus.Paused else TransferStatus.Transferring)
            }
        }

        val buffer = ByteArray(chunkSize)
        val window = chunkSize.toLong() * Fdtp.WINDOW_CHUNKS
        var sent = 0L
        var expectedVerifications = 0

        for ((index, entry) in entries.withIndex()) {
            if (entry.isDirectory) continue
            val state = resumeState[entry.fileId]
            if (state != null && state.verified) {
                results[index] = true
                callbacks.onBytes(index, entry.fileSize)
                callbacks.onFileFinished(index, true)
                continue
            }
            val startOffset = state?.committedBytes ?: 0L
            if (startOffset < 0 || startOffset > entry.fileSize) {
                throw ProtocolException(ErrorCode.UNEXPECTED_MESSAGE, "Invalid resume offset")
            }

            val digest = Hashing.sha256()
            var position = 0L
            var aborted = false
            callbacks.onFileStarted(index, startOffset)
            val source = try {
                callbacks.openSource(index)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
            if (source == null) {
                aborted = true
            } else {
                try {
                    // Already-committed prefix: hash locally, do not resend.
                    while (position < startOffset && !aborted) {
                        scope.ensureActive()
                        val want = minOf(chunkSize.toLong(), startOffset - position).toInt()
                        val n = readChunk(source, buffer, want)
                        if (n < want) aborted = true
                        else {
                            digest.update(buffer, 0, n)
                            position += n
                        }
                    }
                    while (position < entry.fileSize && !aborted) {
                        if (control.localPaused.value || remotePaused.value) {
                            combine(control.localPaused, remotePaused) { l, r -> l || r }.first { !it }
                        }
                        if (sent - acked.value > window) acked.first { sent - it <= window }
                        scope.ensureActive()

                        val want = minOf(chunkSize.toLong(), entry.fileSize - position).toInt()
                        val n = readChunk(source, buffer, want)
                        if (n < want) {
                            aborted = true // source shrank or became unreadable
                        } else {
                            digest.update(buffer, 0, n)
                            conn.sendChunk(index, position, buffer, n)
                            position += n
                            sent += n
                            callbacks.onBytes(index, position)
                        }
                    }
                } finally {
                    try {
                        source.close()
                    } catch (_: IOException) {
                    }
                }
            }
            conn.send(FrameType.FILE_END, FileEnd(entry.fileId, if (aborted) ByteArray(0) else digest.digest(), aborted))
            expectedVerifications++
        }

        // ---- completion ------------------------------------------------------------------
        pauseJob.cancel()
        callbacks.onStatus(TransferStatus.Verifying)
        verifiedCount.first { it >= expectedVerifications }
        allVerified = true
        conn.send(FrameType.TRANSFER_COMPLETE, TransferComplete(sessionId))
        withTimeoutOrNull(5_000) { remoteComplete.await() }

        val regular = entries.indices.filter { !entries[it].isDirectory }
        return TransferResult(
            completed = regular.filter { results[it] == true }.toSet(),
            failed = regular.filter { results[it] != true }.toSet(),
        )
    }

    /** Waits for both the local user's confirmation and the receiver's AUTH_RESULT. */
    private suspend fun authenticate(scope: CoroutineScope, conn: FdtpConnection, peer: PeerInfo, code: String) {
        val localConfirm = scope.async { callbacks.confirmPairing(peer, code) }
        var remote: AuthResult? = null
        var localOk: Boolean? = null
        try {
            while (remote == null || localOk == null) {
                select<Unit> {
                    if (localOk == null) localConfirm.onAwait { localOk = it }
                    if (remote == null) {
                        conn.incoming.onReceiveCatching { result ->
                            val frame = conn.unwrap(result)
                            if (frame.type != FrameType.AUTH_RESULT) conn.unexpected(frame, "AUTH_RESULT")
                            remote = conn.decode(AuthResult.serializer(), frame)
                        }
                    }
                }
                if (localOk == false) {
                    conn.sendCancel("Pairing declined")
                    throw PairingFailedException("Pairing declined", byPeer = false)
                }
                val r = remote
                if (r != null && !r.ok) {
                    throw PairingFailedException(r.reason.take(200).ifEmpty { "Declined by the other device" }, byPeer = true)
                }
            }
        } finally {
            localConfirm.cancel()
        }
    }

    /** Reads exactly [want] bytes unless the source ends or fails; returns the bytes read. */
    private fun readChunk(source: SourceReader, buffer: ByteArray, want: Int): Int {
        var done = 0
        try {
            while (done < want) {
                val n = source.read(buffer, done, want - done)
                if (n < 0) break
                done += n
            }
        } catch (e: IOException) {
            return -1
        }
        return done
    }
}
