package com.flashdrop.connectivity.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.flashdrop.connectivity.api.Advertisement
import com.flashdrop.connectivity.api.ListenEvent
import com.flashdrop.connectivity.api.TransferTransport
import com.flashdrop.connectivity.api.TransportCapabilities
import com.flashdrop.connectivity.tls.TlsEndpoint
import com.flashdrop.core.model.RemoteDevice
import com.flashdrop.core.model.TransportType
import com.flashdrop.protocol.FdtpLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * LAN transport: DNS-SD (NSD) discovery plus TCP secured with mutually authenticated TLS 1.3.
 * Primary path whenever both devices share a local network.
 */
@Singleton
class LanTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tls: TlsEndpoint,
) : TransferTransport {

    override val type = TransportType.LAN

    private val nsd: NsdManager get() = context.getSystemService(NsdManager::class.java)

    /** Ephemeral ids this device is currently advertising, so discovery can hide itself. */
    private val ownIds = ConcurrentHashMap.newKeySet<String>()

    override fun capabilities() = TransportCapabilities(priority = 0, needsSharedNetwork = true, supportsManualAddress = true)

    override fun isAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ---- discovery -----------------------------------------------------------------------

    override fun discover(): Flow<List<RemoteDevice>> = callbackFlow {
        val found = LinkedHashMap<String, RemoteDevice>() // service name -> device
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        fun publish() {
            trySend(found.values.filter { it.id !in ownIds }.toList())
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("Discovery could not start (NSD error $errorCode)"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(found) { if (found.remove(info.serviceName) != null) publish() }
            }
        }

        // The platform resolves one service at a time, so resolution is serialised here.
        launch {
            for (info in toResolve) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(info) } ?: continue
                val device = toDevice(resolved) ?: continue
                synchronized(found) {
                    found[info.serviceName] = device
                    publish()
                }
            }
        }

        trySend(emptyList())
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            toResolve.close()
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: IllegalArgumentException) {
            }
        }
    }.flowOn(Dispatchers.IO)

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (cont.isActive) cont.resume(serviceInfo)
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun toDevice(info: NsdServiceInfo): RemoteDevice? {
        val host = info.host?.hostAddress ?: return null
        val attrs = info.attributes
        fun text(key: String) = attrs[key]?.let { String(it, Charsets.UTF_8) }
        val major = text(TXT_VERSION)?.toIntOrNull() ?: return null
        val id = text(TXT_ID)?.take(64) ?: return null
        // The name is unauthenticated: it is display-only until pairing proves an identity.
        val name = text(TXT_NAME)?.take(64)?.ifBlank { null } ?: "FlashDrop device"
        return RemoteDevice(id, name, TransportType.LAN, host, info.port, major)
    }

    // ---- client --------------------------------------------------------------------------

    override suspend fun connect(device: RemoteDevice): FdtpLink = tls.connect(device.host, device.port)

    // ---- server --------------------------------------------------------------------------

    override fun listen(advertisement: Advertisement): Flow<ListenEvent> = callbackFlow {
        val server = tls.openServer()
        val port = server.localPort

        val info = NsdServiceInfo().apply {
            serviceName = "FlashDrop-${advertisement.ephemeralId}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_VERSION, PROTOCOL_MAJOR.toString())
            setAttribute(TXT_ID, advertisement.ephemeralId)
            setAttribute(TXT_NAME, advertisement.displayName.take(40))
            setAttribute(TXT_TRANSPORTS, "1")
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        ownIds.add(advertisement.ephemeralId)
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        trySend(ListenEvent.Started(TransportType.LAN, localAddresses(), port))

        launch {
            tls.acceptLoop(server) { link -> trySend(ListenEvent.Incoming(TransportType.LAN, link)).isSuccess }
        }

        awaitClose {
            ownIds.remove(advertisement.ephemeralId)
            try {
                nsd.unregisterService(registration)
            } catch (_: IllegalArgumentException) {
            }
            try {
                server.close()
            } catch (_: IOException) {
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .toList()
    } catch (e: SocketException) {
        emptyList()
    }

    private companion object {
        const val TAG = "LanTransport"
        const val SERVICE_TYPE = "_flashdrop._tcp"
        const val PROTOCOL_MAJOR = 1
        const val TXT_VERSION = "v"
        const val TXT_ID = "id"
        const val TXT_NAME = "n"
        const val TXT_TRANSPORTS = "t"
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
