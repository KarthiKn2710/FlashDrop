package com.flashdrop.data.transfer

import com.flashdrop.core.model.FileStatus
import com.flashdrop.core.model.FileTransferState
import com.flashdrop.core.model.IncomingRequest
import com.flashdrop.core.model.PairingPrompt
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.core.model.TransportType
import com.flashdrop.protocol.FdtpReceiver
import com.flashdrop.protocol.FdtpSender
import com.flashdrop.protocol.SessionControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

internal class FileSlot(
    val fileId: String,
    val name: String,
    val relativePath: String,
    val size: Long,
    val mimeType: String,
    val isDirectory: Boolean,
    val sourceUri: String?,
)

internal class PendingPairing(val prompt: PairingPrompt, val decision: CompletableDeferred<Pair<Boolean, Boolean>>)

/**
 * Mutable, thread-safe state of the one live session. The engine updates counters from its I/O
 * threads; the UI only ever sees immutable [TransferSnapshot]s built from it a few times a second.
 */
internal class ActiveSession(val direction: TransferDirection, @Volatile var sessionId: String, val transport: TransportType) {
    val createdAt = System.currentTimeMillis()
    val control = SessionControl()

    @Volatile var status = TransferStatus.Connecting
    @Volatile var message: String? = null
    @Volatile var peerName = ""
    @Volatile var peerDeviceId = ""
    @Volatile var peerFingerprint: ByteArray? = null
    @Volatile var trusted = false
    @Volatile var authenticated = false

    @Volatile var files: List<FileSlot> = emptyList()
    @Volatile private var fileBytes = AtomicLongArray(0)
    @Volatile private var fileStatus = AtomicReferenceArray<FileStatus>(0)
    @Volatile var totalBytes = 0L
    @Volatile var currentIndex = -1
    val transferred = AtomicLong()

    @Volatile var pairing: PendingPairing? = null
    @Volatile var incoming: IncomingRequest? = null
    @Volatile var approval: CompletableDeferred<Boolean>? = null

    @Volatile var job: Job? = null
    @Volatile var watchdog: Job? = null
    @Volatile var sender: FdtpSender? = null
    @Volatile var receiver: FdtpReceiver? = null
    @Volatile var sink: PersistentReceiveSink? = null

    /** The data phase was reached at least once, so a lost connection can be resumed. */
    @Volatile var started = false
    @Volatile var persisted = false

    /** An incoming session becomes the active one only after the peer completed the FDTP hello. */
    @Volatile var claimed = false
    @Volatile var canRetry = false
    @Volatile var device: RemoteDevice? = null
    @Volatile var destinationLabel: String? = null
    @Volatile var destinationUri: String? = null

    // Throughput sampling; touched only by the publisher coroutine.
    private val samples = ArrayDeque<LongArray>()
    @Volatile private var speed = 0L
    @Volatile private var eta: Long? = null
    @Volatile private var activeMillis = 0L
    @Volatile private var baselineBytes = -1L
    @Volatile private var lastSampleAt = 0L

    fun assignFiles(slots: List<FileSlot>) {
        files = slots
        fileBytes = AtomicLongArray(slots.size)
        fileStatus = AtomicReferenceArray<FileStatus>(slots.size).also { array ->
            for (i in slots.indices) array.set(i, FileStatus.Pending)
        }
        totalBytes = slots.sumOf { it.size }
        transferred.set(0)
    }

    fun setFileBytes(index: Int, bytes: Long) {
        val array = fileBytes
        if (index !in 0 until array.length()) return
        val previous = array.getAndSet(index, bytes)
        transferred.addAndGet(bytes - previous)
    }

    fun setFileStatus(index: Int, status: FileStatus) {
        val array = fileStatus
        if (index in 0 until array.length()) array.set(index, status)
        if (status == FileStatus.Transferring) currentIndex = index
    }

    fun fileStatus(index: Int): FileStatus = fileStatus.get(index)
    fun fileBytes(index: Int): Long = fileBytes.get(index)

    fun failedCount(): Int {
        val array = fileStatus
        var n = 0
        for (i in 0 until array.length()) if (array.get(i) == FileStatus.Failed) n++
        return n
    }

    /** Called on every publisher tick. ETA appears only once throughput has been stable for a while. */
    fun sample(now: Long) {
        if (status != TransferStatus.Transferring) {
            samples.clear()
            speed = 0
            eta = null
            lastSampleAt = 0
            return
        }
        val bytes = transferred.get()
        if (baselineBytes < 0) baselineBytes = bytes
        if (lastSampleAt != 0L) activeMillis += now - lastSampleAt
        lastSampleAt = now
        samples.addLast(longArrayOf(now, bytes))
        while (samples.size > 2 && now - samples.first()[0] > SPEED_WINDOW_MS) samples.removeFirst()
        val first = samples.first()
        val span = now - first[0]
        if (span >= 600) {
            speed = ((bytes - first[1]).coerceAtLeast(0) * 1000 / span)
            eta = if (span >= ETA_MIN_SPAN_MS && speed > 0) (totalBytes - bytes).coerceAtLeast(0) / speed else null
        }
    }

    fun averageSpeed(): Long {
        val millis = activeMillis
        if (millis < 200 || baselineBytes < 0) return 0
        return (transferred.get() - baselineBytes).coerceAtLeast(0) * 1000 / millis
    }

    fun snapshot(): TransferSnapshot {
        val slots = files
        val current = currentIndex
        // Very large batches are windowed around the current file to keep snapshots cheap.
        val range = if (slots.size <= MAX_VISIBLE_FILES) slots.indices else {
            val from = (current - 5).coerceIn(0, slots.size - MAX_VISIBLE_FILES)
            from until from + MAX_VISIBLE_FILES
        }
        val states = range.map { i ->
            val slot = slots[i]
            FileTransferState(slot.fileId, slot.name, slot.relativePath, slot.size, fileBytes(i), fileStatus(i), slot.isDirectory)
        }
        return TransferSnapshot(
            sessionId = sessionId,
            direction = direction,
            peerName = peerName.ifBlank { "Unknown device" },
            transport = transport,
            status = status,
            totalBytes = totalBytes,
            transferredBytes = transferred.get().coerceIn(0, totalBytes.coerceAtLeast(0)),
            speedBytesPerSecond = speed,
            remainingSeconds = eta,
            files = states,
            currentFile = slots.getOrNull(current)?.name,
            pairing = pairing?.prompt,
            incoming = incoming,
            message = message ?: "Paused by the other device".takeIf { status == TransferStatus.Paused && !control.localPaused.value },
            failedFiles = failedCount(),
            averageSpeed = averageSpeed(),
            elapsedMillis = activeMillis,
            destinationLabel = destinationLabel,
            destinationUri = destinationUri,
            canRetry = canRetry,
            pausedLocally = control.localPaused.value,
        )
    }

    private companion object {
        const val SPEED_WINDOW_MS = 3_000L
        const val ETA_MIN_SPAN_MS = 2_000L
        const val MAX_VISIBLE_FILES = 300
    }
}
