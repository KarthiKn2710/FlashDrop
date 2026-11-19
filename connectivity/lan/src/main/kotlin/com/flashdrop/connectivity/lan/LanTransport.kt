package com.flashdrop.connectivity.lan

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
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

    /** Ephemeral ids this device is currently advertising, so discovery can skip itself. */
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
        val started = SystemClock.elapsedRealtime()
        val found = LinkedHashMap<String, RemoteDevice>() // service name -> device
        val sightings = ConcurrentHashMap<String, NsdServiceInfo>()
        val multicast = multicastLock()

        fun publish() {
            trySend(synchronized(found) { found.values.toList() })
        }

        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val resolver = ServiceResolver<RemoteDevice>(
            scope = this,
            // Android 14+ resolves from its own cache and allows lookups side by side. Older
            // versions accept a single outstanding lookup per app, so those go one at a time.
            parallelism = if (modern) 4 else 1,
            timeoutMs = if (modern) MODERN_RESOLVE_TIMEOUT_MS else LEGACY_RESOLVE_TIMEOUT_MS,
            maxAttempts = 3,
            retryDelayMs = 1_000,
            resolve = { name ->
                val info = sightings[name]
                if (info == null) null else if (modern) resolveModern(info) else resolveLegacy(info)
            },
            onResolved = { name, device ->
                synchronized(found) { found[name] = device }
                Log.i(TAG, "Found ${device.displayName} at ${device.host} after ${SystemClock.elapsedRealtime() - started} ms")
                publish()
            },
        )

        fun newListener() = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery could not start (NSD error $errorCode)")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                // Our own advertisement: never worth a lookup slot.
                if (ownIds.any { name.contains(it) }) return
                sightings[name] = info
                val known = synchronized(found) { found.containsKey(name) }
                if (!known) resolver.submit(name)
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                resolver.cancel(name)
                val removed = synchronized(found) { found.remove(name) != null }
                if (removed) publish()
            }
        }

        trySend(emptyList())
        Log.i(TAG, "LAN discovery started")
        launch {
            var current: NsdManager.DiscoveryListener? = null
            try {
                var round = 0
                while (isActive) {
                    // Each (re)start sends fresh queries at once. A reply or announcement lost on
                    // a busy network would otherwise only be recovered by the platform's own
                    // retries, which back off to long intervals. The new search starts before the
                    // old one stops, so there is never a gap.
                    val next = newListener()
                    try {
                        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, next)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Discovery restart failed", e)
                    }
                    current?.let(::stopDiscovery)
                    current = next
                    val empty = synchronized(found) { found.isEmpty() }
                    delay(
                        when {
                            !empty -> REQUERY_FOUND_MS
                            round < 3 -> REQUERY_FAST_MS
                            else -> REQUERY_EMPTY_MS
                        },
                    )
                    round++
                }
            } finally {
                current?.let(::stopDiscovery)
            }
        }

        awaitClose {
            resolver.close()
            multicast?.let { if (it.isHeld) it.release() }
        }
    }.flowOn(Dispatchers.IO)

    private fun stopDiscovery(listener: NsdManager.DiscoveryListener) {
        try {
            nsd.stopServiceDiscovery(listener)
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Android 13 and older drop incoming multicast while the screen is off unless asked not to. */
    @SuppressLint("WakelockTimeout")
    private fun multicastLock(): WifiManager.MulticastLock? = try {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("flashdrop:mdns")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    } catch (e: SecurityException) {
        null
    }

    /**
     * Android 14+ reports a service once per network it was seen on, so the first answer may
     * carry only a Wi-Fi Direct or IPv6 address. A usable Wi-Fi address is taken at once; any
     * other is held for a moment in case a better one follows.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveModern(info: NsdServiceInfo): RemoteDevice? {
        val updates = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                updates.close()
            }
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                updates.trySend(serviceInfo)
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        // A sighting is tied to the network it was seen on; asking without one covers them all.
        val query = NsdServiceInfo().apply {
            serviceName = info.serviceName
            serviceType = info.serviceType
        }
        try {
            nsd.registerServiceInfoCallback(query, DIRECT, callback)
        } catch (e: IllegalArgumentException) {
            return null
        }
        try {
            var best: Pair<Int, RemoteDevice>? = null
            var deadline = Long.MAX_VALUE
            while (true) {
                val wait = deadline - SystemClock.elapsedRealtime()
                if (wait <= 0) return best?.second
                val next = withTimeoutOrNull(wait) { updates.receiveCatching().getOrNull() } ?: return best?.second
                val address = pickAddress(next.hostAddresses) ?: continue // no address yet
                val device = toDevice(next, address) ?: continue
                val rank = rank(address, localSubnets())
                if (best == null || rank < best.first) best = rank to device
                if (rank <= 1) return device
                if (deadline == Long.MAX_VALUE) deadline = SystemClock.elapsedRealtime() + BETTER_ADDRESS_WAIT_MS
            }
        } finally {
            updates.close()
            try {
                nsd.unregisterServiceInfoCallback(callback)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveLegacy(info: NsdServiceInfo): RemoteDevice? = suspendCancellableCoroutine { cont ->
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (cont.isActive) cont.resume(serviceInfo.host?.let { toDevice(serviceInfo, it) })
                }
            })
        } catch (e: IllegalArgumentException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun toDevice(info: NsdServiceInfo, address: InetAddress): RemoteDevice? {
        val host = address.hostAddress ?: return null
        if (info.port !in 1..65535) return null
        val attrs = info.attributes
        fun text(key: String) = attrs[key]?.let { String(it, Charsets.UTF_8) }
        val major = text(TXT_VERSION)?.toIntOrNull() ?: return null
        val id = text(TXT_ID)?.take(64) ?: return null
        // The name is unauthenticated: it is display-only until pairing proves an identity.
        val name = text(TXT_NAME)?.take(64)?.ifBlank { null } ?: "FlashDrop device"
        return RemoteDevice(id, name, TransportType.LAN, host, info.port, major)
    }

    /**
     * A device can be reachable at several addresses (Wi-Fi, a Wi-Fi Direct group, IPv6). This
     * is the LAN transport, so prefer an IPv4 address on this device's own Wi-Fi/Ethernet subnet.
     * Wi-Fi Direct group addresses come last: every Android group owner is 192.168.49.1, so when
     * both phones run a group, that address can lead back to the sender itself.
     */
    private fun pickAddress(addresses: List<InetAddress>): InetAddress? {
        if (addresses.size <= 1) return addresses.firstOrNull()
        val subnets = localSubnets()
        return addresses.minByOrNull { rank(it, subnets) }
    }

    /** 0 = this device's own Wi-Fi/Ethernet subnet, 1 = other IPv4, 2 = Wi-Fi Direct group, 3 = IPv6. */
    private fun rank(address: InetAddress, subnets: List<Pair<ByteArray, Int>>): Int = when {
        address !is Inet4Address -> 3
        samePrefix(address.address, WIFI_DIRECT_SUBNET, 24) -> 2
        subnets.any { (local, prefix) -> samePrefix(local, address.address, prefix) } -> 0
        else -> 1
    }

    private fun localSubnets(): List<Pair<ByteArray, Int>> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") }
            .flatMap { it.interfaceAddresses.asSequence() }
            .filter { it.address is Inet4Address }
            .map { it.address.address to it.networkPrefixLength.toInt() }
            .toList()
    } catch (e: SocketException) {
        emptyList()
    }

    private fun samePrefix(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
        if (a.size != b.size || prefix <= 0) return false
        var bits = prefix
        for (i in a.indices) {
            if (bits <= 0) return true
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
        }
        return true
    }

    // ---- client --------------------------------------------------------------------------

    override suspend fun connect(device: RemoteDevice): FdtpLink = tls.connect(device.host, device.port)

    // ---- server --------------------------------------------------------------------------

    override fun listen(advertisement: Advertisement): Flow<ListenEvent> = callbackFlow {
        val server = tls.openServer()
        val port = server.localPort
        // The responder has to receive the senders' multicast queries to answer them.
        val multicast = multicastLock()

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
            multicast?.let { if (it.isHeld) it.release() }
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
        const val TAG = "FlashDropDiscovery"
        const val SERVICE_TYPE = "_flashdrop._tcp"
        const val PROTOCOL_MAJOR = 1
        const val TXT_VERSION = "v"
        const val TXT_ID = "id"
        const val TXT_NAME = "n"
        const val TXT_TRANSPORTS = "t"
        const val MODERN_RESOLVE_TIMEOUT_MS = 3_000L
        const val LEGACY_RESOLVE_TIMEOUT_MS = 2_000L
        const val BETTER_ADDRESS_WAIT_MS = 800L
        const val REQUERY_FAST_MS = 3_000L
        const val REQUERY_EMPTY_MS = 6_000L
        const val REQUERY_FOUND_MS = 20_000L
        val DIRECT = Executor { it.run() }
        val WIFI_DIRECT_SUBNET = byteArrayOf(192.toByte(), 168.toByte(), 49, 0)
    }
}
