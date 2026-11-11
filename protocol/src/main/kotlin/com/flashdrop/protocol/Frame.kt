package com.flashdrop.protocol

import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** FDTP v1 constants (blueprint section 5). */
object Fdtp {
    const val MAGIC: Int = 0x46445250 // "FDRP"
    const val VERSION_MAJOR: Int = 1
    const val VERSION_MINOR: Int = 0
    const val HEADER_SIZE: Int = 16

    /** Strict upper bound for every control payload. */
    const val MAX_CONTROL_PAYLOAD: Int = 1 shl 20

    const val DEFAULT_CHUNK: Int = 4 shl 20
    const val MIN_CHUNK: Int = 256 shl 10
    const val MAX_CHUNK: Int = 16 shl 20

    /** DATA_CHUNK payload prefix: file index (u32) + file offset (u64). */
    const val CHUNK_HEADER: Int = 12

    /** A frame of unknown type carrying this flag may be skipped instead of rejected. */
    const val FLAG_IGNORABLE: Int = 0x0001

    const val CAP_RESUME: Long = 1L

    /** Sender may have at most this many unacknowledged chunks in flight. */
    const val WINDOW_CHUNKS: Int = 8

    /** Receiver commits and acknowledges at least every this many chunks (must be < WINDOW_CHUNKS). */
    const val ACK_CHUNKS: Int = 4
    const val ACK_INTERVAL_MS: Long = 1_000

    const val MANIFEST_PAGE_ENTRIES: Int = 200
    const val MAX_MANIFEST_FILES: Int = 100_000
    const val MAX_NAME_BYTES: Int = 255
    const val MAX_PATH_BYTES: Int = 1024
    const val MAX_PATH_DEPTH: Int = 32

    const val PING_INTERVAL_MS: Long = 10_000
}

enum class FrameType(val code: Int) {
    CLIENT_HELLO(1), SERVER_HELLO(2), SESSION_AUTH(3), AUTH_RESULT(4),
    SESSION_INIT(5), SESSION_READY(6), TRANSFER_MANIFEST(7), TRANSFER_ACCEPT(8),
    TRANSFER_REJECT(9), TRANSFER_START(10), DATA_CHUNK(11), CHUNK_ACK(12),
    FILE_END(13), FILE_VERIFIED(14), TRANSFER_PAUSE(15), TRANSFER_RESUME(16),
    RESUME_STATE(17), TRANSFER_CANCEL(18), TRANSFER_COMPLETE(19), ERROR(20),
    PING(21), PONG(22);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): FrameType? = byCode[code]
    }
}

/** A decoded frame. For DATA_CHUNK, [payload] is a pooled buffer and only [length] bytes are valid. */
class Frame(val type: FrameType, val flags: Int, val sequence: Int, val payload: ByteArray, val length: Int)

class FrameHeader(val type: FrameType, val flags: Int, val length: Int, val sequence: Int)

/**
 * Reads the 16-byte big-endian frame envelope:
 * magic(4) version(1) type(1) flags(2) payloadLength(4) sequence(4).
 * The declared length is validated before anything is allocated or read.
 */
class FrameReader(private val input: InputStream) {
    /** Largest accepted DATA_CHUNK payload; 0 until a chunk size has been negotiated. */
    @Volatile
    var maxDataPayload: Int = 0

    private val header = ByteArray(Fdtp.HEADER_SIZE)
    private var expectedSequence = 0

    /** Returns the next frame header, or null when the peer closed the stream on a frame boundary. */
    fun readHeader(): FrameHeader? {
        while (true) {
            val first = input.read(header, 0, Fdtp.HEADER_SIZE)
            if (first < 0) return null
            if (first < Fdtp.HEADER_SIZE) readFully(header, first, Fdtp.HEADER_SIZE - first)

            if (int(0) != Fdtp.MAGIC) throw ProtocolException(ErrorCode.MALFORMED_FRAME, "Bad frame magic")
            val version = header[4].toInt() and 0xFF
            if (version != Fdtp.VERSION_MAJOR) {
                throw ProtocolException(ErrorCode.UNSUPPORTED_VERSION, "Unsupported protocol version $version")
            }
            val typeCode = header[5].toInt() and 0xFF
            val flags = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
            val length = int(8)
            val sequence = int(12)

            if (sequence != expectedSequence) {
                throw ProtocolException(ErrorCode.MALFORMED_FRAME, "Frame out of sequence")
            }
            expectedSequence++

            val type = FrameType.fromCode(typeCode)
            if (type == null) {
                if (flags and Fdtp.FLAG_IGNORABLE == 0) {
                    throw ProtocolException(ErrorCode.MALFORMED_FRAME, "Unknown frame type $typeCode")
                }
                if (length < 0 || length > Fdtp.MAX_CONTROL_PAYLOAD) {
                    throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Oversized extension frame")
                }
                skip(length)
                continue
            }

            if (type == FrameType.DATA_CHUNK) {
                if (length <= Fdtp.CHUNK_HEADER || length > maxDataPayload) {
                    throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Invalid data chunk length $length")
                }
            } else if (length < 0 || length > Fdtp.MAX_CONTROL_PAYLOAD) {
                throw ProtocolException(ErrorCode.LIMIT_EXCEEDED, "Control payload too large: $length")
            }
            return FrameHeader(type, flags, length, sequence)
        }
    }

