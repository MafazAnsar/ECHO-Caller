package com.echo.caller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.net.ServerSocket
import java.net.Socket
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
    private val resolving = mutableSetOf<String>()

    val peers = mutableStateListOf<LanPeer>()
    val running = mutableStateOf(false)
    val status = mutableStateOf("LAN discovery is stopped.")
    val events = mutableStateListOf<String>()

    @Synchronized
    private fun log(message: String) {
        events.add(message)
        while (events.size > 12) events.removeAt(0)
    }

    @Synchronized
    fun start() {
        if (running.value) return
        try {
            val socket = ServerSocket(0)
            server = socket
            thread(name = "ECHO-LAN-listener", isDaemon = true) {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { client: Socket ->
                            log("LAN probe received from ${client.inetAddress.hostAddress}.")
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
                setAttribute("echo-id", identity.fingerprint.take(64))
                setAttribute("protocol", "1")
            }
            val registerListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    log("Advertising ${info.serviceName} on local network.")
                    status.value = "Advertising and searching for ECHO devices."
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    log("Service registration failed ($errorCode).")
                    status.value = "Could not advertise this device ($errorCode)."
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    log("LAN service unregistered.")
                }
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    log("Service unregistration failed ($errorCode).")
                }
            }
            registration = registerListener
            nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registerListener)

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) {
                    running.value = true
                    status.value = "Searching this Wi-Fi network for ECHO devices."
                    log("LAN discovery started.")
                }
                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceType.trimEnd('.') == serviceType.trimEnd('.')) {
                        log("Found service: ${info.serviceName}. Resolving…")
                        resolve(info)
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) {
                    peers.removeAll { it.name == info.serviceName }
                    log("Service lost: ${info.serviceName}.")
                }
                override fun onDiscoveryStopped(type: String) {
                    running.value = false
                    log("LAN discovery stopped.")
                }
                override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                    running.value = false
                    status.value = "LAN discovery failed ($errorCode)."
                    log("Discovery start failed ($errorCode).")
                }
                override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                    log("Discovery stop failed ($errorCode).")
                }
            }
            discovery = discoveryListener
            nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            running.value = false
            status.value = "LAN discovery could not start: ${e.javaClass.simpleName}."
            log("Start exception: ${e.javaClass.simpleName}: ${e.message}")
            stop()
        }
    }

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
                if (host.isNotBlank() && info.port > 0 && fingerprint != identity.fingerprint) {
                    val peer = LanPeer(info.serviceName, host, info.port, fingerprint)
                    val index = peers.indexOfFirst { it.name == peer.name }
                    if (index >= 0) peers[index] = peer else peers.add(peer)
                    log("Resolved ${info.serviceName} at $host:${info.port}; identity not verified.")
                } else {
                    log("Ignored own service or incomplete service record: ${info.serviceName}.")
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

    @Synchronized
    fun stop() {
        try {
            discovery?.let { nsd.stopServiceDiscovery(it) }
        } catch (_: Exception) { }
        try {
            registration?.let { nsd.unregisterService(it) }
        } catch (_: Exception) { }
        discovery = null
        registration = null
        server?.let { runCatching { it.close() } }
        server = null
        peers.clear()
        resolving.clear()
        running.value = false
        status.value = "LAN discovery is stopped."
        log("LAN discovery stopped by user.")
    }
}
