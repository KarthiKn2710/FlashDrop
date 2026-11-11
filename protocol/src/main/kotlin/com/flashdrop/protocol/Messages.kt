package com.flashdrop.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * FDTP v1 control messages, encoded as Protocol Buffers (proto3 wire format).
 * Field numbers are part of the wire contract: never renumber, only append.
 * Every field has a default so that unknown/missing optional fields are tolerated.
 */

@Serializable
class ClientHello(
    @ProtoNumber(1) val versionMajor: Int = 0,
    @ProtoNumber(2) val versionMinor: Int = 0,
    /** SHA-256 of the client nonce; the nonce itself is revealed in SESSION_AUTH. */
    @ProtoNumber(3) val nonceCommitment: ByteArray = ByteArray(0),
    @ProtoNumber(4) val deviceId: String = "",
    @ProtoNumber(5) val displayName: String = "",
    @ProtoNumber(6) val capabilities: Long = 0,
    /** Capabilities the peer must support, otherwise the session fails explicitly. */
    @ProtoNumber(7) val requiredCapabilities: Long = 0,
    @ProtoNumber(8) val maxChunkSize: Int = 0,
)

@Serializable
class ServerHello(
    @ProtoNumber(1) val versionMajor: Int = 0,
    @ProtoNumber(2) val versionMinor: Int = 0,
    @ProtoNumber(3) val serverNonce: ByteArray = ByteArray(0),
    @ProtoNumber(4) val deviceId: String = "",
    @ProtoNumber(5) val displayName: String = "",
    @ProtoNumber(6) val capabilities: Long = 0,
    @ProtoNumber(7) val requiredCapabilities: Long = 0,
    @ProtoNumber(8) val maxChunkSize: Int = 0,
)

@Serializable
class SessionAuth(@ProtoNumber(1) val clientNonce: ByteArray = ByteArray(0))

@Serializable
class AuthResult(
    @ProtoNumber(1) val ok: Boolean = false,
    @ProtoNumber(2) val reason: String = "",
)

@Serializable
class SessionInit(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val resume: Boolean = false,
    @ProtoNumber(3) val chunkSize: Int = 0,
)

@Serializable
class SessionReady(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val chunkSize: Int = 0,
    @ProtoNumber(3) val resumeSupported: Boolean = false,
)

enum class FileType { FILE_TYPE_UNSPECIFIED, REGULAR_FILE, DIRECTORY }

@Serializable
data class FileEntry(
    @ProtoNumber(1) val fileId: String = "",
    @ProtoNumber(2) val fileName: String = "",
    /** Path relative to the destination root, '/'-separated, including the file name. */
    @ProtoNumber(3) val relativePath: String = "",
    @ProtoNumber(4) val fileSize: Long = 0,
    /** Advisory only; never a security guarantee. */
    @ProtoNumber(5) val mimeType: String = "",
    /** Optional in the manifest; the authoritative digest travels in FILE_END. */
    @ProtoNumber(6) val sha256: ByteArray = ByteArray(0),
    @ProtoNumber(7) val chunkSize: Int = 0,
    @ProtoNumber(8) val totalChunks: Long = 0,
    @ProtoNumber(9) val type: FileType = FileType.FILE_TYPE_UNSPECIFIED,
) {
    val isDirectory: Boolean get() = type == FileType.DIRECTORY
}

/** One page of the manifest. Fields 1-4 follow the blueprint; 5-6 add pagination. */
@Serializable
class TransferManifest(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val files: List<FileEntry> = emptyList(),
    @ProtoNumber(3) val totalBytes: Long = 0,
    @ProtoNumber(4) val totalFiles: Int = 0,
    @ProtoNumber(5) val pageIndex: Int = 0,
    @ProtoNumber(6) val lastPage: Boolean = false,
)

@Serializable
class TransferAccept(@ProtoNumber(1) val sessionId: String = "")

@Serializable
class TransferReject(
    @ProtoNumber(1) val sessionId: String = "",
    @ProtoNumber(2) val reason: String = "",
)

@Serializable
class TransferStart(@ProtoNumber(1) val sessionId: String = "")

/** Receiver-committed progress. Only durable, checkpointed data is ever acknowledged. */
@Serializable
class ChunkAck(
    @ProtoNumber(1) val fileId: String = "",
    @ProtoNumber(2) val committedBytes: Long = 0,
    /** Total bytes the receiver has consumed on this connection (flow-control window). */
    @ProtoNumber(3) val connectionBytes: Long = 0,
)

@Serializable
class FileEnd(
    @ProtoNumber(1) val fileId: String = "",
    @ProtoNumber(2) val sha256: ByteArray = ByteArray(0),
    /** The sender could not read the source to the end; the receiver discards this file. */
    @ProtoNumber(3) val aborted: Boolean = false,
)

@Serializable
class FileVerified(
    @ProtoNumber(1) val fileId: String = "",
    @ProtoNumber(2) val ok: Boolean = false,
)

@Serializable
class FileResume(
    @ProtoNumber(1) val fileId: String = "",
    @ProtoNumber(2) val committedBytes: Long = 0,
    /** File already verified and finalised by the receiver; it must not be resent. */
    @ProtoNumber(3) val verified: Boolean = false,
)

@Serializable
class ResumeState(@ProtoNumber(1) val files: List<FileResume> = emptyList())

@Serializable
class TransferPause(@ProtoNumber(1) val sessionId: String = "")

@Serializable
class TransferCancel(@ProtoNumber(1) val reason: String = "")

@Serializable
class TransferComplete(@ProtoNumber(1) val sessionId: String = "")

@Serializable
class ErrorMessage(
    @ProtoNumber(1) val code: Int = 0,
    @ProtoNumber(2) val message: String = "",
)

enum class ErrorCode(val code: Int) {
    UNKNOWN(0), MALFORMED_FRAME(1), UNSUPPORTED_VERSION(2), UNSUPPORTED_CAPABILITY(3),
    AUTH_FAILED(4), MANIFEST_INVALID(5), LIMIT_EXCEEDED(6), UNEXPECTED_MESSAGE(7),
    INVALID_CHUNK(8), STORAGE_ERROR(9), BUSY(10), INTERNAL(11);

    companion object {
        fun from(code: Int): ErrorCode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** A protocol violation or a fatal error reported by the peer ([remote] = true). Never resumable. */
class ProtocolException(val code: ErrorCode, message: String, val remote: Boolean = false) : Exception(message)

class RemoteCancelledException(reason: String) : Exception(reason.ifEmpty { "Cancelled by the other device" })

class TransferRejectedException(reason: String, val byPeer: Boolean) : Exception(reason)

class PairingFailedException(reason: String, val byPeer: Boolean) : Exception(reason)
