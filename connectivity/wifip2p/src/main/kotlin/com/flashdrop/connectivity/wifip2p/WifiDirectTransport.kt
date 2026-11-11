package com.flashdrop.connectivity.wifip2p

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.flashdrop.connectivity.api.Advertisement
import com.flashdrop.connectivity.api.ListenEvent
import com.flashdrop.connectivity.api.TransferTransport
import com.flashdrop.connectivity.api.TransportCapabilities
import com.flashdrop.connectivity.tls.TlsEndpoint
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.TransportType
import com.flashdrop.protocol.FdtpLink
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Wi-Fi Direct transport: the no-router path.
 *
 * The receiver forms a Wi-Fi Direct group as its owner and advertises a DNS-SD local service
 * carrying the same non-identifying fields as LAN discovery plus its TLS port. The sender finds
 * the service, joins the group and opens the usual mutually authenticated TLS 1.3 socket to the
 * group owner. From there on the FDTP session is identical to the LAN case.
 */
@Singleton
@SuppressLint("MissingPermission") // every entry point is gated by isAvailable()
class WifiDirectTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tls: TlsEndpoint,
) : TransferTransport {

    override val type = TransportType.WIFI_DIRECT

    private val manager: WifiP2pManager? by lazy { context.getSystemService(WifiP2pManager::class.java) }
    private val channel: WifiP2pManager.Channel? by lazy { manager?.initialize(context, Looper.getMainLooper(), null) }
    private val ownIds = ConcurrentHashMap.newKeySet<String>()

    override fun capabilities() = TransportCapabilities(priority = 1, needsSharedNetwork = false, supportsManualAddress = false)

    /** Runtime capability detection: hardware, Wi-Fi radio and the nearby-devices permission. */
    override fun isAvailable(): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) return false
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        if (!wifi.isWifiEnabled) return false
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED &&
            manager != null && channel != null
    }

    // ---- discovery -----------------------------------------------------------------------

    override fun discover(): Flow<List<RemoteDevice>> = callbackFlow {
        val manager = this@WifiDirectTransport.manager ?: run { close(); awaitClose {}; return@callbackFlow }
        val channel = this@WifiDirectTransport.channel ?: run { close(); awaitClose {}; return@callbackFlow }
        val found = LinkedHashMap<String, RemoteDevice>() // peer MAC -> device

        manager.setDnsSdResponseListeners(
            channel,
            { _, _, _ -> },
            { fullDomainName, txt, source ->
                if (!fullDomainName.contains(SERVICE_TYPE)) return@setDnsSdResponseListeners
                val major = txt[TXT_VERSION]?.toIntOrNull() ?: return@setDnsSdResponseListeners
                val id = txt[TXT_ID]?.take(64) ?: return@setDnsSdResponseListeners
                val port = txt[TXT_PORT]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@setDnsSdResponseListeners
                if (id in ownIds) return@setDnsSdResponseListeners
                // Unauthenticated, display-only name; identity is established by pairing.
                val name = txt[TXT_NAME]?.take(64)?.ifBlank { null } ?: "FlashDrop device"
                synchronized(found) {
                    found[source.deviceAddress] = RemoteDevice(id, name, TransportType.WIFI_DIRECT, source.deviceAddress, port, major)
                    trySend(found.values.toList())
                }
            },
        )
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        trySend(emptyList())
        launch {
            await { manager.addServiceRequest(channel, request, it) }
            // A service search only runs for a limited time, so it is restarted periodically.
            while (isActive) {
                await { manager.discoverServices(channel, it) }
                delay(DISCOVERY_REFRESH_MS)
            }
        }
        awaitClose {
            manager.removeServiceRequest(channel, request, null)
            manager.stopPeerDiscovery(channel, null)
        }
    }.flowOn(Dispatchers.Main)

    // ---- client --------------------------------------------------------------------------

    override suspend fun connect(device: RemoteDevice): FdtpLink {
        val manager = manager ?: throw IOException("Wi-Fi Direct is not available")
        val channel = channel ?: throw IOException("Wi-Fi Direct is not available")

        // Leave any previous group first (e.g. when reconnecting to resume).
        if (connectionInfo(manager, channel)?.groupFormed == true) {
            await { manager.removeGroup(channel, it) }
            delay(500)
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = device.host
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // the receiver owns the group, so its address is always known
        }
        val info = withTimeoutOrNull(JOIN_TIMEOUT_MS) {
            groupUpdates(manager, channel) {
                val failure = await { manager.connect(channel, config, it) }
                if (failure != null) throw IOException("Wi-Fi Direct connection failed (${reason(failure)})")
            }.first { it.groupFormed }
        } ?: run {
            manager.cancelConnect(channel, null)
            throw IOException("The other device did not accept the Wi-Fi Direct connection")
        }

        val owner = info.groupOwnerAddress?.hostAddress
        if (info.isGroupOwner || owner == null) {
            await { manager.removeGroup(channel, it) }
            throw IOException("Could not join the other device's Wi-Fi Direct group")
        }

        // The group interface needs a moment to get its address after the group forms.
        var last: IOException? = null
        repeat(SOCKET_ATTEMPTS) { attempt ->
            try {
                val link = tls.connect(owner, device.port)
                return GroupLink(link) { manager.removeGroup(channel, null) }
            } catch (e: IOException) {
                last = e
                delay(700L * (attempt + 1))
            }
        }
        await { manager.removeGroup(channel, it) }
        throw last ?: IOException("Wi-Fi Direct connection failed")
    }

    /** Closing the session's link also leaves the Wi-Fi Direct group. */
    private class GroupLink(private val link: FdtpLink, private val leave: () -> Unit) : FdtpLink by link {
        override fun close() {
            link.close()
            try {
                leave()
            } catch (_: Exception) {
            }
        }
    }

    // ---- server --------------------------------------------------------------------------

    override fun listen(advertisement: Advertisement): Flow<ListenEvent> = callbackFlow {
        val manager = this@WifiDirectTransport.manager ?: run { close(); awaitClose {}; return@callbackFlow }
        val channel = this@WifiDirectTransport.channel ?: run { close(); awaitClose {}; return@callbackFlow }
        val server = tls.openServer()
        val port = server.localPort

        val service = WifiP2pDnsSdServiceInfo.newInstance(
            "FlashDrop-${advertisement.ephemeralId}",
            SERVICE_TYPE,
            mapOf(
                TXT_VERSION to PROTOCOL_MAJOR.toString(),
                TXT_ID to advertisement.ephemeralId,
                TXT_NAME to advertisement.displayName.take(40),
                TXT_PORT to port.toString(),
            ),
        )
        ownIds.add(advertisement.ephemeralId)

        launch {
            await { manager.clearLocalServices(channel, it) }
            val serviceFailure = await { manager.addLocalService(channel, service, it) }
            if (serviceFailure != null) Log.w(TAG, "Could not advertise over Wi-Fi Direct: ${reason(serviceFailure)}")

            // Own the group up front: senders can then join without role negotiation.
            var groupFailure = await { manager.createGroup(channel, it) }
            if (groupFailure == WifiP2pManager.BUSY) {
                await { manager.removeGroup(channel, it) }
                delay(500)
                groupFailure = await { manager.createGroup(channel, it) }
            }
            if (groupFailure != null) Log.w(TAG, "Could not create a Wi-Fi Direct group: ${reason(groupFailure)}")
            trySend(ListenEvent.Started(TransportType.WIFI_DIRECT, emptyList(), port))

            // Without a group the device only answers service searches while it is itself scanning.
            while (isActive && groupFailure != null) {
                await { manager.discoverPeers(channel, it) }
                delay(LISTEN_REFRESH_MS)
            }
        }
        launch(Dispatchers.IO) {
            tls.acceptLoop(server) { link -> trySend(ListenEvent.Incoming(TransportType.WIFI_DIRECT, link)).isSuccess }
        }

        awaitClose {
            ownIds.remove(advertisement.ephemeralId)
            manager.removeLocalService(channel, service, null)
            manager.stopPeerDiscovery(channel, null)
            manager.removeGroup(channel, null)
            try {
                server.close()
            } catch (_: IOException) {
            }
        }
    }.flowOn(Dispatchers.Main)

    // ---- helpers -------------------------------------------------------------------------

    /** Runs a WifiP2pManager action; returns null on success or the platform failure code. */
    private suspend fun await(action: (WifiP2pManager.ActionListener) -> Unit): Int? = suspendCancellableCoroutine { cont ->
        action(object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(null)
            }
            override fun onFailure(reason: Int) {
                if (cont.isActive) cont.resume(reason)
            }
        })
    }

    private suspend fun connectionInfo(manager: WifiP2pManager, channel: WifiP2pManager.Channel): WifiP2pInfo? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                manager.requestConnectionInfo(channel) { info -> if (cont.isActive) cont.resume(info) }
            }
        }

    /** Emits group state on every connection change; [start] runs once the receiver is registered. */
    private fun groupUpdates(manager: WifiP2pManager, channel: WifiP2pManager.Channel, start: suspend () -> Unit): Flow<WifiP2pInfo> =
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    manager.requestConnectionInfo(channel) { info -> if (info != null) trySend(info) }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            launch {
                try {
                    start()
                } catch (e: IOException) {
                    close(e)
                }
            }
            awaitClose { context.unregisterReceiver(receiver) }
        }

    private fun reason(code: Int): String = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported on this device"
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "error $code"
    }

    private companion object {
        const val TAG = "WifiDirectTransport"
        const val SERVICE_TYPE = "_flashdrop._tcp"
        const val PROTOCOL_MAJOR = 1
        const val TXT_VERSION = "v"
        const val TXT_ID = "id"
        const val TXT_NAME = "n"
        const val TXT_PORT = "p"
        const val DISCOVERY_REFRESH_MS = 15_000L
        const val LISTEN_REFRESH_MS = 30_000L
        const val JOIN_TIMEOUT_MS = 60_000L
        const val SOCKET_ATTEMPTS = 4
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class WifiDirectModule {
    @Binds
    @IntoSet
    abstract fun bindWifiDirectTransport(transport: WifiDirectTransport): TransferTransport
}
