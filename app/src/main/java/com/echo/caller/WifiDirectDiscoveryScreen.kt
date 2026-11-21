package com.echo.caller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private data class P2pLinkState(
    val connected: Boolean = false,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null
)

@Composable
fun WifiDirectDiscoveryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val manager = remember {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    val channel = remember(manager) {
        manager?.initialize(context, context.mainLooper, null)
    }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var status by remember { mutableStateOf("Ready to search for nearby Wi-Fi Direct devices.") }
    val transportManager = remember { TransportManager() }
    var peers by remember { mutableStateOf<List<DiscoveredPeer>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(P2pLinkState()) }
    var connectingTo by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        status = if (granted) "Permission granted. Tap Start discovery." else
            "Nearby-device permission was not granted. ECHO cannot use Wi-Fi Direct without it."
    }

    fun refreshConnectionInfo() {
        if (manager == null || channel == null) return
        try {
            manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
                if (info.groupFormed) {
                    link = P2pLinkState(
                        connected = true,
                        isGroupOwner = info.isGroupOwner,
                        groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                    )
                    connectingTo = null
                    status = if (info.isGroupOwner) {
                        "Direct Wi-Fi connection established. This phone is the group owner."
                    } else {
                        "Direct Wi-Fi connection established. Connected to the group owner."
                    }
                } else {
                    link = P2pLinkState()
                    connectingTo = null
                    status = "No active Wi-Fi Direct connection."
                }
            }
        } catch (_: SecurityException) {
            status = "Nearby-device permission is required to read connection status."
        }
    }

    DisposableEffect(context, manager, channel, permissionGranted) {
        if (manager == null || channel == null || !permissionGranted) {
            onDispose { }
        } else {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiveContext: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            val enabled = intent.getIntExtra(
                                WifiP2pManager.EXTRA_WIFI_STATE,
                                WifiP2pManager.WIFI_P2P_STATE_DISABLED
                            ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            if (!enabled) {
                                discovering = false
                                connectingTo = null
                                link = P2pLinkState()
                                peers = transportManager.clear()
                                status = "Wi-Fi Direct is disabled by the phone. Turn on Wi-Fi and try again."
                            }
                        }
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            try {
                                manager.requestPeers(channel) { list ->
                                    peers = transportManager.replacePeers(
                                        TransportType.WIFI_DIRECT,
                                        WifiDirectPeerMapper.map(list.deviceList)
                                    )
                                    if (!link.connected && connectingTo == null) {
                                        status = if (peers.isEmpty()) {
                                            "Search is running. No nearby Wi-Fi Direct devices have been reported yet."
                                        } else {
                                            "Found ${peers.size} nearby Wi-Fi Direct device(s)."
                                        }
                                    }
                                }
                            } catch (_: SecurityException) {
                                status = "Permission is needed to read nearby Wi-Fi Direct devices."
                            }
                        }
                        WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshConnectionInfo()
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
    }

    val wifiManager = remember { appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager }
    val locationManager = remember { appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
    val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
    val locationEnabled = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager?.isLocationEnabled == true else true
    }.getOrDefault(false)
    val supported = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) }

    fun startConnection(peer: DiscoveredPeer) {
        if (!wifiEnabled) {
            status = "Turn on Wi-Fi in Android settings before connecting."
            return
        }
        if (!permissionGranted) {
            permissionLauncher.launch(permission)
            return
        }
        if (manager == null || channel == null) {
            status = "Android Wi-Fi Direct service could not be initialised."
            return
        }
        if (peer.transportAddress.isBlank() || peer.transportAddress == "Address unavailable") {
            status = "Android did not provide an address for this device. Refresh discovery and try again."
            return
        }
        connectingTo = peer.peerId
        status = "Requesting a direct connection to ${peer.displayName}…"
        try {
            val config = WifiP2pConfig().apply { deviceAddress = peer.transportAddress }
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // This confirms only that Android accepted the request, not that a link exists.
                    status = "Connection request accepted. Waiting for both phones to establish the link…"
                }
                override fun onFailure(reason: Int) {
                    connectingTo = null
                    status = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct connection is not supported on this phone."
                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Stop discovery or wait briefly, then retry."
                        WifiP2pManager.ERROR -> "Android reported a connection error. Please retry."
                        else -> "Connection request failed (code $reason)."
                    }
                }
            })
        } catch (_: SecurityException) {
            connectingTo = null
            status = "Nearby-device permission is required to connect."
        } catch (_: Exception) {
            connectingTo = null
            status = "Could not start the Wi-Fi Direct connection. Please retry."
        }
    }

    fun disconnect() {
        if (manager == null || channel == null) return
        status = "Disconnecting Wi-Fi Direct…"
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    link = P2pLinkState()
                    connectingTo = null
                    status = "Wi-Fi Direct group disconnected."
                }
                override fun onFailure(reason: Int) {
                    refreshConnectionInfo()
                    if (link.connected) status = "Disconnect request failed (code $reason). The connection may still be active."
                    else status = "No active Wi-Fi Direct group."
                }
            })
        } catch (_: SecurityException) {
            status = "Nearby-device permission is required to disconnect."
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nearby discovery & connection", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Text("Discover nearby Wi-Fi Direct devices, then request a direct local connection. Identity authentication is not part of this step.", color = Color(0xFF6D7D87), fontSize = 13.sp)

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF142B3A)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("CONNECTION STATUS", color = Color(0xFFB8C8D0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                if (link.connected) {
                    Text(
                        "Connected • ${if (link.isGroupOwner) "Group owner" else "Client"}",
                        color = Color(0xFF8DE0C9), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                    link.groupOwnerAddress?.let {
                        Text("Group owner address: $it", color = Color(0xFFB8C8D0), fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                } else if (connectingTo != null) {
                    Text("Connection pending", color = Color(0xFFFFD18A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        if (!supported) InfoCard("Wi-Fi Direct is not reported as supported on this phone.")
        else if (!wifiEnabled) InfoCard("Turn on Wi-Fi in Android settings, then return here. ECHO will not change the setting for you.")
        else if (!locationEnabled) InfoCard("Android Location must be switched on for Wi-Fi Direct peer discovery on this device.")
        else if (!permissionGranted) InfoCard("ECHO needs Nearby devices permission to search and connect using Wi-Fi Direct.")

        Button(
            onClick = {
                when {
                    !supported -> status = "This phone does not report Wi-Fi Direct support."
                    !wifiEnabled -> status = "Turn on Wi-Fi in Android settings, then try again."
                    !locationEnabled -> status = "Turn on Android Location, then try again."
                    !permissionGranted -> permissionLauncher.launch(permission)
                    manager == null || channel == null -> status = "Android Wi-Fi Direct service could not be initialised."
                    else -> {
                        peers = transportManager.clear()
                        discovering = true
                        status = "Starting Wi-Fi Direct peer search…"
                        try {
                            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    status = "Search request accepted. Waiting for nearby devices…"
                                    try {
                                        manager.requestPeers(channel) { list ->
                                            peers = transportManager.replacePeers(TransportType.WIFI_DIRECT, WifiDirectPeerMapper.map(list.deviceList))
                                            if (peers.isNotEmpty()) status = "Found ${peers.size} nearby Wi-Fi Direct device(s)."
                                        }
                                    } catch (_: SecurityException) {
                                        status = "Permission is needed to read nearby devices."
                                    }
                                }
                                override fun onFailure(reason: Int) {
                                    discovering = false
                                    status = when (reason) {
                                        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported by this device."
                                        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Wait a moment and try again."
                                        WifiP2pManager.ERROR -> "Android reported a Wi-Fi Direct error. Try again."
                                        else -> "Discovery could not start (code $reason)."
                                    }
                                }
                            })
                        } catch (_: SecurityException) {
                            discovering = false
                            status = "Nearby-device permission is required. Please grant it and try again."
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), enabled = !discovering && !link.connected
        ) { Text(if (!permissionGranted) "Allow and start discovery" else "Start discovery") }

        if (discovering) {
            OutlinedButton(
                onClick = {
                    if (manager != null && channel != null) try {
                        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { discovering = false; status = "Discovery stopped." }
                            override fun onFailure(reason: Int) { discovering = false; status = "Stop request completed with code $reason." }
                        })
                    } catch (_: SecurityException) { discovering = false; status = "Permission is required to stop discovery." }
                }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)
            ) { Text("Stop discovery") }
        }

        if (link.connected) {
            OutlinedButton(onClick = { disconnect() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("Disconnect")
            }
        }

        Text("NEARBY WI-FI DIRECT DEVICES", color = Color(0xFF6D7D87), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) {
            InfoCard("No devices listed yet. Install ECHO on the second phone, enable Wi-Fi, grant its Nearby devices permission, and start discovery there too.")
        } else {
            peers.forEach { peer ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Text(peer.displayName, color = Color(0xFF142B3A), fontWeight = FontWeight.SemiBold)
                        Text("${peer.availability}  •  ${peer.transportAddress}", color = Color(0xFF6D7D87), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                        Text("Transport: ${peer.transport.label}  •  Identity: ${peer.identityStatus.label}", color = Color(0xFF6D7D87), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        Button(
                            onClick = { startConnection(peer) },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            enabled = !link.connected && connectingTo == null && peer.availability != "Unavailable" && peer.transportAddress != "Address unavailable"
                        ) { Text(if (connectingTo == peer.peerId) "Requesting connection…" else "Connect") }
                    }
                }
            }
        }

        Surface(color = Color(0xFFE4F5F1), shape = RoundedCornerShape(13.dp)) {
            Text(
                "Phase 1.5A only establishes the Android Wi-Fi Direct link. A successful link does not prove the other phone is a trusted ECHO device. Identity authentication and encrypted application messaging are later steps. No calls or mesh relaying are enabled.",
                color = Color(0xFF142B3A), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(13.dp)
            )
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Text(message, color = Color(0xFF6D7D87), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(15.dp))
    }
}
