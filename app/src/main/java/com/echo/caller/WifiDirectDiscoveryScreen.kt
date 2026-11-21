package com.echo.caller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun WifiDirectDiscoveryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext
    val controller = remember { WifiDirectControllerHolder.get(app) }
    val state = controller.state
    val peers = controller.peers
    val discovering = controller.discovering
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) controller.discover()
    }
    val wifi = remember { app.getSystemService(Context.WIFI_SERVICE) as? WifiManager }
    val location = remember { app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager }
    val wifiOn = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)
    val locationOn = runCatching { if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true else true }.getOrDefault(false)
    val supported = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Nearby discovery & connection", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Text("Connection monitoring is now app-level and should remain available while navigating between tabs.", color = Color(0xFF6D7D87), fontSize = 13.sp)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF142B3A)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("CONNECTION STATUS", color = Color(0xFFB8C8D0), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(state.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                if (state.connected) {
                    Text("Connected • ${if (state.owner) "Group owner" else "Client"}", color = Color(0xFF8DE0C9), modifier = Modifier.padding(top = 8.dp))
                    Text("Peer: ${state.peerName ?: "Wi-Fi Direct peer"}", color = Color(0xFFB8C8D0), fontSize = 12.sp)
                    state.ownerAddress?.let { Text("Group owner address: $it", color = Color(0xFFB8C8D0), fontSize = 11.sp) }
                } else if (state.connecting) Text("Connection pending", color = Color(0xFFFFD18A), modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (!supported) InfoCard("Wi-Fi Direct is not reported as supported.")
        else if (!wifiOn) InfoCard("Turn on Wi-Fi in Android settings.")
        else if (!locationOn) InfoCard("Turn on Android Location for discovery.")
        else if (!granted) InfoCard("ECHO needs Nearby devices permission.")
        Button(onClick = { when {
            !supported || !wifiOn || !locationOn -> Unit
            !granted -> launcher.launch(permission)
            else -> controller.discover()
        } }, modifier = Modifier.fillMaxWidth(), enabled = !discovering && !state.connected) {
            Text(if (!granted) "Allow and start discovery" else "Start discovery")
        }
        if (discovering) OutlinedButton(onClick = { controller.stopDiscovery() }, modifier = Modifier.fillMaxWidth()) { Text("Stop discovery") }
        if (state.connected) OutlinedButton(onClick = { controller.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        Text("NEARBY WI-FI DIRECT DEVICES", color = Color(0xFF6D7D87), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        if (peers.isEmpty()) InfoCard("No devices listed yet. Enable Wi-Fi on both phones and start discovery on both.")
        else peers.forEach { peer ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Text(peer.displayName, color = Color(0xFF142B3A), fontWeight = FontWeight.SemiBold)
                    Text("${peer.availability} • ${peer.transportAddress}", color = Color(0xFF6D7D87), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
                    Text("Transport: ${peer.transport.label} • Identity: ${peer.identityStatus.label}", color = Color(0xFF6D7D87), fontSize = 10.sp)
                    Button(onClick = { controller.connect(peer) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        enabled = !state.connected && !state.connecting && peer.availability != "Unavailable" && peer.transportAddress != "Address unavailable") {
                        Text(if (state.connecting) "Requesting connection…" else "Connect")
                    }
                }
            }
        }
        Surface(color = Color(0xFFE4F5F1), shape = RoundedCornerShape(13.dp)) {
            Text("A Wi-Fi Direct link is not an authenticated ECHO identity. Identity verification and encrypted messaging are separate later steps. No calls or mesh relaying are enabled.",
                color = Color(0xFF142B3A), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(13.dp))
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
        Text(message, color = Color(0xFF6D7D87), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(15.dp))
    }
}

/** Add this to DevicesScreen to display a live link separately from QR-trusted identities. */
@Composable
fun ConnectedDevicesPanel() {
    val state = WifiDirectControllerHolder.peek()?.state
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ACTIVE CONNECTION", color = Color(0xFF6D7D87), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(15.dp)) {
                if (state?.connected == true) {
                    Text(state.peerName ?: "Wi-Fi Direct peer", color = Color(0xFF142B3A), fontWeight = FontWeight.SemiBold)
                    Text("Connected • ${if (state.owner) "Group owner" else "Client"}", color = Color(0xFF13866F), fontSize = 12.sp)
                    Text("Identity: Not verified", color = Color(0xFF6D7D87), fontSize = 11.sp)
                    state.ownerAddress?.let { Text("Group owner: $it", color = Color(0xFF6D7D87), fontSize = 11.sp) }
                } else {
                    Text("No active Wi-Fi Direct connection", color = Color(0xFF142B3A), fontWeight = FontWeight.Medium)
                    Text("Connect from the Discover tab. This is separate from QR-trusted identities.", color = Color(0xFF6D7D87), fontSize = 12.sp)
                }
            }
        }
    }
}
