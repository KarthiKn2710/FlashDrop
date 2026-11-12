package com.flashdrop.data.transfer

import android.util.Log
import com.flashdrop.connectivity.api.Advertisement
import com.flashdrop.connectivity.api.ListenEvent
import com.flashdrop.connectivity.api.TransferTransport
import com.flashdrop.core.database.FlashDropDatabase
import com.flashdrop.core.database.TransferDao
import com.flashdrop.core.database.TransferFileEntity
import com.flashdrop.core.database.TransferSessionEntity
import com.flashdrop.core.database.TrustedDeviceDao
import com.flashdrop.core.database.TrustedDeviceEntity
import com.flashdrop.core.datastore.SettingsStore
import com.flashdrop.core.model.AppSettings
import com.flashdrop.core.model.FileStatus
import com.flashdrop.core.model.IncomingRequest
import com.flashdrop.core.model.PairingPrompt
import com.flashdrop.core.model.ReceiveState
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.SelectedItem
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.core.model.TransportType
import com.flashdrop.core.security.DeviceIdentity
import com.flashdrop.core.security.Fingerprints
import com.flashdrop.domain.repository.TransferController
import com.flashdrop.protocol.Approval
import com.flashdrop.protocol.ErrorCode
import com.flashdrop.protocol.FdtpConnection
import com.flashdrop.protocol.FdtpLink
import com.flashdrop.protocol.FdtpReceiver
import com.flashdrop.protocol.FdtpSender
import com.flashdrop.protocol.FileEntry
import com.flashdrop.protocol.FileType
import com.flashdrop.protocol.Hashing
import com.flashdrop.protocol.LocalDevice
import com.flashdrop.protocol.ManifestValidator
import com.flashdrop.protocol.PairingFailedException
import com.flashdrop.protocol.PeerInfo
import com.flashdrop.protocol.ProtocolException
import com.flashdrop.protocol.ReceiverCallbacks
import com.flashdrop.protocol.RemoteCancelledException
import com.flashdrop.protocol.SenderCallbacks
import com.flashdrop.protocol.SourceReader
import com.flashdrop.protocol.TransferRejectedException
import com.flashdrop.protocol.TransferResult
import com.flashdrop.storage.DestinationFactory
import com.flashdrop.storage.DestinationStore
import com.flashdrop.storage.DocumentScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session manager: owns the single live FDTP session, drives the engine over whichever transport
 * reaches the peer, persists state transitions, and exposes throttled state to the UI.
 */
