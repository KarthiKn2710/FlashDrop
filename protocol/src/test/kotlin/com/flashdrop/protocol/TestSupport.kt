package com.flashdrop.protocol

import com.flashdrop.core.model.TransferStatus
import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

const val TEST_CHUNK = Fdtp.MIN_CHUNK

class SocketLink(
    private val socket: Socket,
    override val localFingerprint: ByteArray,
    override val peerFingerprint: ByteArray,
    output: OutputStream? = null,
) : FdtpLink {
    override val input: InputStream = socket.getInputStream()
    override val output: OutputStream = output ?: socket.getOutputStream()
    override fun close() = socket.close()
}

class LinkPair(val client: Socket, val server: Socket) {
    val clientFp = ByteArray(32) { 1 }
    val serverFp = ByteArray(32) { 2 }
    fun clientLink(output: OutputStream? = null) = SocketLink(client, clientFp, serverFp, output)
    fun serverLink() = SocketLink(server, serverFp, clientFp)
}

fun linkPair(): LinkPair {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
        val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
        return LinkPair(client, listener.accept())
    }
}

/** Fails the connection once [limit] bytes have been written, like a dropped network. */
class CuttingOutputStream(out: OutputStream, private val limit: Long, private val socket: Socket) : FilterOutputStream(out) {
    private var written = 0L
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (written + len > limit) {
            socket.close()
            throw IOException("simulated network failure")
        }
        out.write(b, off, len)
        written += len
    }
}

/** Flips one byte at an absolute stream position, like on-path tampering. */
class TamperingOutputStream(out: OutputStream, private val position: Long) : FilterOutputStream(out) {
    private var written = 0L
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (position >= written && position < written + len) {
            val copy = b.copyOfRange(off, off + len)
            val at = (position - written).toInt()
            copy[at] = (copy[at].toInt() xor 0x55).toByte()
            out.write(copy, 0, len)
        } else {
            out.write(b, off, len)
        }
        written += len
    }
}

class TestFile(val path: String, val data: ByteArray?, val directory: Boolean = false)

fun testFile(path: String, size: Int, seed: Int = size) = TestFile(path, Random(seed).nextBytes(size))

fun entriesOf(files: List<TestFile>): List<FileEntry> = files.mapIndexed { i, f ->
    FileEntry(
        fileId = "f$i",
        fileName = f.path.substringAfterLast('/'),
        relativePath = f.path,
        fileSize = (f.data?.size ?: 0).toLong(),
        mimeType = "application/octet-stream",
        type = if (f.directory) FileType.DIRECTORY else FileType.REGULAR_FILE,
    )
}

open class RecordingEvents : TransferEvents {
    val statuses = CopyOnWriteArrayList<TransferStatus>()
    val startOffsets = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    @Volatile var onBytesHook: ((Int, Long) -> Unit)? = null

    override fun onStatus(status: TransferStatus) {
        statuses.add(status)
    }
    override fun onFileStarted(index: Int, offset: Long) {
        startOffsets[index] = offset
    }
    override fun onBytes(index: Int, fileBytes: Long) {
        onBytesHook?.invoke(index, fileBytes)
    }
    override fun onFileFinished(index: Int, ok: Boolean) {}
}

class TestSender(private val files: List<TestFile>, private val acceptPairing: Boolean = true) :
    RecordingEvents(), SenderCallbacks {
    @Volatile var code: String? = null
    override suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean {
        this.code = code
        return acceptPairing
    }
    override fun openSource(index: Int): SourceReader {
        val stream = ByteArrayInputStream(files[index].data!!)
        return object : SourceReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int) = stream.read(buffer, offset, length)
            override fun close() {}
        }
    }
}

/** In-memory destination that survives across connections, so resume can be exercised. */
class MemorySink(private val entries: List<FileEntry>) : ReceiveSink {
    val partial = HashMap<Int, ByteArray>()
    val committed = HashMap<Int, Long>()
    val finalized = HashMap<Int, ByteArray>()
    val discarded = HashSet<Int>()
    val directories = HashSet<Int>()
    var bytesWritten = 0L

    override fun resumeState(): List<FileResume> = entries.mapIndexedNotNull { i, e ->
        when {
            finalized.containsKey(i) -> FileResume(e.fileId, e.fileSize, verified = true)
            (committed[i] ?: 0L) > 0L -> FileResume(e.fileId, committed.getValue(i), verified = false)
            else -> null
        }
    }

    override fun createDirectory(index: Int) {
        directories.add(index)
    }

    override fun openFile(index: Int, offset: Long): FileSink {
        var data = (partial[index] ?: ByteArray(0)).copyOf(offset.toInt())
        return object : FileSink {
            override fun readPrefix(digest: MessageDigest, length: Long) = digest.update(data, 0, length.toInt())
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                data += buffer.copyOfRange(offset, offset + length)
                bytesWritten += length
                partial[index] = data
            }
            override fun commit(position: Long) {
                committed[index] = position
            }
            override fun complete(sha256: ByteArray) {
                finalized[index] = data
                partial.remove(index)
                committed.remove(index)
            }
            override fun discard() {
                discarded.add(index)
                partial.remove(index)
                committed.remove(index)
            }
            override fun close() {}
        }
    }
}

class TestReceiver(
    private val acceptPairing: Boolean = true,
    private val acceptTransfer: Boolean = true,
    var sink: MemorySink? = null,
) : RecordingEvents(), ReceiverCallbacks {
    @Volatile var code: String? = null
    @Volatile var manifest: List<FileEntry>? = null

    override suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean {
        this.code = code
        return acceptPairing
    }

    override suspend fun approve(
        peer: PeerInfo, sessionId: String, manifestDigest: ByteArray,
        files: List<FileEntry>, totalBytes: Long, chunkSize: Int,
    ): Approval {
        manifest = files
        if (!acceptTransfer) return Approval.Rejected("Declined")
        val s = sink ?: MemorySink(files).also { sink = it }
        return Approval.Accepted(s)
    }
}

const val SESSION = "0f8fad5b-d9cb-469f-a165-70867728950e"
val CLIENT = LocalDevice("client-1", "Sender phone")
val SERVER = LocalDevice("server-1", "Receiver phone")
