package com.flashdrop.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.serializer
import java.io.EOFException
import java.io.IOException

/**
 * Message layer on top of one [FdtpLink].
 *
 * A reader coroutine decodes frames into a small bounded channel, so network reads and disk
 * writes overlap while memory stays bounded: at most [POOL_SIZE] chunk buffers ever exist.
 * PING/PONG is answered here and never reaches the session logic.
 */
class FdtpConnection(private val link: FdtpLink) {
    private val reader = FrameReader(link.input)
    private val writer = FrameWriter(link.output)
    private val pool = Channel<ByteArray>(POOL_SIZE)
    private var allocated = 0

    val incoming = Channel<Frame>(INCOMING_CAPACITY)

    var maxDataPayload: Int
        get() = reader.maxDataPayload
        set(value) {
            reader.maxDataPayload = value
        }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    delay(Fdtp.PING_INTERVAL_MS)
                    writer.write(FrameType.PING, EMPTY)
                }
            } catch (e: IOException) {
                link.close() // unblocks the reader, which reports the failure to the session
            }
        }
    }

    private suspend fun readLoop() {
        try {
            while (true) {
                val header = reader.readHeader() ?: break
                val frame = if (header.type == FrameType.DATA_CHUNK) {
                    val buffer = acquire()
                    reader.readPayload(buffer, header.length)
                    Frame(header.type, header.flags, header.sequence, buffer, header.length)
                } else {
                    val payload = ByteArray(header.length)
                    reader.readPayload(payload, header.length)
                    Frame(header.type, header.flags, header.sequence, payload, header.length)
                }
                when (frame.type) {
                    FrameType.PING -> writer.write(FrameType.PONG, EMPTY)
                    FrameType.PONG -> Unit
                    else -> incoming.send(frame)
                }
            }
            incoming.close(EOFException("Connection closed by the other device"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            incoming.close()
            throw e
        } catch (e: Throwable) {
            incoming.close(e)
        }
    }

    private suspend fun acquire(): ByteArray {
        pool.tryReceive().getOrNull()?.let { return it }
        if (allocated < POOL_SIZE) {
            allocated++
            return ByteArray(reader.maxDataPayload)
        }
        return pool.receive()
    }

    /** Returns a DATA_CHUNK buffer to the pool once its bytes have been consumed. */
    fun recycle(frame: Frame) {
        if (frame.type == FrameType.DATA_CHUNK) pool.trySend(frame.payload)
    }

    suspend fun next(): Frame = unwrap(incoming.receiveCatching())

    fun unwrap(result: ChannelResult<Frame>): Frame =
        result.getOrNull() ?: throw (result.exceptionOrNull() ?: EOFException("Connection closed"))

    fun <T> decode(deserializer: DeserializationStrategy<T>, frame: Frame): T = try {
        ProtoBuf.decodeFromByteArray(deserializer, frame.payload)
    } catch (e: SerializationException) {
        throw ProtocolException(ErrorCode.MALFORMED_FRAME, "Malformed ${frame.type} message")
    } catch (e: IllegalArgumentException) {
        throw ProtocolException(ErrorCode.MALFORMED_FRAME, "Malformed ${frame.type} message")
    }

    suspend inline fun <reified T> expect(type: FrameType): T {
        val frame = next()
        if (frame.type != type) unexpected(frame, type.name)
        return decode(serializer<T>(), frame)
    }

    /** Converts a frame that is not valid in the current state into the matching exception. */
    fun unexpected(frame: Frame, expected: String): Nothing = when (frame.type) {
        FrameType.ERROR -> {
            val error = decode(ErrorMessage.serializer(), frame)
            throw ProtocolException(ErrorCode.from(error.code), error.message.take(200), remote = true)
        }
        FrameType.TRANSFER_CANCEL ->
            throw RemoteCancelledException(decode(TransferCancel.serializer(), frame).reason.take(200))
        else -> throw ProtocolException(ErrorCode.UNEXPECTED_MESSAGE, "Expected $expected but received ${frame.type}")
    }

    fun <T> send(type: FrameType, serializer: SerializationStrategy<T>, message: T) {
        writer.write(type, ProtoBuf.encodeToByteArray(serializer, message))
    }

    inline fun <reified T> send(type: FrameType, message: T) = send(type, serializer<T>(), message)

    fun sendChunk(fileIndex: Int, offset: Long, data: ByteArray, length: Int) =
        writer.writeChunk(fileIndex, offset, data, length)

    /** Best-effort notification on the way out; failures are irrelevant because we are closing. */
    fun sendQuietly(type: FrameType, payload: ByteArray) {
        try {
            writer.write(type, payload)
        } catch (_: Exception) {
        }
    }

    fun sendError(e: ProtocolException) = sendQuietly(
        FrameType.ERROR,
        ProtoBuf.encodeToByteArray(ErrorMessage.serializer(), ErrorMessage(e.code.code, e.message ?: "")),
    )

    fun sendCancel(reason: String) = sendQuietly(
        FrameType.TRANSFER_CANCEL,
        ProtoBuf.encodeToByteArray(TransferCancel.serializer(), TransferCancel(reason)),
    )

    /**
     * Runs [block] (typically a user decision) while watching the connection, so a cancel,
     * an error or a disconnect from the peer ends the wait immediately.
     */
    suspend fun <T> awaitOrAbort(scope: CoroutineScope, expected: String, block: suspend () -> T): T {
        val pending = scope.async { block() }
        try {
            return select<T> {
                pending.onAwait { it }
                incoming.onReceiveCatching { result -> unexpected(unwrap(result), expected) }
            }
        } finally {
            pending.cancel()
        }
    }

    companion object {
        const val POOL_SIZE = 3
        const val INCOMING_CAPACITY = 2
        val EMPTY = ByteArray(0)

        /** Tells a peer we cannot serve it right now, then closes the link. */
        fun rejectBusy(link: FdtpLink) {
            try {
                FrameWriter(link.output).write(
                    FrameType.ERROR,
                    ProtoBuf.encodeToByteArray(
                        ErrorMessage.serializer(),
                        ErrorMessage(ErrorCode.BUSY.code, "The other device is busy with another transfer"),
                    ),
                )
            } catch (_: Exception) {
            } finally {
                try {
                    link.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}

/**
 * Owns the lifetime of one FDTP session: starts the connection, guarantees the link is closed on
 * completion, failure or cancellation (which also unblocks any thread stuck in socket I/O), and
 * reports local protocol errors to the peer before closing.
 */
internal suspend fun <T> runSession(
    link: FdtpLink,
    onConnection: (FdtpConnection) -> Unit,
    block: suspend CoroutineScope.(FdtpConnection) -> T,
): T = withContext(Dispatchers.IO) {
    try {
        coroutineScope {
            val conn = FdtpConnection(link)
            onConnection(conn)
            launch {
                try {
                    awaitCancellation()
                } finally {
                    try {
                        link.close()
                    } catch (_: Exception) {
                    }
                }
            }
            conn.start(this)
            try {
                block(conn)
            } catch (e: ProtocolException) {
                if (!e.remote) conn.sendError(e)
                throw e
            } finally {
                coroutineContext.cancelChildren()
            }
        }
    } finally {
        try {
            link.close()
        } catch (_: Exception) {
        }
    }
}
