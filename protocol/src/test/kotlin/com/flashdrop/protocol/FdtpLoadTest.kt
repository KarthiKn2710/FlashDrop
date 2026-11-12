package com.flashdrop.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Load and stress tests for the FDTP engine. They are skipped in a normal test run; enable with
 *   gradlew :protocol:test -Dfdtp.load=true
 * The test JVM heap is capped (see build.gradle.kts), so a multi-gigabyte transfer passing here
 * demonstrates that memory stays bounded regardless of file size.
 */
class FdtpLoadTest {

    @Before
    fun onlyWhenRequested() = assumeTrue("load tests disabled", System.getProperty("fdtp.load") == "true")

    /** Deterministic content derived from the position, so no source data is held in memory. */
    private class SyntheticSource(private val size: Long, private val seed: Int) : SourceReader {
        private var position = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            for (i in 0 until n) buffer[offset + i] = pattern(position + i, seed)
            position += n
            return n
        }
        override fun close() {}
    }

    private class SyntheticSender(private val sizes: LongArray) : RecordingEvents(), SenderCallbacks {
        override suspend fun confirmPairing(peer: PeerInfo, code: String) = true
        override fun openSource(index: Int): SourceReader = SyntheticSource(sizes[index], index)
    }

    /** Destination that keeps only counters and resumable prefixes it can regenerate. */
    private class CountingSink(private val entries: List<FileEntry>) : ReceiveSink {
        val written = AtomicLong()
        val committed = HashMap<Int, Long>()
        val finalized = HashSet<Int>()
        val discarded = HashSet<Int>()
        var contentErrors = 0

        override fun resumeState(): List<FileResume> = entries.mapIndexedNotNull { i, e ->
            when {
                i in finalized -> FileResume(e.fileId, e.fileSize, verified = true)
                (committed[i] ?: 0L) > 0L -> FileResume(e.fileId, committed.getValue(i), verified = false)
                else -> null
            }
        }
        override fun createDirectory(index: Int) {}
        override fun openFile(index: Int, offset: Long): FileSink = object : FileSink {
            private var position = offset
            override fun readPrefix(digest: MessageDigest, length: Long) {
                val buffer = ByteArray(1 shl 16)
                var at = 0L
                while (at < length) {
                    val n = minOf(buffer.size.toLong(), length - at).toInt()
                    for (i in 0 until n) buffer[i] = pattern(at + i, index)
                    digest.update(buffer, 0, n)
                    at += n
                }
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                // Spot-check content at the chunk edges without hashing everything a third time.
                if (buffer[offset] != pattern(position, index) ||
                    buffer[offset + length - 1] != pattern(position + length - 1, index)
                ) contentErrors++
                position += length
                written.addAndGet(length.toLong())
            }
            override fun commit(position: Long) { committed[index] = position }
            override fun complete(sha256: ByteArray) { finalized.add(index); committed.remove(index) }
            override fun discard() { discarded.add(index); committed.remove(index) }
            override fun close() {}
        }
    }

    private class Receiver(var sink: CountingSink? = null) : RecordingEvents(), ReceiverCallbacks {
        override suspend fun confirmPairing(peer: PeerInfo, code: String) = true
        override suspend fun approve(
            peer: PeerInfo, sessionId: String, manifestDigest: ByteArray,
            files: List<FileEntry>, totalBytes: Long, chunkSize: Int,
        ): Approval = Approval.Accepted(sink ?: CountingSink(files).also { sink = it })
    }

    private fun entries(names: List<String>, sizes: LongArray) = names.mapIndexed { i, path ->
        FileEntry("f$i", path.substringAfterLast('/'), path, sizes[i], "application/octet-stream", type = FileType.REGULAR_FILE)
    }

    private fun run(
        names: List<String>, sizes: LongArray, receiver: Receiver, chunk: Int,
        resume: Boolean = false, control: SessionControl = SessionControl(),
        sender: SyntheticSender = SyntheticSender(sizes),
        wrap: ((LinkPair) -> OutputStream)? = null,
    ): Pair<Result<TransferResult>, Result<TransferResult>> = runBlocking {
        withTimeout(600_000) {
            supervisorScope {
                val pair = linkPair()
                val r = async(Dispatchers.IO) {
                    runCatching { FdtpReceiver(pair.serverLink(), SERVER, receiver, SessionControl(), maxChunkSize = chunk).run() }
                }
                val s = async(Dispatchers.IO) {
                    runCatching {
                        FdtpSender(pair.clientLink(wrap?.invoke(pair)), CLIENT, SESSION, entries(names, sizes), resume, sender, control, chunk).run()
                    }
                }
                s.await() to r.await()
            }
        }
    }

    private fun usedHeapMb(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }

    @Test
    fun fourGigabyteFileStaysWithinASmallHeap() {
        val size = 4L shl 30
        val receiver = Receiver()
        var peak = 0L
        val sender = SyntheticSender(longArrayOf(size))
        sender.onBytesHook = { _, bytes -> if (bytes % (256L shl 20) == 0L) peak = maxOf(peak, usedHeapMb()) }
        val started = System.nanoTime()
        val (sent, _) = run(listOf("huge.bin"), longArrayOf(size), receiver, Fdtp.DEFAULT_CHUNK, sender = sender)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(setOf(0), sent.getOrThrow().completed)
        assertEquals(size, receiver.sink!!.written.get())
        assertEquals(0, receiver.sink!!.contentErrors)
        println("LOAD 4 GiB single file: %.1f s, %.0f MB/s, peak heap %d MB (max %d MB)".format(seconds, size / 1e6 / seconds, peak, Runtime.getRuntime().maxMemory() shr 20))
        // The real bound is the capped test heap: an unbounded engine would die with OutOfMemoryError.
    }

    @Test
    fun tenThousandSmallFiles() {
        val count = 10_000
        val sizes = LongArray(count) { (it * 37 % 4096).toLong() }
        val names = List(count) { "batch/d${it % 50}/file-$it.dat" }
        val receiver = Receiver()
        val started = System.nanoTime()
        val (sent, received) = run(names, sizes, receiver, Fdtp.DEFAULT_CHUNK)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(count, sent.getOrThrow().completed.size)
        assertEquals(count, received.getOrThrow().completed.size)
        assertEquals(sizes.sum(), receiver.sink!!.written.get())
        println("LOAD 10,000 small files: %.1f s, %.0f files/s".format(seconds, count / seconds))
    }

    @Test
    fun thousandMixedFilesInDeepFolders() {
        val count = 1_000
        val sizes = LongArray(count) { if (it % 100 == 0) 20L shl 20 else (it * 7919L % 300_000) }
        val deep = (1..28).joinToString("/") { "level$it" }
        val names = List(count) { if (it % 10 == 0) "$deep/n-$it.bin" else "mixed/set${it % 7}/файл-$it.bin" }
        val receiver = Receiver()
        val started = System.nanoTime()
        val (sent, _) = run(names, sizes, receiver, Fdtp.DEFAULT_CHUNK)
        val seconds = (System.nanoTime() - started) / 1e9
        assertEquals(count, sent.getOrThrow().completed.size)
        assertEquals(0, receiver.sink!!.contentErrors)
        println("LOAD 1,000 mixed files (%d MB): %.1f s".format(sizes.sum() shr 20, seconds))
    }

    @Test
    fun interruptionsAtQuarterHalfAndNinetyPercentResumeWithoutLoss() {
        val chunk = Fdtp.MIN_CHUNK
        val size = 256L shl 20
        val receiver = Receiver()
        val names = listOf("interrupted.bin")
        val sizes = longArrayOf(size)
        var resume = false
        for (percent in intArrayOf(25, 50, 90)) {
            val target = size * percent / 100
            val alreadyCommitted = receiver.sink?.committed?.get(0) ?: 0L
            val (sent, _) = run(names, sizes, receiver, chunk, resume = resume, wrap = { pair ->
                CuttingOutputStream(pair.client.getOutputStream(), target - alreadyCommitted, pair.client)
            })
            assertTrue("attempt at $percent% must be interrupted", sent.isFailure)
            val committed = receiver.sink!!.committed[0] ?: 0L
            assertTrue("progress must be kept at $percent%: $committed", committed > alreadyCommitted)
            assertEquals(0L, committed % chunk)
            resume = true
        }
        val sender = SyntheticSender(sizes)
        val (sent, _) = run(names, sizes, receiver, chunk, resume = true, sender = sender)
        assertEquals(setOf(0), sent.getOrThrow().completed)
        val sink = receiver.sink!!
        assertEquals(0, sink.contentErrors)
        // Committed data is never resent: the only overhead is what was in flight at each cut.
        val overhead = sink.written.get() - size
        val window = chunk.toLong() * (Fdtp.WINDOW_CHUNKS + 2)
        println("LOAD interruptions 25/50/90%%: retransmitted %d KiB in total (limit %d KiB)".format(overhead shr 10, 3 * window shr 10))
        assertTrue("retransmission must be bounded by the flow-control window", overhead in 0..3 * window)
    }

    @Test
    fun fiftyPauseResumeCycles() {
        val chunk = Fdtp.MIN_CHUNK
        val size = 128L shl 20
        val control = SessionControl()
        val sender = SyntheticSender(longArrayOf(size))
        var cycles = 0
        var next = chunk.toLong() * 4
        sender.onBytesHook = { _, bytes ->
            if (cycles < 50 && bytes >= next) {
                cycles++
                next = bytes + chunk.toLong() * 8
                control.localPaused.value = true
                Thread {
                    Thread.sleep(15)
                    control.localPaused.value = false
                }.start()
            }
        }
        val receiver = Receiver()
        val (sent, _) = run(listOf("paused.bin"), longArrayOf(size), receiver, chunk, control = control, sender = sender)
        assertEquals(setOf(0), sent.getOrThrow().completed)
        assertEquals(50, cycles)
        assertEquals(size, receiver.sink!!.written.get())
        assertEquals(0, receiver.sink!!.contentErrors)
        println("LOAD 50 pause/resume cycles: ok")
    }

    @Test
    fun largeManifestOfFiftyThousandEntries() {
        val count = 50_000
        val sizes = LongArray(count) { 0 }
        val names = List(count) { "many/${it / 500}/empty-$it" }
        val receiver = Receiver()
        val started = System.nanoTime()
        val (sent, _) = run(names, sizes, receiver, Fdtp.DEFAULT_CHUNK)
        assertEquals(count, sent.getOrThrow().completed.size)
        println("LOAD 50,000-entry manifest: %.1f s".format((System.nanoTime() - started) / 1e9))
    }

    @Test
    fun sanityOfSyntheticPattern() {
        val a = ByteArray(1000)
        SyntheticSource(1000, 3).read(a, 0, 1000)
        val b = ByteArray(1000) { pattern(it.toLong(), 3) }
        assertArrayEquals(a, b)
    }

    private companion object {
        fun pattern(position: Long, seed: Int): Byte = ((position * 31 + (position ushr 13) + seed * 17) and 0xFF).toByte()
    }
}
