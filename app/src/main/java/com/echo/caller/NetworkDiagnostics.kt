package com.echo.caller

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiAwareManager
import android.net.wifi.WifiManager
import android.bluetooth.BluetoothManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class DiagnosticItem(val title: String, val status: String, val detail: String)

private data class DiagnosticSnapshot(
    val device: String,
    val android: String,
    val items: List<DiagnosticItem>
)

private fun readDiagnostics(context: Context): DiagnosticSnapshot {
    val pm = context.packageManager
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val awareSupported = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
    val awareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
    val awareAvailable = if (awareSupported) runCatching { awareManager?.isAvailable == true }.getOrDefault(false) else false
    val wifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val activeNetwork = runCatching { connectivity?.activeNetwork }.getOrNull()
    val capabilities = runCatching { if (connectivity != null && activeNetwork != null) connectivity.getNetworkCapabilities(activeNetwork) else null }.getOrNull()
    val localTransports = buildList {
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Mobile network")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
    }

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothSupported = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) && bluetoothManager?.adapter != null

    val items = listOf(
        DiagnosticItem(
            "Wi-Fi Aware (NAN)",
            when { !awareSupported -> "Not supported"; awareAvailable -> "Available now"; else -> "Supported, not currently available" },
            if (!awareSupported) "This phone does not report Wi-Fi Aware hardware support."
            else if (awareAvailable) "Can be evaluated as a direct peer-to-peer transport."
            else "May be unavailable while Wi-Fi is off or due to device/firmware conditions."
        ),
        DiagnosticItem(
            "Wi-Fi Direct", if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) "Supported" else "Not reported",
            "Direct device networking; ECHO would still provide multi-hop routing."
        ),
        DiagnosticItem(
            "Wi-Fi", if (wifiEnabled) "Enabled" else "Disabled or unavailable",
            "Diagnostics do not turn Wi-Fi on or change its settings."
        ),
        DiagnosticItem(
            "Current network", if (localTransports.isEmpty()) "No active network detected" else localTransports.joinToString(),
            "This is informational only. ECHO local communication will not require internet access."
        ),
        DiagnosticItem(
            "Bluetooth", if (bluetoothSupported) "Hardware available" else "Not reported",
            "Potential later fallback for discovery or low-bandwidth functions; voice support is not assumed."
        ),
        DiagnosticItem(
            "Hotspot / SoftAP", "Not tested",
            "Android does not provide a universal, non-invasive check that guarantees hotspot operation. No hotspot was started."
        )
    )

    return DiagnosticSnapshot(
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        items = items
    )
}

@Composable
fun NetworkDiagnosticsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val snapshot = remember(context, refreshKey) { readDiagnostics(context) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Network diagnostics", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
        Text("A read-only check of this phone's wireless capabilities.", color = Color(0xFF6D7D87), fontSize = 13.sp)

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF142B3A)), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(snapshot.device, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(snapshot.android, color = Color(0xFFB8C8D0), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        snapshot.items.forEach { item ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                Column(Modifier.fillMaxWidth().padding(15.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.title, color = Color(0xFF142B3A), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(item.status, color = when {
                            item.status == "Available now" || item.status == "Supported" || item.status == "Enabled" || item.status == "Hardware available" -> Color(0xFF13866F)
                            item.status == "Not supported" || item.status == "Disabled or unavailable" -> MaterialTheme.colorScheme.error
                            else -> Color(0xFF8A6A2F)
                        }, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(item.detail, color = Color(0xFF6D7D87), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), lineHeight = 17.sp)
                }
            }
        }

        Button(onClick = { refreshKey++ }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text("Refresh diagnostics")
        }
        Spacer(Modifier.height(4.dp))
        Surface(color = Color(0xFFE4F5F1), shape = RoundedCornerShape(13.dp)) {
            Text(
                "No device discovery or connections are started in this phase. Results describe reported device capability, not a successful connection test.",
                color = Color(0xFF142B3A), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(13.dp)
            )
        }
    }
}
