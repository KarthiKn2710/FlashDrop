package com.flashdrop.domain.repository

import com.flashdrop.core.model.AppSettings
import com.flashdrop.core.model.Diagnostics
import com.flashdrop.core.model.ReceiveState
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.SelectedItem
import com.flashdrop.core.model.TransferRecord
import com.flashdrop.core.model.TransferRecordDetail
import com.flashdrop.core.model.TransferSnapshot
import com.flashdrop.core.model.TrustedDeviceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * Contracts the feature modules program against. Nothing here mentions sockets, Wi-Fi or FDTP
 * classes; URIs cross this boundary as strings so the module stays free of Android types.
 */

/** The single active transfer session and the device's receive mode. */
interface TransferController {
    /** Throttled state of the current session, or null when there is none. */
    val session: StateFlow<TransferSnapshot?>
    val receiving: StateFlow<ReceiveState>

    fun startReceiving()
    fun stopReceiving()

    fun send(device: RemoteDevice, items: List<SelectedItem>)

    fun confirmPairing(accept: Boolean, trustDevice: Boolean)
    fun respondToIncoming(accept: Boolean)

    fun pause()
    fun resume()
    fun cancel()

    /** Reconnects and resumes a send that failed after the connection was lost. */
    fun retry()

    /** Dismisses a finished session from the UI. */
    fun clearFinished()
}

interface DiscoveryRepository {
    /** Peers currently reachable over any available transport. */
    fun devices(): Flow<List<RemoteDevice>>

    /** A device entered by address, for networks where discovery is blocked. */
    fun manualDevice(host: String, port: Int): RemoteDevice

    fun isAnyTransportAvailable(): Boolean
}

/** Files and folders the user has picked for the next send. */
interface SelectionRepository {
    val items: StateFlow<List<SelectedItem>>
    val loading: StateFlow<Boolean>

    fun addFiles(uris: List<String>)
    fun addFolder(treeUri: String)
    fun remove(uri: String)
    fun clear()
}

interface HistoryRepository {
    fun records(): Flow<List<TransferRecord>>
    fun detail(sessionId: String): Flow<TransferRecordDetail?>
    suspend fun delete(sessionId: String)
    suspend fun clearFinished()

    /** Loads a past outgoing transfer's files back into the selection. Returns the item count. */
    suspend fun reselect(sessionId: String): Int
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setDeviceName(name: String)
    suspend fun setStayVisible(value: Boolean)
    suspend fun setNotificationsEnabled(value: Boolean)
    suspend fun setAutoAcceptTrusted(value: Boolean)

    /** [treeUri] null restores the default Download/FlashDrop destination. */
    suspend fun setSaveLocation(treeUri: String?)
    suspend fun diagnostics(): Diagnostics
}

interface TrustedDeviceRepository {
    fun devices(): Flow<List<TrustedDeviceInfo>>
    suspend fun remove(deviceId: String)
}
