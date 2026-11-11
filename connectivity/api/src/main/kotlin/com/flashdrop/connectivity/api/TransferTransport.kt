package com.flashdrop.connectivity.api

import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.TransportType
import com.flashdrop.protocol.FdtpLink
import kotlinx.coroutines.flow.Flow

/** What the selection policy needs to know about a transport (blueprint section 6.3). */
data class TransportCapabilities(
    /** Lower is preferred when several transports can reach the same peer. */
    val priority: Int,
    val needsSharedNetwork: Boolean,
    val supportsManualAddress: Boolean,
)

/** Only non-identifying, ephemeral data may be advertised (blueprint section 6.4). */
data class Advertisement(val ephemeralId: String, val displayName: String)

sealed interface ListenEvent {
    /** The transport is advertising and accepting connections. */
    data class Started(val transport: TransportType, val addresses: List<String>, val port: Int) : ListenEvent

    /** A peer connected and the link is secured; the FDTP session has not started yet. */
    class Incoming(val transport: TransportType, val link: FdtpLink) : ListenEvent
}

/**
 * A connection provider. Every transport carries the same logical FDTP session; nothing above
 * this interface knows about sockets, Wi-Fi Direct groups or Nearby payloads.
 */
interface TransferTransport {
    val type: TransportType

    fun capabilities(): TransportCapabilities

    /** Runtime capability detection: hardware present, radio on, network usable. */
    fun isAvailable(): Boolean

    /** Emits the current set of reachable peers until the collector is cancelled. */
    fun discover(): Flow<List<RemoteDevice>>

    /** Establishes a secured link. Throws IOException when the peer cannot be reached. */
    suspend fun connect(device: RemoteDevice): FdtpLink

    /** Advertises this device and emits incoming links until the collector is cancelled. */
    fun listen(advertisement: Advertisement): Flow<ListenEvent>
}
