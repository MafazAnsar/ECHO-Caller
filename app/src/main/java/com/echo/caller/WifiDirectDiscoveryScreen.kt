package com.echo.caller

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

private data class NearbyWifiPeer(
    val name: String,
    val address: String,
    val status: String
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
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("Ready to search for nearby Wi-Fi Direct devices.") }
    var peers by remember { mutableStateOf<List<NearbyWifiPeer>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var p2pEnabled by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        status = if (granted) "Permission granted. Tap Start discovery." else
            "Nearby-device permission was not granted. ECHO cannot search without it."
    }

    DisposableEffect(context, manager, channel, permissionGranted) {
        if (manager == null || channel == null || !permissionGranted) {
            onDispose { }
        } else {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiveContext: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                            p2pEnabled = intent.getIntExtra(
                                WifiP2pManager.EXTRA_WIFI_STATE,
                                WifiP2pManager.WIFI_P2P_STATE_DISABLED
                            ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            if (!p2pEnabled) {
                                discovering = false
                                status = "Wi-Fi Direct is currently disabled by the phone."
                                peers = emptyList()
                            }
                        }
                        WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                            try {
                                manager.requestPeers(channel) { list ->
                                    peers = list.deviceList.map { device ->
                                        NearbyWifiPeer(
                                            name = device.deviceName.ifBlank { "Unnamed Wi-Fi Direct device" },
                                            address = device.deviceAddress,
                                            status = peerStatus(device.status)
                                        )
                                    }
                                    status = if (peers.isEmpty()) {
                                        "Search is running. No nearby Wi-Fi Direct devices have been reported yet."
                                    } else {
                                        "Found ${peers.size} nearby Wi-Fi Direct device(s)."
                                    }
                                }
                            } catch (_: SecurityException) {
                                status = "Permission is needed to read nearby Wi-Fi Direct devices."
                            }
                        }
                    }
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
    }

    val wifiManager = remember {
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    val locationManager = remember {
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }
    val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
    val locationEnabled = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locationManager?.isLocationEnabled == true
        else true
    }.getOrDefault(false)
    val supported = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Nearby discovery", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Text("Find nearby devices using Android Wi-Fi Direct. This step does not connect to them.", color = Color(0xFF6D7D87), fontSize = 13.sp)

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF142B3A)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("DISCOVERY STATUS", color = Color(0xFFB8C8D0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(status, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            }
        }

        if (!supported) {
            InfoCard("Wi-Fi Direct is not reported as supported on this phone.")
        } else if (!wifiEnabled) {
            InfoCard("Turn on Wi-Fi in Android settings, then return here. ECHO will not change the setting for you.")
        } else if (!locationEnabled) {
            InfoCard("Android Location must be switched on for Wi-Fi Direct peer discovery on this device.")
        } else if (!permissionGranted) {
            InfoCard("ECHO needs the Nearby devices permission to search. It is used for nearby Wi-Fi discovery, not to identify your location.")
        }

        Button(
            onClick = {
                when {
                    !supported -> status = "This phone does not report Wi-Fi Direct support."
                    !wifiEnabled -> status = "Turn on Wi-Fi in Android settings, then try again."
                    !locationEnabled -> status = "Turn on Android Location, then try again."
                    !permissionGranted -> permissionLauncher.launch(permission)
                    manager == null || channel == null -> status = "Android Wi-Fi Direct service could not be initialised."
                    else -> {
                        peers = emptyList()
                        discovering = true
                        status = "Starting Wi-Fi Direct peer search…"
                        try {
                            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    status = "Search request accepted. Waiting for nearby devices…"
                                    try {
                                        manager.requestPeers(channel) { list ->
                                            peers = list.deviceList.map { device ->
                                                NearbyWifiPeer(
                                                    device.deviceName.ifBlank { "Unnamed Wi-Fi Direct device" },
                                                    device.deviceAddress,
                                                    peerStatus(device.status)
                                                )
                                            }
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            enabled = !discovering
        ) {
            Text(if (!permissionGranted) "Allow and start discovery" else "Start discovery")
        }

        if (discovering) {
            OutlinedButton(
                onClick = {
                    if (manager != null && channel != null) {
                        try {
                            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    discovering = false
                                    status = "Discovery stopped."
                                }
                                override fun onFailure(reason: Int) {
                                    discovering = false
                                    status = "Stop request completed with code $reason."
                                }
                            })
                        } catch (_: SecurityException) {
                            discovering = false
                            status = "Discovery could not be stopped because permission is unavailable."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("Stop discovery") }
        }

        Text("NEARBY WI-FI DIRECT DEVICES", color = Color(0xFF6D7D87), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) {
            InfoCard("No devices listed yet. Install ECHO on the second phone, enable Wi-Fi, grant its nearby-device permission, and start discovery there too.")
        } else {
            peers.forEach { peer ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Text(peer.name, color = Color(0xFF142B3A), fontWeight = FontWeight.SemiBold)
                        Text("${peer.status}  •  ${peer.address}", color = Color(0xFF6D7D87), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }

        Surface(color = Color(0xFFE4F5F1), shape = RoundedCornerShape(13.dp)) {
            Text(
                "Important: Wi-Fi Direct discovery lists nearby Wi-Fi P2P devices, not verified ECHO identities. It does not pair, connect, or enable calls. ECHO identity matching and secure connections come in later steps.",
                color = Color(0xFF142B3A), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(13.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Text(message, color = Color(0xFF6D7D87), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(15.dp))
    }
}

private fun peerStatus(status: Int): String = when (status) {
    WifiP2pDevice.AVAILABLE -> "Available"
    WifiP2pDevice.INVITED -> "Invited"
    WifiP2pDevice.CONNECTED -> "Connected"
    WifiP2pDevice.FAILED -> "Failed"
    WifiP2pDevice.UNAVAILABLE -> "Unavailable"
    else -> "Status unknown"
}
