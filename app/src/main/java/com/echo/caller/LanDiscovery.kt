package com.echo.caller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class LanPeer(
    val name: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
    val verified: Boolean = false
)

/**
 * Advertises this ECHO instance and discovers other ECHO instances on the
 * same local network. Discovery is only a locator; it does not authenticate
 * peers or enable voice calls.
 */
class LanDiscoveryController(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val identity = DeviceIdentityManager(app).getOrCreate()
    private val serviceType = "_echo._tcp."

    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var starting = false
    private var stopRequested = false
    private var serviceRegistered = false

    private val resolving = mutableSetOf<String>()
    private val peerServiceNames = mutableMapOf<String, String>()
    private val pendingPeerRemovals = mutableMapOf<String, ScheduledFuture<*>>()
    private val removalScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ECHO-LAN-peer-expiry").apply { isDaemon = true }
    }
    private val peerLossGracePeriodSeconds = 20L

    val peers = mutableStateListOf<LanPeer>()
    val running = mutableStateOf(false)
    val status = mutableStateOf("LAN discovery is stopped.")
    val events = mutableStateListOf<String>()
    val connectionTest = mutableStateOf("No connection test yet.")
    val connectingPeer = mutableStateOf<String?>(null)

    @Synchronized
    private fun log(message: String) {
        events.add(message)
        while (events.size > 12) events.removeAt(0)
    }

    @Synchronized
    fun start() {
        if (starting || running.value) {
            log("LAN discovery is already starting or running.")
            return
        }

        starting = true
        stopRequested = false
        serviceRegistered = false
        peers.clear()
        peerServiceNames.clear()
        resolving.clear()

        try {
            val socket = ServerSocket(0)
            server = socket
            thread(name = "ECHO-LAN-listener", isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client: Socket ->
                            client.soTimeout = 5000
                            val authenticatedPeer = EchoSecureHandshake.server(
                                client, DeviceIdentityManager(app), TrustedDeviceStore(app), EchoMessageStore(app)
                            )
                            log("Authenticated encrypted session with $authenticatedPeer.")
                        }
                    } catch (_: Exception) {
                        if (!socket.isClosed) log("LAN listener stopped unexpectedly.")
                    }
                }
            }

            val service = NsdServiceInfo().apply {
                serviceName = "ECHO-${Build.MODEL.take(24)}"
                serviceType = this@LanDiscoveryController.serviceType
                port = socket.localPort
                setAttribute("echo-id", identity.fingerprint)
                setAttribute("protocol", "1")
            }

            val registerListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    val shouldUnregister = synchronized(this@LanDiscoveryController) {
                        serviceRegistered = true
                        starting = false
                        if (stopRequested) true else {
                            status.value = "Advertising and searching for ECHO devices."
                            log("Advertising ${info.serviceName} on local network.")
                            false
                        }
                    }
                    if (shouldUnregister) {
                        val pending = synchronized(this@LanDiscoveryController) {
                            this@LanDiscoveryController.registration.also {
                                this@LanDiscoveryController.registration = null
                            }
                        }
                        unregisterRegistration(pending)
                    }
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(this@LanDiscoveryController) {
                        serviceRegistered = false
                        starting = false
                        log("Service registration failed ($errorCode).")
                        if (!stopRequested) status.value = "Could not advertise this device ($errorCode)."
                    }
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    synchronized(this@LanDiscoveryController) {
                        serviceRegistered = false
                        log("LAN service unregistered.")
                    }
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    log("Service unregistration failed ($errorCode).")
                }
            }
            registration = registerListener
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registerListener)

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    synchronized(this@LanDiscoveryController) {
                        if (stopRequested) {
                            runCatching { nsd.stopServiceDiscovery(this) }
                            return
                        }
                        running.value = true
                        starting = false
                        status.value = "Searching this Wi-Fi network for ECHO devices."
                        log("LAN discovery started.")
                    }
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceType.trimEnd('.') == serviceType.trimEnd('.')) {
                        synchronized(this@LanDiscoveryController) {
                            pendingPeerRemovals.remove(info.serviceName)?.cancel(false)
                        }
                        log("Found service: ${info.serviceName}. Resolving…")
                        resolve(info)
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(this@LanDiscoveryController) {
                        pendingPeerRemovals.remove(info.serviceName)?.cancel(false)
                        val expiry = removalScheduler.schedule({
                            synchronized(this@LanDiscoveryController) {
                                val key = peerServiceNames.entries.firstOrNull { it.value == info.serviceName }?.key
                                if (key != null) {
                                    val peer = peers.firstOrNull { peerKey(it) == key }
                                    if (peer?.name == info.serviceName) {
                                        peers.remove(peer)
                                        log("Removed ${info.serviceName} after ${peerLossGracePeriodSeconds}s without rediscovery.")
                                    }
                                    peerServiceNames.remove(key)
                                }
                                pendingPeerRemovals.remove(info.serviceName)
                            }
                        }, peerLossGracePeriodSeconds, TimeUnit.SECONDS)
                        pendingPeerRemovals[info.serviceName] = expiry
                        log("Service temporarily lost: ${info.serviceName}; keeping it visible for ${peerLossGracePeriodSeconds}s.")
                    }
                }

                override fun onDiscoveryStopped(type: String) {
                    synchronized(this@LanDiscoveryController) {
                        running.value = false
                        log("LAN discovery stopped.")
                    }
                }

                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    synchronized(this@LanDiscoveryController) {
                        running.value = false
                        starting = false
                        status.value = "LAN discovery failed ($errorCode)."
                        log("Discovery start failed ($errorCode).")
                    }
                    runCatching { nsd.stopServiceDiscovery(this) }
                }

                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                    log("Discovery stop failed ($errorCode).")
                }
            }
            discovery = discoveryListener
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            synchronized(this) {
                starting = false
                status.value = "LAN discovery could not start: ${e.javaClass.simpleName}."
                log("Start exception: ${e.javaClass.simpleName}: ${e.message}")
            }
            stop()
        }
    }

    fun testConnection(peer: LanPeer) {
        if (connectingPeer.value != null) return
        connectingPeer.value = peer.name
        connectionTest.value = "Authenticating and establishing encryption with ${peer.name}…"
        thread(name = "ECHO-LAN-secure-test", isDaemon = true) {
            try {
                val trusted = TrustedDeviceStore(app).all().firstOrNull { it.fingerprint == peer.fingerprint }
                    ?: throw SecurityException("This device is not QR-paired. Pair both phones first.")
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 5000)
                    socket.soTimeout = 8000
                    val remoteName = EchoSecureHandshake.client(socket, DeviceIdentityManager(app), trusted)
                    connectionTest.value = "Secure success: authenticated $remoteName and exchanged an encrypted message."
                    log("Mutual authentication and encrypted message exchange succeeded with ${peer.name}.")
                }
            } catch (e: Exception) {
                connectionTest.value = "Secure connection failed: ${e.message ?: e.javaClass.simpleName}"
                log("Secure LAN test failed for ${peer.name}: ${e.javaClass.simpleName}.")
            } finally {
                connectingPeer.value = null
            }
        }
    }

    fun sendMessage(peer: LanPeer, body: String) {
        val text = body.trim()
        if (text.isEmpty()) {
            connectionTest.value = "Enter a message first."
            return
        }
        if (text.length > EchoMessageStore.MAX_MESSAGE_CHARS) {
            connectionTest.value = "Message is too long (maximum 4,000 characters)."
            return
        }
        if (connectingPeer.value != null) return
        connectingPeer.value = peer.name
        connectionTest.value = "Sending encrypted message to ${peer.name}…"
        thread(name = "ECHO-LAN-send-message", isDaemon = true) {
            try {
                val trusted = TrustedDeviceStore(app).all().firstOrNull { it.fingerprint == peer.fingerprint }
                    ?: throw SecurityException("This device is not QR-paired. Pair both phones first.")
                val store = EchoMessageStore(app)
                val id = store.newMessageId()
                Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 5000)
                    socket.soTimeout = 8000
                    val remoteName = EchoSecureHandshake.clientSend(socket, DeviceIdentityManager(app), trusted, id, text)
                    store.add(EchoMessage(id, trusted.fingerprint, EchoMessageDirection.SENT, text,
                        System.currentTimeMillis(), "DELIVERED"))
                    connectionTest.value = "Delivered securely to $remoteName."
                    log("Encrypted message delivered to $remoteName.")
                }
            } catch (e: Exception) {
                connectionTest.value = "Message failed: ${e.message ?: e.javaClass.simpleName}"
                log("Encrypted message failed for ${peer.name}: ${e.javaClass.simpleName}.")
            } finally {
                connectingPeer.value = null
            }
        }
    }

    private fun peerKey(peer: LanPeer): String =
        if (peer.fingerprint.isNotBlank()) "id:${peer.fingerprint}" else "host:${peer.host}"

    @Suppress("DEPRECATION")
    private fun resolve(service: NsdServiceInfo) {
        synchronized(resolving) {
            if (!resolving.add(service.serviceName)) return
        }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(resolving) { resolving.remove(service.serviceName) }
                log("Could not resolve ${service.serviceName} ($errorCode).")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                synchronized(resolving) { resolving.remove(service.serviceName) }
                val host = info.host?.hostAddress.orEmpty()
                val fingerprint = info.attributes["echo-id"]?.toString(Charsets.UTF_8).orEmpty()

                if (fingerprint == identity.fingerprint) {
                    log("Ignored this phone's own LAN advertisement.")
                    return
                }

                if (host.isBlank() || info.port <= 0) {
                    log("Ignored incomplete service record: ${info.serviceName}.")
                    return
                }

                synchronized(this@LanDiscoveryController) {
                    if (stopRequested) return
                    val peer = LanPeer(info.serviceName, host, info.port, fingerprint)
                    val key = peerKey(peer)
                    val old = peers.firstOrNull { peerKey(it) == key }
                    if (old == null) {
                        peers.add(peer)
                        log("Resolved ${info.serviceName} at $host:${info.port}; identity not verified.")
                    } else if (old.host != peer.host || old.port != peer.port || old.name != peer.name) {
                        val index = peers.indexOf(old)
                        if (index >= 0) peers[index] = peer
                        log("Updated LAN endpoint for ${info.serviceName} at $host:${info.port}.")
                    }
                    peerServiceNames[key] = info.serviceName
                }
            }
        }

        try {
            nsd.resolveService(service, listener)
        } catch (e: Exception) {
            synchronized(resolving) { resolving.remove(service.serviceName) }
            log("Resolve exception: ${e.javaClass.simpleName}.")
        }
    }

    private fun unregisterRegistration(listener: NsdManager.RegistrationListener?) {
        if (listener == null) return
        runCatching { nsd.unregisterService(listener) }
            .onFailure { log("Unregister request failed: ${it.javaClass.simpleName}.") }
    }

    @Synchronized
    fun stop() {
        stopRequested = true
        starting = false

        discovery?.let { listener ->
            runCatching { nsd.stopServiceDiscovery(listener) }
                .onFailure { log("Stop discovery request failed: ${it.javaClass.simpleName}.") }
        }
        discovery = null

        if (serviceRegistered) {
            unregisterRegistration(registration)
            registration = null
            serviceRegistered = false
        }
        // If registration is still pending, keep its listener reference.
        // onServiceRegistered() will unregister it when Android confirms it.

        server?.let { runCatching { it.close() } }
        server = null
        peers.clear()
        peerServiceNames.clear()
        pendingPeerRemovals.values.forEach { it.cancel(false) }
        pendingPeerRemovals.clear()
        synchronized(resolving) { resolving.clear() }
        running.value = false
        status.value = "LAN discovery is stopped."
        log("LAN discovery stopped by user.")
    }
}
