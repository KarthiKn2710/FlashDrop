package com.flashdrop.protocol

import com.flashdrop.core.model.TransferStatus
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * An established, confidential byte stream to one peer, provided by a transport adapter.
 * Fingerprints identify the public keys both ends proved possession of while securing the link.
 */
interface FdtpLink : Closeable {
    val input: InputStream
    val output: OutputStream
    val localFingerprint: ByteArray
    val peerFingerprint: ByteArray
}

data class LocalDevice(val deviceId: String, val displayName: String)

class PeerInfo(val deviceId: String, val displayName: String, val fingerprint: ByteArray)

/** Shared between the session owner and the engine; flipping [localPaused] pauses or resumes. */
class SessionControl {
    val localPaused = MutableStateFlow(false)
}

class TransferResult(val completed: Set<Int>, val failed: Set<Int>)

interface TransferEvents {
    fun onStatus(status: TransferStatus)
    fun onFileStarted(index: Int, offset: Long)

    /** Called for every chunk; implementations must be cheap and non-blocking. */
    fun onBytes(index: Int, fileBytes: Long)
    fun onFileFinished(index: Int, ok: Boolean)
}

/** Sequential, bounded reader over one source file. */
interface SourceReader : Closeable {
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

interface SenderCallbacks : TransferEvents {
    /** Suspends until the user confirms the code, or returns immediately for a trusted peer. */
    suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean
    fun openSource(index: Int): SourceReader
}

sealed interface Approval {
    class Accepted(val sink: ReceiveSink) : Approval
    class Rejected(val reason: String) : Approval
}

interface ReceiverCallbacks : TransferEvents {
    suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean

    /** Decides on an incoming manifest (user approval, quotas, free space, resume lookup). */
    suspend fun approve(
        peer: PeerInfo,
        sessionId: String,
        manifestDigest: ByteArray,
        files: List<FileEntry>,
        totalBytes: Long,
        chunkSize: Int,
    ): Approval
}

/** Destination side of a session. All methods are blocking and called on an I/O thread. */
interface ReceiveSink {
    /** Only data that is verified and durably committed may be reported here. */
    fun resumeState(): List<FileResume>
    fun createDirectory(index: Int)
    fun openFile(index: Int, offset: Long): FileSink
}

interface FileSink {
    /** Feeds the first [length] already-committed bytes into [digest] when resuming. */
    fun readPrefix(digest: MessageDigest, length: Long)
    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Makes everything up to [position] durable and records a checkpoint. */
    fun commit(position: Long)

    /** Integrity verified: make the data durable as needed and move the file to its final name. */
    fun complete(sha256: ByteArray)

    /** Integrity failed or the file was aborted: delete the temporary data. */
    fun discard()

    /** Stop writing but keep the partial file for a later resume. */
    fun close()
}