@Singleton
class TransferManager @Inject constructor(
    private val transports: Set<@JvmSuppressWildcards TransferTransport>,
    private val identity: DeviceIdentity,
    private val database: FlashDropDatabase,
    private val dao: TransferDao,
    private val trustedDao: TrustedDeviceDao,
    private val settings: SettingsStore,
    private val scanner: DocumentScanner,
    private val destinations: DestinationFactory,
) : TransferController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _session = MutableStateFlow<TransferSnapshot?>(null)
    override val session: StateFlow<TransferSnapshot?> = _session

    private val _receiving = MutableStateFlow(ReceiveState())
    override val receiving: StateFlow<ReceiveState> = _receiving

    @Volatile private var active: ActiveSession? = null
    private var listenJob: Job? = null
    private var stopWhenIdle = false
    private val provisional = AtomicInteger()

    init {
        scope.launch { recoverAfterRestart() }
        scope.launch {
            if (settings.current().stayVisible) startReceiving()
        }
        // UI progress is published at ~5 Hz, independently of how fast the engine moves bytes.
        scope.launch {
            while (true) {
                delay(PUBLISH_INTERVAL_MS)
                val s = active ?: continue
                if (s.status.isTerminal) continue
                s.sample(System.currentTimeMillis())
                publish(s)
            }
        }
    }

    // ---- receive mode ----------------------------------------------------------------------

    override fun startReceiving() {
        synchronized(lock) {
            if (listenJob?.isActive == true) return
            listenJob = scope.launch { listen() }
        }
    }

    override fun stopReceiving() {
        synchronized(lock) {
            // Stopping tears down the listening transports (for Wi-Fi Direct, the whole group),
            // which would kill an incoming transfer in flight: finish that first.
            val receivingNow = active?.let { it.direction == TransferDirection.RECEIVE && !it.status.isTerminal } == true
            if (receivingNow) {
                stopWhenIdle = true
                return
            }
            stopWhenIdle = false
            listenJob?.cancel()
            listenJob = null
        }
        _receiving.value = ReceiveState()
    }

    private suspend fun listen() {
        val name = settings.current().deviceName
        // A fresh random id per receive session: nothing permanent is ever advertised.
        val advertisement = Advertisement(Hashing.hex(Hashing.randomBytes(6)), name)
        _receiving.value = ReceiveState(deviceName = name)
        try {
            val available = transports.filter { it.isAvailable() }
            if (available.isEmpty()) {
                _receiving.value = ReceiveState(deviceName = name, error = "Turn on Wi-Fi to receive files")
                return
            }
            // One transport failing to start must not take the others down with it.
            val flows = available.map { transport ->
                transport.listen(advertisement).catch { e -> Log.w(TAG, "${transport.type} could not listen", e) }
            }
            flows.merge().collect { event ->
                when (event) {
                    is ListenEvent.Started -> _receiving.update { current ->
                        // Only address-based transports (LAN) contribute a manual address.
                        val hasAddress = event.addresses.isNotEmpty()
                        current.copy(
                            visible = true,
                            deviceName = name,
                            addresses = if (hasAddress) event.addresses else current.addresses,
                            port = if (hasAddress) event.port else current.port,
                            error = null,
                        )
                    }
                    is ListenEvent.Incoming -> onIncomingLink(event.link, event.transport, advertisement)
                }
            }
            if (!_receiving.value.visible) {
                _receiving.value = ReceiveState(deviceName = name, error = "Could not start receiving")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Receive mode failed", e)
            _receiving.value = ReceiveState(deviceName = name, error = e.message ?: "Could not start receiving")
        }
    }

    /**
     * A TLS connection alone proves nothing about intent, so a new link starts as a provisional
     * session that the UI never sees. It only becomes the active session in [claim], once the
     * peer has completed the FDTP hello. Port scans and reconnect probes therefore cause no
     * flicker and cannot displace a session in progress.
     */
    private fun onIncomingLink(link: FdtpLink, transport: TransportType, advertisement: Advertisement) {
        if (provisional.incrementAndGet() > MAX_PROVISIONAL_SESSIONS) {
            provisional.decrementAndGet()
            scope.launch { FdtpConnection.rejectBusy(link) }
            return
        }
        val s = ActiveSession(TransferDirection.RECEIVE, "", transport).also { it.status = TransferStatus.Authenticating }
        s.job = scope.launch {
            try {
                runReceive(s, link, advertisement)
            } finally {
                if (!s.claimed) provisional.decrementAndGet()
            }
        }
    }

    /**
     * The same peer connecting again while its transfer is "running" means that connection is
     * dead and we simply have not timed out yet: treat the old session as interrupted right away.
     */
    private suspend fun supersedeStale(s: ActiveSession, fingerprint: ByteArray) {
        val stale = synchronized(lock) {
            active?.takeIf {
                it !== s && it.direction == TransferDirection.RECEIVE && it.started && !it.status.isTerminal &&
                    it.status != TransferStatus.Interrupted && it.peerFingerprint?.contentEquals(fingerprint) == true
            }
        }
        if (stale != null) {
            stale.job?.cancelAndJoin()
            if (active === stale && !stale.status.isTerminal) {
                setStatus(stale, TransferStatus.Interrupted, "Connection lost. Waiting for the sender to reconnect…")
            }
        }
    }

    /** Makes a provisional incoming session the active one, or refuses it when the device is busy. */
    private fun claim(s: ActiveSession) {
        synchronized(lock) {
            val current = active
            val replaceable = current == null || current === s || current.status.isTerminal ||
                (current.direction == TransferDirection.RECEIVE && current.status == TransferStatus.Interrupted)
            if (!replaceable) throw ProtocolException(ErrorCode.BUSY, "The other device is busy with another transfer")
            current?.watchdog?.cancel()
            active = s
            if (!s.claimed) {
                s.claimed = true
                provisional.decrementAndGet()
            }
        }
    }

    private suspend fun runReceive(s: ActiveSession, link: FdtpLink, advertisement: Advertisement) {
        val engine = FdtpReceiver(
            link, LocalDevice(advertisement.ephemeralId, advertisement.displayName), ReceiveCallbacks(s), s.control,
        )
        s.receiver = engine
        try {
            finish(s, engine.run())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TransferRejectedException) {
            end(s, TransferStatus.Rejected, e.message)
        } catch (e: RemoteCancelledException) {
            s.sink?.discardPartials()
            end(s, TransferStatus.Cancelled, "Cancelled by the sender")
        } catch (e: PairingFailedException) {
            end(s, TransferStatus.Cancelled, e.message)
        } catch (e: ProtocolException) {
            // After a local storage failure the partial data stays resumable; anything else is final.
            val resumable = s.started && e.code == ErrorCode.STORAGE_ERROR
            end(s, TransferStatus.Failed, e.message, persistAs = if (resumable) TransferStatus.Interrupted else TransferStatus.Failed)
        } catch (e: IOException) {
            if (!s.started) {
                end(s, TransferStatus.Failed, "Connection lost")
            } else {
                // Keep the partial files and wait for the sender to reconnect and resume.
                setStatus(s, TransferStatus.Interrupted, "Connection lost. Waiting for the sender to reconnect…")
                s.watchdog = scope.launch {
                    delay(RECEIVER_RESUME_WAIT_MS)
                    if (active === s && s.status == TransferStatus.Interrupted) {
                        end(s, TransferStatus.Failed, "Connection lost", persistAs = TransferStatus.Interrupted)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receive failed", e)
            end(s, TransferStatus.Failed, e.message ?: "Unexpected error")
        }
    }

    // ---- send ------------------------------------------------------------------------------

    override fun send(device: RemoteDevice, items: List<SelectedItem>) {
        if (items.isEmpty()) return
        val s = synchronized(lock) {
            val current = active
            if (current != null && !current.status.isTerminal) return
            current?.watchdog?.cancel()
            ActiveSession(TransferDirection.SEND, UUID.randomUUID().toString(), device.transport).also {
                it.device = device
                it.peerName = device.displayName
                it.assignFiles(buildSlots(items))
                active = it
            }
        }
        publish(s)
        s.job = scope.launch { runSend(s) }
    }

    override fun retry() {
        val s = active ?: return
        if (s.direction != TransferDirection.SEND || !s.canRetry || s.job?.isActive == true) return
        s.canRetry = false
        s.message = null
        s.job = scope.launch { runSend(s) }
    }

    /**
     * Opens the link for a send. The first connection goes to the device the user picked. A
     * reconnection must reach the same authenticated key, but not necessarily the same address:
     * a restarted receiver listens on a new port and advertises a new ephemeral id. So when the
     * old address fails, nearby devices are probed and the one proving the pinned key is used.
     */
    private suspend fun openLink(s: ActiveSession): FdtpLink {
        val device = s.device ?: throw IOException("No device")
        val pinned = s.peerFingerprint
        val transport = transports.firstOrNull { it.type == device.transport }
            ?: throw ProtocolException(ErrorCode.INTERNAL, "This connection type is not available")
        if (!s.started || pinned == null) return transport.connect(device)

        try {
            val link = transport.connect(device)
            if (pinned.contentEquals(link.peerFingerprint)) return link
            link.close()
        } catch (e: IOException) {
            // fall through to rediscovery
        }
        val tried = HashSet<String>()
        val flows = transports.filter { it.isAvailable() }.map { it.discover().catch { } }
        return withTimeoutOrNull(REDISCOVERY_MS) {
            flows.merge().mapNotNull { candidates ->
                var match: FdtpLink? = null
                for (candidate in candidates) {
                    // Joining a Wi-Fi Direct group prompts its owner, so never probe strangers there.
                    if (candidate.transport == TransportType.WIFI_DIRECT && candidate.host != device.host) continue
                    if (!tried.add("${candidate.transport}/${candidate.host}/${candidate.port}")) continue
                    val link = try {
                        transports.first { it.type == candidate.transport }.connect(candidate)
                    } catch (e: IOException) {
                        continue
                    }
                    if (pinned.contentEquals(link.peerFingerprint)) {
                        s.device = candidate
                        match = link
                        break
                    }
                    link.close()
                }
                match
            }.first()
        } ?: throw IOException("The other device is not reachable")
    }

    private suspend fun runSend(s: ActiveSession) {
        val local = LocalDevice(Hashing.hex(Hashing.randomBytes(6)), settings.current().deviceName)
        if (!s.persisted) persistNew(s, manifestDigest = "", destinationTree = null)
        val entries = s.files.map { slot ->
            FileEntry(
                fileId = slot.fileId,
                fileName = slot.name,
                relativePath = slot.relativePath,
                fileSize = slot.size,
                mimeType = slot.mimeType,
                type = if (slot.isDirectory) FileType.DIRECTORY else FileType.REGULAR_FILE,
            )
        }
        var attempt = 0
        var bytesBefore = 0L

        /** Bounded exponential backoff. Returns false when the attempts are used up. */
        suspend fun backOff(): Boolean {
            if (s.transferred.get() > bytesBefore) attempt = 0 // progress was made: start over
            attempt++
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                s.canRetry = true
                end(s, TransferStatus.Failed, "Connection lost")
                return false
            }
            setStatus(s, TransferStatus.Interrupted, "Connection lost. Reconnecting…")
            delay(RECONNECT_BASE_DELAY_MS shl (attempt - 1))
            return true
        }

        while (true) {
            bytesBefore = s.transferred.get()
            try {
                setStatus(s, if (s.started) TransferStatus.Reconnecting else TransferStatus.Connecting, null)
                val link = openLink(s)
                val engine = FdtpSender(link, local, s.sessionId, entries, resume = s.started, SendCallbacks(s), s.control)
                s.sender = engine
                finish(s, engine.run())
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransferRejectedException) {
                end(s, TransferStatus.Rejected, e.message)
                return
            } catch (e: RemoteCancelledException) {
                end(s, TransferStatus.Cancelled, "Cancelled by the receiver")
                return
            } catch (e: PairingFailedException) {
                end(s, if (e.byPeer) TransferStatus.Rejected else TransferStatus.Cancelled, e.message)
                return
            } catch (e: ProtocolException) {
                // "Busy" while resuming means the receiver has not noticed the dropped connection
                // yet; it will, so keep trying like for any other interruption.
                if (s.started && e.remote && e.code == ErrorCode.BUSY) {
                    if (!backOff()) return
                    continue
                }
                // A receiver-side storage problem (e.g. disk full) can be fixed and retried.
                s.canRetry = s.started && e.code == ErrorCode.STORAGE_ERROR
                end(s, TransferStatus.Failed, e.message)
                return
            } catch (e: IOException) {
                if (!s.started) {
                    end(s, TransferStatus.Failed, "Could not connect to ${s.peerName.ifBlank { "the device" }}")
                    return
                }
                if (!backOff()) return
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                end(s, TransferStatus.Failed, e.message ?: "Unexpected error")
                return
            }
        }
    }

    // ---- user actions ----------------------------------------------------------------------

    override fun confirmPairing(accept: Boolean, trustDevice: Boolean) {
        active?.pairing?.decision?.complete(accept to (accept && trustDevice))
    }

    override fun respondToIncoming(accept: Boolean) {
        active?.approval?.complete(accept)
    }

    override fun pause() {
        val s = active ?: return
        if (s.status == TransferStatus.Transferring || s.status == TransferStatus.Paused) s.control.localPaused.value = true
    }

    override fun resume() {
        active?.control?.localPaused?.value = false
    }

    override fun cancel() {
        val s = active ?: return
        if (s.status.isTerminal) return
        scope.launch {
            val who = if (s.direction == TransferDirection.SEND) "sender" else "receiver"
            s.sender?.sendCancel("Cancelled by the $who")
            s.receiver?.sendCancel("Cancelled by the $who")
            // Closing a socket with unread data resets it, and a reset can destroy the cancel
            // message in flight. Keep the connection draining briefly so the peer reads it.
            if (s.sender != null || s.receiver != null) delay(CANCEL_GRACE_MS)
            s.watchdog?.cancel()
            s.job?.cancelAndJoin()
            try {
                s.sink?.discardPartials()
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup after cancel failed", e)
            }
            end(s, TransferStatus.Cancelled, "Cancelled")
        }
    }

    override fun clearFinished() {
        synchronized(lock) {
            val s = active ?: return
            if (!s.status.isTerminal) return
            s.watchdog?.cancel()
            active = null
        }
        _session.value = null
    }

    // ---- engine callbacks ------------------------------------------------------------------

    private abstract inner class BaseCallbacks(protected val s: ActiveSession) {
        fun onStatus(status: TransferStatus) {
            if (status == TransferStatus.Transferring) s.started = true
            setStatus(s, status, null)
        }

        fun onFileStarted(index: Int, offset: Long) {
            s.setFileBytes(index, offset)
            s.setFileStatus(index, FileStatus.Transferring)
        }

        fun onBytes(index: Int, fileBytes: Long) = s.setFileBytes(index, fileBytes)

        fun onFileFinished(index: Int, ok: Boolean) {
            s.setFileStatus(index, if (ok) FileStatus.Completed else FileStatus.Failed)
        }

        /**
         * Authenticates the peer's key. Known keys pass silently (TLS already proved possession);
         * unknown ones need both users to compare and confirm the short code.
         */
        suspend fun confirmPairing(peer: PeerInfo, code: String): Boolean {
            val name = peer.displayName.ifBlank { "Unknown device" }
            val hex = Fingerprints.hex(peer.fingerprint)
            s.peerName = name
            s.peerDeviceId = peer.deviceId
            if (s.authenticated && s.peerFingerprint?.contentEquals(peer.fingerprint) == true) return true
            s.peerFingerprint = peer.fingerprint

            val now = System.currentTimeMillis()
            if (trustedDao.find(hex) != null) {
                trustedDao.touch(hex, name, now)
                s.trusted = true
                s.authenticated = true
                return true
            }
            // A sender reconnecting to resume was already confirmed for that session.
            if (s.direction == TransferDirection.RECEIVE && dao.countResumableFrom(hex, now - RETENTION_MS) > 0) {
                s.authenticated = true
                return true
            }

            // Only now does the session need the screen; until then it stays provisional.
            if (s.direction == TransferDirection.RECEIVE) {
                supersedeStale(s, peer.fingerprint)
                claim(s)
            }
            val decision = CompletableDeferred<Pair<Boolean, Boolean>>()
            s.pairing = PendingPairing(PairingPrompt(name, code, Fingerprints.display(peer.fingerprint)), decision)
            publish(s)
            try {
                val (accept, trust) = decision.await()
                if (accept && trust) {
                    trustedDao.upsert(TrustedDeviceEntity(hex, name, hex, now, now))
                    s.trusted = true
                }
                s.authenticated = accept
                return accept
            } finally {
                s.pairing = null
                publish(s)
            }
        }
    }

    private inner class SendCallbacks(s: ActiveSession) : BaseCallbacks(s), SenderCallbacks {
        override fun openSource(index: Int): SourceReader {
            val uri = s.files[index].sourceUri ?: throw FileNotFoundException("No source for file")
            return scanner.openReader(uri)
        }
    }

    private inner class ReceiveCallbacks(s: ActiveSession) : BaseCallbacks(s), ReceiverCallbacks {
        override suspend fun approve(
            peer: PeerInfo,
            sessionId: String,
            manifestDigest: ByteArray,
            files: List<FileEntry>,
            totalBytes: Long,
            chunkSize: Int,
        ): Approval {
            val fingerprint = Fingerprints.hex(peer.fingerprint)
            val digest = Hashing.hex(manifestDigest)
            val slots = files.map { FileSlot(it.fileId, it.fileName, it.relativePath, it.fileSize, it.mimeType, it.isDirectory, null) }

            supersedeStale(s, peer.fingerprint)

            // Resume: same session, same peer key, same manifest. No second approval needed.
            // A refused resume is answered without ever becoming the visible session, so it
            // cannot replace what the user is looking at (e.g. the summary of a cancelled transfer).
            val existing = dao.findSession(sessionId)
            if (existing != null) {
                val resumable = existing.direction == TransferDirection.RECEIVE.name &&
                    existing.status == TransferStatus.Interrupted.name &&
                    existing.remoteFingerprint == fingerprint && existing.manifestDigest == digest
                if (!resumable) {
                    return Approval.Rejected(
                        if (existing.status == TransferStatus.Cancelled.name) "Cancelled by the receiver" else "This transfer cannot be resumed",
                    )
                }
                claim(s)
                val store = try {
                    destinations.create(existing.destinationTree, "Custom folder")
                } catch (e: Exception) {
                    return Approval.Rejected("The destination folder is no longer available")
                }
                s.sessionId = sessionId
                s.assignFiles(slots)
                s.persisted = true
                useDestination(s, store)
                return Approval.Accepted(PersistentReceiveSink(sessionId, files, chunkSize, store, dao).also { s.sink = it })
            }

            claim(s)
            val config = settings.current()
            val store = openDestination(config)
            val free = store.freeBytes()
            if (free != null && totalBytes > free - FREE_SPACE_RESERVE) {
                return Approval.Rejected("Not enough free space on the receiving device")
            }

            s.sessionId = sessionId
            s.assignFiles(slots)
            useDestination(s, store)
            val accepted = if (s.trusted && config.autoAcceptTrusted) true else {
                val decision = CompletableDeferred<Boolean>()
                s.incoming = IncomingRequest(
                    peerName = s.peerName,
                    trusted = s.trusted,
                    fileCount = files.count { !it.isDirectory },
                    folderCount = files.mapNotNull { it.relativePath.substringBefore('/', "").ifEmpty { null } }.distinct().size,
                    totalBytes = totalBytes,
                    previewNames = files.take(4).map { it.relativePath },
                    destinationLabel = store.label,
                )
                s.approval = decision
                publish(s)
                try {
                    decision.await()
                } finally {
                    s.incoming = null
                    s.approval = null
                    publish(s)
                }
            }
            if (!accepted) return Approval.Rejected("Declined")

            persistNew(s, digest, config.saveTreeUri.takeIf { store.rootUri != null })
            return Approval.Accepted(PersistentReceiveSink(sessionId, files, chunkSize, store, dao).also { s.sink = it })
        }
    }

    private fun openDestination(config: AppSettings): DestinationStore = try {
        destinations.create(config.saveTreeUri, config.saveLabel)
    } catch (e: Exception) {
        // The chosen folder was removed or its grant revoked: fall back to the default location.
        destinations.create(null, AppSettings.DEFAULT_SAVE_LABEL)
    }

    private fun useDestination(s: ActiveSession, store: DestinationStore) {
        s.destinationLabel = store.label
        s.destinationUri = store.rootUri
    }

    // ---- state & persistence ---------------------------------------------------------------

    private fun publish(s: ActiveSession) {
        if (active === s) _session.value = s.snapshot()
    }

    private fun setStatus(s: ActiveSession, status: TransferStatus, message: String?, persistAs: TransferStatus = status) {
        s.status = status
        s.message = message
        if (s.persisted) {
            try {
                dao.updateSession(s.sessionId, persistAs.name, s.transferred.get(), message, s.averageSpeed(), System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist session state", e)
            }
        }
        publish(s)
    }

    private fun end(s: ActiveSession, status: TransferStatus, message: String?, persistAs: TransferStatus = status) {
        s.sender = null
        s.receiver = null
        s.control.localPaused.value = false
        setStatus(s, status, message, persistAs)
        // A stop requested while this transfer was running takes effect now.
        if (s.direction == TransferDirection.RECEIVE && status.isTerminal && stopWhenIdle) stopReceiving()
    }

    private fun finish(s: ActiveSession, result: TransferResult) {
        for (i in result.completed) s.setFileStatus(i, FileStatus.Completed)
        for (i in result.failed) s.setFileStatus(i, FileStatus.Failed)
        if (s.direction == TransferDirection.SEND && s.persisted) {
            // Receive-side rows are maintained by the sink; mirror the outcome for sent files.
            try {
                database.runInTransaction {
                    s.files.forEachIndexed { i, slot ->
                        if (!slot.isDirectory) dao.updateFileProgress(s.sessionId, slot.fileId, s.fileBytes(i), s.fileStatus(i).name)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist file results", e)
            }
        }
        val failed = result.failed.size
        when {
            failed == 0 -> end(s, TransferStatus.Completed, null)
            result.completed.isEmpty() -> end(s, TransferStatus.Failed, "No file could be transferred")
            else -> end(s, TransferStatus.Completed, "$failed ${if (failed == 1) "file" else "files"} could not be transferred")
        }
    }

    private fun persistNew(s: ActiveSession, manifestDigest: String, destinationTree: String?) {
        val now = System.currentTimeMillis()
        val session = TransferSessionEntity(
            sessionId = s.sessionId,
            remoteDeviceId = s.peerDeviceId.ifEmpty { s.device?.id ?: "" },
            remoteDeviceName = s.peerName,
            remoteFingerprint = s.peerFingerprint?.let(Fingerprints::hex) ?: "",
            direction = s.direction.name,
            transport = s.transport.name,
            status = s.status.name,
            totalBytes = s.totalBytes,
            transferredBytes = 0,
            totalFiles = s.files.count { !it.isDirectory },
            manifestDigest = manifestDigest,
            destinationTree = destinationTree,
            message = null,
            averageSpeed = 0,
            createdAt = s.createdAt,
            updatedAt = now,
        )
        val files = s.files.mapIndexed { i, slot ->
            TransferFileEntity(
                sessionId = s.sessionId,
                fileId = slot.fileId,
                position = i,
                fileName = slot.name,
                relativePath = slot.relativePath,
                fileSize = slot.size,
                mimeType = slot.mimeType,
                sha256 = null,
                status = FileStatus.Pending.name,
                completedBytes = 0,
                isDirectory = slot.isDirectory,
                sourceUri = slot.sourceUri,
                temporaryUri = null,
                destinationUri = null,
            )
        }
        database.createSession(session, files)
        s.persisted = true
    }

    /**
     * Process death recovery. Incoming transfers that were mid-flight stay resumable for the
     * retention period; everything else is closed out, and stale partial files are deleted.
     */
    private fun recoverAfterRestart() {
        try {
            val now = System.currentTimeMillis()
            for (row in dao.unfinishedSessions()) {
                val wasMidTransfer = row.status in MID_TRANSFER_STATES
                val fresh = now - row.updatedAt < RETENTION_MS
                if (row.direction == TransferDirection.RECEIVE.name && wasMidTransfer && fresh) {
                    if (row.status != TransferStatus.Interrupted.name) {
                        dao.updateSession(row.sessionId, TransferStatus.Interrupted.name, row.transferredBytes, "Interrupted", row.averageSpeed, row.updatedAt)
                    }
                    continue
                }
                if (row.direction == TransferDirection.RECEIVE.name) {
                    try {
                        val store = destinations.create(row.destinationTree, "")
                        for (file in dao.filesOf(row.sessionId)) {
                            val temp = file.temporaryUri ?: continue
                            if (file.status != FileStatus.Completed.name) store.discard(temp)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not clean up partial files of ${row.sessionId}", e)
                    }
                }
                dao.updateSession(row.sessionId, TransferStatus.Failed.name, row.transferredBytes, "Interrupted", row.averageSpeed, now)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recovery failed", e)
        }
    }

    // ---- manifest building -----------------------------------------------------------------

    /** Turns the selection into manifest entries with safe, unique destination paths. */
    private fun buildSlots(items: List<SelectedItem>): List<FileSlot> {
        val used = HashSet<String>()
        return items.mapIndexed { i, item ->
            var path = item.relativePath.split('/').map(::sanitizeName).filter { it.isNotEmpty() }.joinToString("/")
            if (path.isEmpty()) path = "file"
            try {
                ManifestValidator.validatePath(path)
            } catch (e: ProtocolException) {
                path = sanitizeName(item.name).ifEmpty { "file" }.take(80) // too deep or too long: flatten
            }
            var candidate = path
            var n = 1
            while (!used.add(candidate.lowercase())) candidate = numbered(path, n++)
            FileSlot("f$i", candidate.substringAfterLast('/'), candidate, if (item.isDirectory) 0 else item.size, item.mimeType, item.isDirectory, item.uri)
        }
    }

    private fun sanitizeName(name: String): String {
        val cleaned = buildString {
            for (c in name) append(if (c.code < 0x20 || c.code == 0x7F || c in "\\/:*?\"<>|") '_' else c)
        }.trim()
        if (cleaned == "." || cleaned == "..") return "_"
        var result = cleaned
        while (result.toByteArray(Charsets.UTF_8).size > 240) result = result.dropLast(1)
        return result
    }

    private fun numbered(path: String, n: Int): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        return if (dot > slash + 1) "${path.substring(0, dot)} ($n)${path.substring(dot)}" else "$path ($n)"
    }

    private companion object {
        const val TAG = "TransferManager"
        const val PUBLISH_INTERVAL_MS = 200L
        const val MAX_RECONNECT_ATTEMPTS = 6
        const val REDISCOVERY_MS = 6_000L
        const val CANCEL_GRACE_MS = 400L
        const val MAX_PROVISIONAL_SESSIONS = 4
        const val RECONNECT_BASE_DELAY_MS = 1_000L
        const val RECEIVER_RESUME_WAIT_MS = 120_000L
        const val RETENTION_MS = 24L * 60 * 60 * 1000
        const val FREE_SPACE_RESERVE = 64L * 1024 * 1024
        val MID_TRANSFER_STATES = setOf(
            TransferStatus.Transferring.name, TransferStatus.Paused.name,
            TransferStatus.Interrupted.name, TransferStatus.Verifying.name,
        )
    }
}
