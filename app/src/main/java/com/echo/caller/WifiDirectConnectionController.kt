package com.echo.caller

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat

data class WifiLinkState(
    val connected: Boolean = false,
    val owner: Boolean = false,
    val ownerAddress: String? = null,
    val peerName: String? = null,
    val connecting: Boolean = false,
    val message: String = "Ready."
)

/** App-process Wi-Fi Direct controller. Not owned by a Compose screen. */
class WifiDirectConnectionController(context: Context) {
    private val app = context.applicationContext
    private val p2p = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = p2p?.initialize(app, app.mainLooper, null)

    var state by mutableStateOf(WifiLinkState()); private set
    var peers by mutableStateOf<List<DiscoveredPeer>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set

    private fun permitted(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
                else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) {
                        discovering = false
                        peers = emptyList()
                        state = WifiLinkState(message = "Wi-Fi Direct is disabled.")
                    } else refresh()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> loadPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refresh()
            }
        }
    }

    init {
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        try { ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_EXPORTED) }
        catch (_: Exception) { state = state.copy(message = "Could not monitor Wi-Fi Direct.") }
        refresh()
    }

    fun discover() {
        if (p2p == null || channel == null || !permitted()) {
            state = state.copy(message = "Wi-Fi Direct service or Nearby devices permission unavailable.")
            return
        }
        try {
            p2p.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { discovering = true; state = state.copy(message = "Discovery running."); loadPeers() }
                override fun onFailure(reason: Int) { discovering = false; state = state.copy(message = "Discovery failed (code $reason).") }
            })
        } catch (_: SecurityException) { state = state.copy(message = "Nearby devices permission required.") }
    }

    fun stopDiscovery() {
        if (p2p == null || channel == null || !permitted()) { discovering = false; return }
        try {
            p2p.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { discovering = false; state = state.copy(message = if (state.connected) "Connected; discovery stopped." else "Discovery stopped.") }
                override fun onFailure(reason: Int) { discovering = false; state = state.copy(message = "Stop discovery returned code $reason.") }
            })
        } catch (_: SecurityException) { discovering = false }
    }

    private fun loadPeers() {
        if (p2p == null || channel == null || !permitted()) return
        try { p2p.requestPeers(channel) { peers = WifiDirectPeerMapper.map(it.deviceList) } }
        catch (_: SecurityException) { state = state.copy(message = "Permission required to read peers.") }
    }

    fun connect(peer: DiscoveredPeer) {
        if (p2p == null || channel == null || !permitted()) {
            state = state.copy(message = "Wi-Fi Direct service or permission unavailable."); return
        }
        if (peer.transportAddress.isBlank() || peer.transportAddress == "Address unavailable") {
            state = state.copy(message = "Peer address unavailable; run discovery again."); return
        }
        state = state.copy(connecting = true, message = "Requesting connection to ${peer.displayName}…")
        try {
            // Stop discovery before connecting to avoid Android returning BUSY.
            p2p.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = requestConnect(peer)
                override fun onFailure(reason: Int) = requestConnect(peer)
            })
        } catch (_: SecurityException) { state = state.copy(connecting = false, message = "Permission required.") }
    }

    private fun requestConnect(peer: DiscoveredPeer) {
        if (p2p == null || channel == null) return
        try {
            p2p.connect(channel, WifiP2pConfig().apply { deviceAddress = peer.transportAddress },
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { state = state.copy(connecting = true, message = "Request accepted; waiting for link."); refresh() }
                    override fun onFailure(reason: Int) { state = state.copy(connecting = false, message = "Connection failed (code $reason).") }
                })
        } catch (_: SecurityException) { state = state.copy(connecting = false, message = "Permission required.") }
    }

    fun refresh() {
        if (p2p == null || channel == null || !permitted()) return
        try {
            p2p.requestConnectionInfo(channel) { info ->
                if (info.groupFormed) {
                    state = state.copy(connected = true, owner = info.isGroupOwner,
                        ownerAddress = info.groupOwnerAddress?.hostAddress, connecting = false,
                        message = "Wi-Fi Direct link active.")
                    loadGroupName()
                } else {
                    val was = state.connected
                    state = WifiLinkState(message = when {
                        was -> "Wi-Fi Direct connection lost."
                        state.connecting -> "Connection pending; waiting for peer."
                        else -> "Not connected."
                    })
                }
            }
        } catch (_: SecurityException) { state = state.copy(message = "Permission required to read link state.") }
    }

    private fun loadGroupName() {
        if (p2p == null || channel == null || !permitted()) return
        try {
            p2p.requestGroupInfo(channel) { g: WifiP2pGroup? ->
                if (g != null) {
                    val name = if (g.isGroupOwner) g.clientList.firstOrNull()?.deviceName else g.owner?.deviceName
                    state = state.copy(peerName = name?.takeIf { it.isNotBlank() })
                }
            }
        } catch (_: SecurityException) { }
    }

    fun disconnect() {
        if (p2p == null || channel == null || !permitted()) { state = state.copy(message = "Permission required."); return }
        try {
            p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { discovering = false; state = WifiLinkState(message = "Disconnected.") }
                override fun onFailure(reason: Int) { refresh(); if (state.connected) state = state.copy(message = "Disconnect failed (code $reason).") }
            })
        } catch (_: SecurityException) { state = state.copy(message = "Permission required.") }
    }
}

object WifiDirectControllerHolder {
    @Volatile private var value: WifiDirectConnectionController? = null
    fun get(context: Context): WifiDirectConnectionController = value ?: synchronized(this) {
        value ?: WifiDirectConnectionController(context).also { value = it }
    }
    fun peek(): WifiDirectConnectionController? = value
}
