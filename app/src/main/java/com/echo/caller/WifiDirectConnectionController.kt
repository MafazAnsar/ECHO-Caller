package com.echo.caller

import android.annotation.SuppressLint
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WifiLinkState(
    val connected: Boolean = false,
    val owner: Boolean = false,
    val ownerAddress: String? = null,
    val peerName: String? = null,
    val connecting: Boolean = false,
    val message: String = "Ready."
)

// Every Wi-Fi Direct operation below is gated by permitted() and catches SecurityException.
// Lint cannot follow the permission check through this helper, so suppress its false positives here.
@SuppressLint("MissingPermission")
class WifiDirectConnectionController(context: Context) {
    private val app = context.applicationContext
    private val p2p = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = p2p?.initialize(app, app.mainLooper, null)

    var state by mutableStateOf(WifiLinkState()); private set
    var peers by mutableStateOf<List<DiscoveredPeer>>(emptyList()); private set
    var discovering by mutableStateOf(false); private set
    val diagnostics = mutableStateListOf<String>()

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        diagnostics.add("$stamp  $message")
        while (diagnostics.size > 35) diagnostics.removeAt(0)
    }

    private fun permitted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun reason(reason: Int) = when (reason) {
        WifiP2pManager.ERROR -> "ERROR (0)"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED (1)"
        WifiP2pManager.BUSY -> "BUSY (2)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS (3)"
        else -> "UNKNOWN ($reason)"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    log("Broadcast: Wi-Fi Direct ${if (enabled) "enabled" else "disabled"}.")
                    if (!enabled) {
                        discovering = false
                        peers = emptyList()
                        state = WifiLinkState(message = "Wi-Fi Direct disabled.")
                    } else refresh()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    log("Broadcast: peer list changed.")
                    loadPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    log("Broadcast: connection state changed on this phone.")
                    refresh()
                }
            }
        }
    }

    init {
        log("Controller started; P2P service ${if (p2p == null) "missing" else "available"}.")
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        try {
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            log("Receiver registered.")
        } catch (e: Exception) {
            state = state.copy(message = "Could not monitor Wi-Fi Direct.")
            log("Receiver registration exception: ${e.javaClass.simpleName}: ${e.message}")
        }
        refresh()
    }

    fun discover() {
        if (p2p == null || channel == null || !permitted()) {
            log("Discovery blocked: service/channel/permission unavailable.")
            state = state.copy(message = "Wi-Fi Direct service or permission unavailable.")
            return
        }
        log("Calling discoverPeers().")
        try {
            p2p.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discovering = true
                    state = state.copy(message = "Discovery running.")
                    log("discoverPeers success.")
                    loadPeers()
                }
                override fun onFailure(code: Int) {
                    discovering = false
                    state = state.copy(message = "Discovery failed: ${reason(code)}.")
                    log("discoverPeers failure: ${reason(code)}.")
                }
            })
        } catch (e: Exception) {
            log("discoverPeers exception: ${e.javaClass.simpleName}: ${e.message}")
            state = state.copy(message = "Discovery error.")
        }
    }

    fun stopDiscovery() {
        if (p2p == null || channel == null || !permitted()) {
            discovering = false
            log("stopPeerDiscovery skipped: unavailable service/channel/permission.")
            return
        }
        log("Calling stopPeerDiscovery().")
        try {
            p2p.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discovering = false
                    log("stopPeerDiscovery success.")
                    state = state.copy(message = if (state.connected) "Connected; discovery stopped." else "Discovery stopped.")
                }
                override fun onFailure(code: Int) {
                    discovering = false
                    log("stopPeerDiscovery failure: ${reason(code)}; proceeding to connect.")
                }
            })
        } catch (e: Exception) {
            discovering = false
            log("stopPeerDiscovery exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun loadPeers() {
        if (p2p == null || channel == null || !permitted()) return
        try {
            p2p.requestPeers(channel) { list ->
                peers = WifiDirectPeerMapper.map(list.deviceList)
                log("requestPeers returned ${peers.size} peer(s).")
                peers.forEach { log("Peer: ${it.displayName}; ${it.availability}; ${it.transportAddress}.") }
            }
        } catch (e: Exception) {
            log("requestPeers exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun connect(peer: DiscoveredPeer) {
        if (p2p == null || channel == null || !permitted()) {
            log("Connect aborted: service/channel/permission unavailable.")
            state = state.copy(message = "Wi-Fi Direct service or permission unavailable.")
            return
        }
        if (peer.transportAddress.isBlank() || peer.transportAddress == "Address unavailable") {
            log("Connect aborted: invalid peer address.")
            state = state.copy(message = "Peer address unavailable; run discovery again.")
            return
        }
        state = state.copy(connecting = true, message = "Requesting connection to ${peer.displayName}…")
        log("Connect tapped: ${peer.displayName}; address=${peer.transportAddress}; peerStatus=${peer.availability}.")
        try {
            p2p.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discovering = false
                    log("Pre-connect stopPeerDiscovery success.")
                    requestConnect(peer)
                }
                override fun onFailure(code: Int) {
                    discovering = false
                    log("Pre-connect stopPeerDiscovery failure ${reason(code)}; trying connect anyway.")
                    requestConnect(peer)
                }
            })
        } catch (e: Exception) {
            state = state.copy(connecting = false, message = "Connection preparation error.")
            log("Pre-connect exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun requestConnect(peer: DiscoveredPeer) {
        val manager = p2p ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.transportAddress
            wps.setup = WpsInfo.PBC
        }
        log("Calling connect() with address=${config.deviceAddress}.")
        try {
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    state = state.copy(connecting = true, message = "Android accepted request; waiting for peer.")
                    log("connect callback: SUCCESS (request accepted, not yet connected).")
                    refresh()
                }
                override fun onFailure(code: Int) {
                    state = state.copy(connecting = false, message = "Connection failed: ${reason(code)}.")
                    log("connect callback: FAILURE ${reason(code)}.")
                    refresh()
                }
            })
        } catch (e: Exception) {
            state = state.copy(connecting = false, message = "Connection request exception.")
            log("connect exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun refresh() {
        if (p2p == null || channel == null || !permitted()) return
        try {
            p2p.requestConnectionInfo(channel) { info ->
                if (info.groupFormed) {
                    state = state.copy(
                        connected = true,
                        owner = info.isGroupOwner,
                        ownerAddress = info.groupOwnerAddress?.hostAddress,
                        connecting = false,
                        message = "Wi-Fi Direct link active."
                    )
                    log("Connection info: group formed; role=${if (info.isGroupOwner) "owner" else "client"}; ownerIP=${info.groupOwnerAddress?.hostAddress}.")
                    loadGroupName()
                } else {
                    if (state.connected) log("Connection info: group no longer formed.")
                    state = WifiLinkState(message = if (state.connecting) "Connection pending; waiting for peer." else "Not connected.")
                }
            }
        } catch (e: Exception) {
            log("requestConnectionInfo exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun loadGroupName() {
        if (p2p == null || channel == null || !permitted()) return
        try {
            p2p.requestGroupInfo(channel) { group ->
                if (group != null) {
                    val name = if (group.isGroupOwner) group.clientList.firstOrNull()?.deviceName
                    else group.owner?.deviceName
                    state = state.copy(peerName = name?.takeIf { it.isNotBlank() })
                    log("Group info: peer=${name ?: "name unavailable"}.")
                } else log("Group info callback: no group.")
            }
        } catch (e: Exception) {
            log("requestGroupInfo exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun disconnect() {
        if (p2p == null || channel == null || !permitted()) {
            state = state.copy(message = "Permission required.")
            log("Disconnect blocked: service/channel/permission unavailable.")
            return
        }
        log("Calling removeGroup().")
        try {
            p2p.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    discovering = false
                    state = WifiLinkState(message = "Disconnected.")
                    log("removeGroup success.")
                }
                override fun onFailure(code: Int) {
                    log("removeGroup failure: ${reason(code)}.")
                    refresh()
                }
            })
        } catch (e: Exception) {
            log("removeGroup exception: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}

object WifiDirectControllerHolder {
    @Volatile private var value: WifiDirectConnectionController? = null
    fun get(context: Context): WifiDirectConnectionController = value ?: synchronized(this) {
        value ?: WifiDirectConnectionController(context).also { value = it }
    }
    fun peek(): WifiDirectConnectionController? = value
}
