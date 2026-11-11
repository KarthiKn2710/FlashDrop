package com.flashdrop.protocol

import com.flashdrop.core.model.TransferStatus
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class FdtpSessionTest {

    private class Outcome(val sender: Result<TransferResult>, val receiver: Result<TransferResult>)

    private fun runPair(
        files: List<TestFile>,
        sender: TestSender = TestSender(files),
        receiver: TestReceiver = TestReceiver(),
        resume: Boolean = false,
        senderControl: SessionControl = SessionControl(),
        wrapOutput: ((LinkPair) -> java.io.OutputStream)? = null,
        entries: List<FileEntry> = entriesOf(files),
    ): Outcome = runBlocking {
        withTimeout(60_000) {
            supervisorScope {
                val pair = linkPair()
                val r: Deferred<TransferResult> = async(Dispatchers.IO) {
                    FdtpReceiver(pair.serverLink(), SERVER, receiver, SessionControl(), maxChunkSize = TEST_CHUNK).run()
                }
                val s: Deferred<TransferResult> = async(Dispatchers.IO) {
                    FdtpSender(
                        pair.clientLink(wrapOutput?.invoke(pair)), CLIENT, SESSION, entries, resume,
                        sender, senderControl, requestedChunkSize = TEST_CHUNK,
                    ).run()
                }
                Outcome(runCatching { s.await() }, runCatching { r.await() })
            }
        }
    }

    private val sampleFiles = listOf(
        testFile("empty.bin", 0),
        testFile("Photos/a.jpg", 1_000_123),
        TestFile("Photos/Empty folder", null, directory = true),
        testFile("Photos/Über größe/文件.dat", TEST_CHUNK * 3),
        testFile("small.txt", 17),
    )

    @Test
    fun transfersFilesFoldersAndVerifiesIntegrity() {
        val sender = TestSender(sampleFiles)
        val receiver = TestReceiver()
        val outcome = runPair(sampleFiles, sender, receiver)

        val sent = outcome.sender.getOrThrow()
        val received = outcome.receiver.getOrThrow()
        assertEquals(setOf(0, 1, 3, 4), sent.completed)
        assertTrue(sent.failed.isEmpty())
        assertEquals(setOf(0, 1, 3, 4), received.completed)

        val sink = receiver.sink!!
        for (i in listOf(0, 1, 3, 4)) assertArrayEquals(sampleFiles[i].data, sink.finalized[i])
        assertTrue(2 in sink.directories)
        assertEquals("Photos/Über größe/文件.dat", receiver.manifest!![3].relativePath)

        // Both devices display the same six-digit code.
        assertNotNull(sender.code)
        assertEquals(sender.code, receiver.code)
        assertEquals(6, sender.code!!.length)
        assertTrue(TransferStatus.Transferring in sender.statuses)
        assertEquals(TransferStatus.Verifying, sender.statuses.last())
    }

    @Test
    fun manifestIsPaginatedForManyFiles() {
        val files = (0 until 450).map { testFile("dir/file-$it.bin", 10 + it) }
        val receiver = TestReceiver()
        val outcome = runPair(files, receiver = receiver)
        assertEquals(450, outcome.sender.getOrThrow().completed.size)
        assertEquals(450, receiver.sink!!.finalized.size)
        assertArrayEquals(files[449].data, receiver.sink!!.finalized[449])
    }

    @Test
    fun interruptedTransferResumesWithoutResendingCommittedData() {
        val files = listOf(testFile("first.bin", TEST_CHUNK * 2), testFile("big.bin", TEST_CHUNK * 20 + 4321))
        val total = files.sumOf { it.data!!.size }.toLong()
        val receiver = TestReceiver()

        // First attempt: the network dies after roughly 60% of the stream.
        val first = runPair(files, receiver = receiver, wrapOutput = { pair ->
            CuttingOutputStream(pair.client.getOutputStream(), total * 6 / 10, pair.client)
        })
        assertTrue(first.sender.exceptionOrNull() is IOException)
        assertTrue(first.receiver.exceptionOrNull() is IOException)

        val sink = receiver.sink!!
        assertArrayEquals(files[0].data, sink.finalized[0])
        val committed = sink.committed[1]!!
        assertTrue("some data must be committed", committed > 0)
        assertEquals("checkpoints are chunk aligned", 0L, committed % TEST_CHUNK)
        val writtenBefore = sink.bytesWritten

        // Second attempt resumes the same session.
        val sender = TestSender(files)
        val second = runPair(files, sender, receiver, resume = true)
        assertEquals(setOf(0, 1), second.sender.getOrThrow().completed)
        assertArrayEquals(files[1].data, sink.finalized[1])

        // The verified file was skipped entirely, and the big file continued at the checkpoint.
        assertFalse(sender.startOffsets.containsKey(0))
        assertEquals(committed, sender.startOffsets[1])
        assertEquals(files[1].data!!.size - committed, sink.bytesWritten - writtenBefore)
    }

    @Test
    fun tamperedContentFailsVerificationAndIsNeverCommitted() {
        val files = listOf(testFile("victim.bin", TEST_CHUNK * 4), testFile("fine.bin", 5000))
        val receiver = TestReceiver()
        val outcome = runPair(files, receiver = receiver, wrapOutput = { pair ->
            // Lands inside the payload of the second data chunk of the first file.
            TamperingOutputStream(pair.client.getOutputStream(), TEST_CHUNK + 150_000L)
        })
        val sent = outcome.sender.getOrThrow()
        assertEquals(setOf(0), sent.failed)
        assertEquals(setOf(1), sent.completed)
        val sink = receiver.sink!!
        assertTrue(0 in sink.discarded)
        assertFalse(sink.finalized.containsKey(0))
        assertArrayEquals(files[1].data, sink.finalized[1])
    }

    @Test
    fun receiverCanDeclineTheTransfer() {
        val files = listOf(testFile("a.bin", 100))
        val outcome = runPair(files, receiver = TestReceiver(acceptTransfer = false))
        val error = outcome.sender.exceptionOrNull()
        assertTrue(error is TransferRejectedException && error.byPeer)
        assertTrue(outcome.receiver.exceptionOrNull() is TransferRejectedException)
    }

    @Test
    fun decliningThePairingCodeStopsTheSessionBeforeAnyManifest() {
        val files = listOf(testFile("a.bin", 100))
        val receiver = TestReceiver(acceptPairing = false)
        val outcome = runPair(files, receiver = receiver)
        val error = outcome.sender.exceptionOrNull()
        assertTrue(error is PairingFailedException && error.byPeer)
        assertTrue(outcome.receiver.exceptionOrNull() is PairingFailedException)
        assertEquals(null, receiver.manifest)

        val senderDeclines = runPair(files, sender = TestSender(files, acceptPairing = false))
        assertTrue(senderDeclines.sender.exceptionOrNull() is PairingFailedException)
        assertTrue(senderDeclines.receiver.isFailure)
    }

    @Test
    fun pathTraversalInManifestIsRejected() {
        val files = listOf(testFile("ok.bin", 10))
        for (bad in listOf("../escape.bin", "/abs.bin", "a/../../b.bin", "a//b.bin", "con\u0000.bin")) {
            val entries = listOf(entriesOf(files)[0].copy(relativePath = bad, fileName = bad.substringAfterLast('/')))
            val receiver = TestReceiver()
            val outcome = runPair(files, receiver = receiver, entries = entries)
            val local = outcome.receiver.exceptionOrNull()
            assertTrue("path '$bad' must be rejected", local is ProtocolException && local.code == ErrorCode.MANIFEST_INVALID)
            val remote = outcome.sender.exceptionOrNull()
            assertTrue(remote is ProtocolException && remote.remote && remote.code == ErrorCode.MANIFEST_INVALID)
            assertEquals(null, receiver.sink)
        }
    }

    @Test
    fun duplicateDestinationPathsAreRejected() {
        val files = listOf(testFile("Same.bin", 10), testFile("same.bin", 20))
        val outcome = runPair(files)
        val error = outcome.receiver.exceptionOrNull()
        assertTrue(error is ProtocolException && error.code == ErrorCode.MANIFEST_INVALID)
    }

    @Test
    fun pauseAndResumeCompletesTheTransfer() {
        val files = listOf(testFile("movie.bin", TEST_CHUNK * 12))
        val control = SessionControl()
        val sender = TestSender(files)
        var paused = false
        sender.onBytesHook = { _, bytes ->
            if (!paused && bytes >= TEST_CHUNK * 3L) {
                paused = true
                control.localPaused.value = true
                Thread {
                    Thread.sleep(400)
                    control.localPaused.value = false
                }.start()
            }
        }
        val receiver = TestReceiver()
        val outcome = runPair(files, sender, receiver, senderControl = control)
        assertEquals(setOf(0), outcome.sender.getOrThrow().completed)
        assertTrue(TransferStatus.Paused in sender.statuses)
        assertTrue(TransferStatus.Paused in receiver.statuses)
        assertArrayEquals(files[0].data, receiver.sink!!.finalized[0])
    }

    @Test
    fun incompatibleMajorVersionFailsExplicitly() = runBlocking {
        val pair = linkPair()
        supervisorScope {
            val receiver = async(Dispatchers.IO) {
                runCatching { FdtpReceiver(pair.serverLink(), SERVER, TestReceiver(), SessionControl()).run() }
            }
            val link = pair.clientLink()
            val conn = FdtpConnection(link)
            conn.send(FrameType.CLIENT_HELLO, ClientHello(versionMajor = 2, nonceCommitment = ByteArray(32)))
            val error = receiver.await().exceptionOrNull()
            assertTrue(error is ProtocolException && error.code == ErrorCode.UNSUPPORTED_VERSION)

            // The peer is told why, with an ERROR frame.
            val reader = FrameReader(link.input)
            val header = reader.readHeader()!!
            assertEquals(FrameType.ERROR, header.type)
            link.close()
        }
    }

    @Test
    fun cancellingTheSenderUnblocksAndNotifiesTheReceiver() = runBlocking {
        val files = listOf(testFile("huge.bin", TEST_CHUNK * 40))
        val pair = linkPair()
        val senderCallbacks = TestSender(files)
        val control = SessionControl()
        supervisorScope {
            val receiver = async(Dispatchers.IO) {
                runCatching { FdtpReceiver(pair.serverLink(), SERVER, TestReceiver(), SessionControl(), maxChunkSize = TEST_CHUNK).run() }
            }
            val engine = FdtpSender(pair.clientLink(), CLIENT, SESSION, entriesOf(files), false, senderCallbacks, control, TEST_CHUNK)
            val sender = async(Dispatchers.IO) { runCatching { engine.run() } }
            control.localPaused.value = true // hold the transfer open
            while (TransferStatus.Paused !in senderCallbacks.statuses) delay(20)
            engine.sendCancel("Cancelled by sender")
            sender.cancel()
            val error = withTimeout(10_000) { receiver.await() }.exceptionOrNull()
            if (error !is RemoteCancelledException) fail("expected remote cancel but was $error")
        }
    }
}