    fun readPayload(into: ByteArray, length: Int) = readFully(into, 0, length)

    private fun int(at: Int): Int =
        ((header[at].toInt() and 0xFF) shl 24) or ((header[at + 1].toInt() and 0xFF) shl 16) or
            ((header[at + 2].toInt() and 0xFF) shl 8) or (header[at + 3].toInt() and 0xFF)

    private fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var done = 0
        while (done < length) {
            val n = input.read(buffer, offset + done, length - done)
            if (n < 0) throw EOFException("Connection closed mid-frame")
            done += n
        }
    }

    private fun skip(length: Int) {
        val scratch = ByteArray(minOf(length, 8192).coerceAtLeast(1))
        var left = length
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size))
            if (n < 0) throw EOFException("Connection closed mid-frame")
            left -= n
        }
    }
}

/** Serialises frames; safe to call from several threads (one frame is written atomically). */
class FrameWriter(output: OutputStream) {
    private val out = BufferedOutputStream(output, 64 * 1024)
    private val head = ByteArray(Fdtp.HEADER_SIZE + Fdtp.CHUNK_HEADER)
    private var sequence = 0

    @Synchronized
    fun write(type: FrameType, payload: ByteArray, flags: Int = 0) {
        require(payload.size <= Fdtp.MAX_CONTROL_PAYLOAD) { "Control payload too large" }
        putHeader(type.code, flags, payload.size)
        out.write(head, 0, Fdtp.HEADER_SIZE)
        out.write(payload)
        out.flush()
    }

    /** Raw frame writer used by tests and extension frames. */
    @Synchronized
    fun writeRaw(typeCode: Int, payload: ByteArray, flags: Int) {
        putHeader(typeCode, flags, payload.size)
        out.write(head, 0, Fdtp.HEADER_SIZE)
        out.write(payload)
        out.flush()
    }

    @Synchronized
    fun writeChunk(fileIndex: Int, offset: Long, data: ByteArray, length: Int) {
        putHeader(FrameType.DATA_CHUNK.code, 0, Fdtp.CHUNK_HEADER + length)
        putInt(Fdtp.HEADER_SIZE, fileIndex)
        putInt(Fdtp.HEADER_SIZE + 4, (offset ushr 32).toInt())
        putInt(Fdtp.HEADER_SIZE + 8, offset.toInt())
        out.write(head, 0, head.size)
        out.write(data, 0, length)
        out.flush()
    }

    private fun putHeader(typeCode: Int, flags: Int, length: Int) {
        putInt(0, Fdtp.MAGIC)
        head[4] = Fdtp.VERSION_MAJOR.toByte()
        head[5] = typeCode.toByte()
        head[6] = (flags ushr 8).toByte()
        head[7] = flags.toByte()
        putInt(8, length)
        putInt(12, sequence++)
    }

    private fun putInt(at: Int, value: Int) {
        head[at] = (value ushr 24).toByte()
        head[at + 1] = (value ushr 16).toByte()
        head[at + 2] = (value ushr 8).toByte()
        head[at + 3] = value.toByte()
    }
}

internal fun readInt(buffer: ByteArray, at: Int): Int =
    ((buffer[at].toInt() and 0xFF) shl 24) or ((buffer[at + 1].toInt() and 0xFF) shl 16) or
        ((buffer[at + 2].toInt() and 0xFF) shl 8) or (buffer[at + 3].toInt() and 0xFF)

internal fun readLong(buffer: ByteArray, at: Int): Long =
    (readInt(buffer, at).toLong() shl 32) or (readInt(buffer, at + 4).toLong() and 0xFFFFFFFFL)
