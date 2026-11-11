package com.flashdrop.data.repository

import android.net.Uri
import com.flashdrop.connectivity.api.TransferTransport
import com.flashdrop.core.database.TransferDao
import com.flashdrop.core.database.TransferFileEntity
import com.flashdrop.core.database.TransferSessionEntity
import com.flashdrop.core.database.TrustedDeviceDao
import com.flashdrop.core.datastore.SettingsStore
import com.flashdrop.core.model.AppSettings
import com.flashdrop.core.model.Diagnostics
import com.flashdrop.core.model.FileStatus
import com.flashdrop.core.model.FileTransferState
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.SelectedItem
import com.flashdrop.core.model.TransferDirection
import com.flashdrop.core.model.TransferRecord
import com.flashdrop.core.model.TransferRecordDetail
import com.flashdrop.core.model.TransferStatus
import com.flashdrop.core.model.TransportDiagnostic
import com.flashdrop.core.model.TransportType
import com.flashdrop.core.model.TrustedDeviceInfo
import com.flashdrop.core.security.DeviceIdentity
import com.flashdrop.core.security.Fingerprints
import com.flashdrop.domain.repository.DiscoveryRepository
import com.flashdrop.domain.repository.HistoryRepository
import com.flashdrop.domain.repository.SelectionRepository
import com.flashdrop.domain.repository.SettingsRepository
import com.flashdrop.domain.repository.TrustedDeviceRepository
import com.flashdrop.protocol.Fdtp
import com.flashdrop.storage.DocumentScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Capability-driven discovery: every transport that is usable right now contributes its peers. */
@Singleton
class DiscoveryRepositoryImpl @Inject constructor(
    private val transports: Set<@JvmSuppressWildcards TransferTransport>,
) : DiscoveryRepository {

    override fun devices(): Flow<List<RemoteDevice>> {
        val flows = transports.filter { it.isAvailable() }
            .sortedBy { it.capabilities().priority }
            .map { transport -> transport.discover().catch { emit(emptyList()) } }
        if (flows.isEmpty()) return flowOf(emptyList())
        // When several transports reach the same peer, the preferred (first) one wins.
        return combine(flows) { lists -> lists.flatMap { it }.distinctBy { it.id } }
    }

    override fun manualDevice(host: String, port: Int): RemoteDevice =
        RemoteDevice("manual-$host-$port", host, TransportType.LAN, host, port)

    override fun isAnyTransportAvailable(): Boolean = transports.any { it.isAvailable() }
}

@Singleton
class SelectionRepositoryImpl @Inject constructor(private val scanner: DocumentScanner) : SelectionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = AtomicInteger()

    private val _items = MutableStateFlow<List<SelectedItem>>(emptyList())
    override val items: StateFlow<List<SelectedItem>> = _items

    private val _loading = MutableStateFlow(false)
    override val loading: StateFlow<Boolean> = _loading

    override fun addFiles(uris: List<String>) = load { scanner.describe(uris) }

    override fun addFolder(treeUri: String) = load { scanner.scanTree(treeUri) }

    private fun load(block: suspend () -> List<SelectedItem>) {
        pending.incrementAndGet()
        _loading.value = true
        scope.launch {
            try {
                val added = block()
                _items.update { current -> (current + added).distinctBy { it.uri } }
            } finally {
                if (pending.decrementAndGet() == 0) _loading.value = false
            }
        }
    }

    fun replace(items: List<SelectedItem>) {
        _items.value = items
    }

    override fun remove(uri: String) = _items.update { list -> list.filterNot { it.uri == uri } }

    override fun clear() {
        _items.value = emptyList()
    }
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val dao: TransferDao,
    private val selection: SelectionRepositoryImpl,
) : HistoryRepository {

    override fun records(): Flow<List<TransferRecord>> = dao.observeSessions().map { rows -> rows.map { it.toRecord() } }

    override fun detail(sessionId: String): Flow<TransferRecordDetail?> =
        combine(dao.observeSession(sessionId), dao.observeFiles(sessionId)) { session, files ->
            session?.let { TransferRecordDetail(it.toRecord(), files.map { f -> f.toState() }) }
        }

    override suspend fun delete(sessionId: String) = dao.deleteSession(sessionId)

    override suspend fun clearFinished() = dao.deleteFinishedSessions()

    override suspend fun reselect(sessionId: String): Int = withContext(Dispatchers.IO) {
        val items = dao.filesOf(sessionId).mapNotNull { f ->
            val uri = f.sourceUri ?: return@mapNotNull null
            SelectedItem(uri, f.fileName, f.relativePath, f.fileSize, f.mimeType, f.isDirectory)
        }
        if (items.isNotEmpty()) selection.replace(items)
        items.size
    }
}

internal fun TransferSessionEntity.toRecord() = TransferRecord(
    sessionId = sessionId,
    peerName = remoteDeviceName.ifBlank { "Unknown device" },
    direction = enumOr(direction, TransferDirection.SEND),
    transport = enumOr(transport, TransportType.LAN),
    status = enumOr(status, TransferStatus.Failed),
    totalBytes = totalBytes,
    transferredBytes = transferredBytes,
    totalFiles = totalFiles,
    averageSpeed = averageSpeed,
    message = message,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun TransferFileEntity.toState() = FileTransferState(
    fileId = fileId,
    name = fileName,
    relativePath = relativePath,
    size = fileSize,
    completedBytes = completedBytes,
    status = enumOr(status, FileStatus.Pending),
    isDirectory = isDirectory,
)

private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: fallback

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val store: SettingsStore,
    private val scanner: DocumentScanner,
    private val identity: DeviceIdentity,
    private val transports: Set<@JvmSuppressWildcards TransferTransport>,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = store.settings

    override suspend fun setDeviceName(name: String) {
        if (name.isNotBlank()) store.setDeviceName(name)
    }

    override suspend fun setStayVisible(value: Boolean) {
        store.setStayVisible(value)
    }

    override suspend fun setNotificationsEnabled(value: Boolean) {
        store.setNotifications(value)
    }

    override suspend fun setAutoAcceptTrusted(value: Boolean) {
        store.setAutoAcceptTrusted(value)
    }

    override suspend fun setSaveLocation(treeUri: String?) {
        if (treeUri == null) {
            store.setSaveLocation(null, null)
            return
        }
        withContext(Dispatchers.IO) {
            scanner.persist(Uri.parse(treeUri), write = true)
            store.setSaveLocation(treeUri, scanner.treeLabel(treeUri))
        }
    }

    override suspend fun diagnostics(): Diagnostics = withContext(Dispatchers.IO) {
        val byType = transports.associateBy { it.type }
        Diagnostics(
            protocolVersion = "FDTP ${Fdtp.VERSION_MAJOR}.${Fdtp.VERSION_MINOR}",
            deviceFingerprint = Fingerprints.display(identity.fingerprint),
            transports = TransportType.entries.map { type ->
                val transport = byType[type]
                TransportDiagnostic(type, implemented = transport != null, available = transport?.isAvailable() == true)
            },
        )
    }
}

@Singleton
class TrustedDeviceRepositoryImpl @Inject constructor(private val dao: TrustedDeviceDao) : TrustedDeviceRepository {
    override fun devices(): Flow<List<TrustedDeviceInfo>> = dao.observeAll().map { rows ->
        rows.map {
            TrustedDeviceInfo(it.deviceId, it.displayName, it.publicKeyFingerprint.take(16).uppercase().chunked(4).joinToString(" "), it.trustedAt, it.lastSeenAt)
        }
    }

    override suspend fun remove(deviceId: String) = dao.delete(deviceId)
}
