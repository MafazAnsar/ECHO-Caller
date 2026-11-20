
package com.echo.caller

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Ink = Color(0xFF142B3A)
private val Muted = Color(0xFF6D7D87)
private val Canvas = Color(0xFFF4F7F8)
private val Teal = Color(0xFF13A891)
private val PaleTeal = Color(0xFFE4F5F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val identity = DeviceIdentityManager(this).getOrCreate()
        val store = TrustedDeviceStore(this)
        setContent { EchoApp(identity, store) }
    }
}

@Composable
private fun EchoApp(identity: DeviceIdentity, store: TrustedDeviceStore) {
    var tab by rememberSaveable { mutableStateOf("Home") }
    var pairing by rememberSaveable { mutableStateOf(false) }
    var trusted by remember { mutableStateOf(store.all()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Teal,
            onPrimary = Color.White,
            secondary = Ink,
            background = Canvas,
            surface = Color.White,
            onSurface = Ink
        )
    ) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                if (!pairing) NavigationBar(containerColor = Color.White) {
                    listOf("Home", "Devices", "Network", "Activity").forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = {
                                tab = item
                                trusted = store.all()
                            },
                            icon = {
                                Text(
                                    when (item) {
                                        "Home" -> "⌂"
                                        "Devices" -> "▦"
                                        "Network" -> "⌁"
                                        else -> "◷"
                                    },
                                    fontSize = 21.sp
                                )
                            },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { pad ->
            when {
                pairing -> PairingScreen(
                    Modifier.padding(pad),
                    identity,
                    store,
                    onBack = {
                        pairing = false
                        trusted = store.all()
                    },
                    onRequestCamera = {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    hasCameraPermission = cameraGranted
                )

                tab == "Devices" -> DevicesScreen(
                    Modifier.padding(pad),
                    trusted,
                    onPair = { pairing = true },
                    onRemove = {
                        store.remove(it)
                        trusted = store.all()
                    }
                )

                tab == "Network" -> NetworkDiagnosticsScreen(
                    Modifier.padding(pad)
                )

                tab == "Activity" -> ActivityScreen(
                    Modifier.padding(pad)
                )

                else -> HomeScreen(
                    Modifier.padding(pad),
                    trusted,
                    onPair = { pairing = true },
                    onDevices = { tab = "Devices" },
                    onNetwork = { tab = "Network" }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    trusted: List<TrustedDevice>,
    onPair: () -> Unit,
    onDevices: () -> Unit,
    onNetwork: () -> Unit
) {
    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Ink),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "E",
                    color = Color.White,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(12.dp))

            Column {
                Text(
                    "ECHO",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    letterSpacing = 2.sp
                )
                Text(
                    "OFFLINE LOCAL COMMUNICATION",
                    fontSize = 9.sp,
                    color = Muted,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.weight(1f))

            Surface(shape = CircleShape, color = PaleTeal) {
                Text(
                    "● LOCAL",
                    Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = Teal,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        Text(
            "Stay connected.",
            fontSize = 29.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        Text(
            "Talk directly to people nearby, without relying on the internet.",
            Modifier.padding(top = 7.dp),
            color = Muted,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(22.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Ink)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "NETWORK STATUS",
                    color = Color(0xFFB8C8D0),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                Text(
                    "Not connected",
                    Modifier.padding(top = 13.dp),
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Local discovery and calling are not enabled yet.",
                    Modifier.padding(top = 6.dp),
                    color = Color(0xFFB8C8D0),
                    fontSize = 12.sp
                )

                Spacer(Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onNetwork,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("Run network diagnostics")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("GET STARTED")
        Spacer(Modifier.height(10.dp))

        Card(
            Modifier.fillMaxWidth().clickable(onClick = onPair),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(
                Modifier.padding(17.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PaleTeal),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▦", fontSize = 25.sp, color = Teal)
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        "Pair a device",
                        color = Ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Scan and approve another ECHO device",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }

                Text("›", color = Teal, fontSize = 28.sp)
            }
        }

        Spacer(Modifier.height(23.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("TRUSTED DEVICES")
            Spacer(Modifier.weight(1f))
            Text(
                "View all",
                color = Teal,
                fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onDevices)
            )
        }

        Spacer(Modifier.height(10.dp))

        if (trusted.isEmpty()) {
            EmptyCard(
                "◎",
                "No trusted devices yet",
                "Pair another phone to see it here."
            )
        } else {
            trusted.take(3).forEach {
                DeviceRow(it.displayName, it.fingerprint)
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "ECHO  •  VERSION 0.4.0",
            Modifier.fillMaxWidth(),
            color = Muted,
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SectionLabel(value: String) {
    Text(
        value,
        color = Muted,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp
    )
}

@Composable
private fun PairingScreen(
    modifier: Modifier,
    identity: DeviceIdentity,
    store: TrustedDeviceStore,
    onBack: () -> Unit,
    onRequestCamera: () -> Unit,
    hasCameraPermission: Boolean
) {
    var scanning by rememberSaveable { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<ScannedIdentity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val ownQr = remember(identity.publicKeyBase64) {
        QrCode.create(DeviceIdentityManager.encodeQr(identity))
    }

    Column(
        modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp)
    ) {
        Text(
            "‹  Back",
            color = Teal,
            fontSize = 15.sp,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "Pair a device",
            fontSize = 28.sp,
            color = Ink,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Exchange public identities and approve the fingerprint before trusting a device.",
            Modifier.padding(top = 7.dp),
            color = Muted,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(18.dp))

        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "YOUR ECHO IDENTITY",
                    color = Muted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Image(
                    ownQr.asImageBitmap(),
                    contentDescription = "Your ECHO pairing QR code",
                    modifier = Modifier.size(210.dp).padding(8.dp)
                )

                Text(
                    "Let the other phone scan this code",
                    color = Ink,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Fingerprint",
                    Modifier.padding(top = 10.dp),
                    color = Muted,
                    fontSize = 11.sp
                )

                Text(
                    identity.fingerprint,
                    color = Ink,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (scanning && hasCameraPermission) {
            Text(
                "SCAN THE OTHER PHONE",
                color = Muted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            QrScanner(
                Modifier.fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(18.dp))
            ) { raw ->
                scanning = false
                candidate = DeviceIdentityManager.decodeQr(raw)
                message = if (candidate == null) {
                    "This QR code is not a valid ECHO identity."
                } else {
                    null
                }
            }

            Text(
                "Keep the QR code inside the camera frame.",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                "Stop scanning",
                color = Teal,
                modifier = Modifier.clickable { scanning = false }
                    .padding(vertical = 10.dp)
            )
        } else {
            Button(
                onClick = {
                    if (hasCameraPermission) scanning = true
                    else onRequestCamera()
                },
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    if (hasCameraPermission) "Scan another ECHO device"
                    else "Allow camera and scan"
                )
            }
        }

        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        candidate?.let { peer ->
            Spacer(Modifier.height(14.dp))

            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "IDENTITY FOUND",
                        color = Teal,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        peer.displayName,
                        color = Ink,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Text(
                        "Compare this fingerprint with the other phone before approving:",
                        color = Muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Text(
                        peer.fingerprint,
                        color = Ink,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    if (peer.fingerprint == identity.fingerprint) {
                        Text(
                            "This is this phone's own identity.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Button(
                            onClick = {
                                store.add(peer)
                                candidate = null
                                message = "Device added to your trusted list."
                            },
                            Modifier.fillMaxWidth().padding(top = 14.dp)
                        ) {
                            Text("I verified it — trust this device")
                        }
                    }

                    Text(
                        "Cancel",
                        color = Teal,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                            .clickable { candidate = null }
                            .padding(12.dp)
                    )
                }
            }
        }

        message?.takeIf { it.contains("added") }?.let {
            Surface(
                color = PaleTeal,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text(
                    it,
                    color = Ink,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(color = PaleTeal, shape = RoundedCornerShape(14.dp)) {
            Text(
                "Your private key stays in Android Keystore. Only the public key is shared. Pairing records your approval on this phone; repeat the scan and approval on the other phone. This step does not connect devices or enable calls yet.",
                Modifier.padding(14.dp),
                color = Ink,
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun DevicesScreen(
    modifier: Modifier,
    trusted: List<TrustedDevice>,
    onPair: () -> Unit,
    onRemove: (String) -> Unit
) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Text(
            "Devices",
            fontSize = 28.sp,
            color = Ink,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Your approved ECHO identities",
            Modifier.padding(top = 6.dp),
            color = Muted,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(18.dp))

        if (trusted.isEmpty()) {
            EmptyCard(
                "⌕",
                "No trusted devices",
                "Scan another phone's ECHO QR code to add it to this list."
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(trusted, key = { it.fingerprint }) { device ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            DeviceRow(device.displayName, device.fingerprint)

                            Text(
                                "Remove trusted device",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.End)
                                    .clickable { onRemove(device.fingerprint) }
                                    .padding(top = 10.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onPair,
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp)
        ) {
            Text("Pair another device")
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("CONNECTION")

        Surface(
            color = Color.White,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(9.dp).clip(CircleShape)
                        .background(Color(0xFFE7A64A))
                )

                Spacer(Modifier.width(10.dp))

                Column {
                    Text(
                        "Local discovery inactive",
                        color = Ink,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Trusted identities are saved; network discovery comes next.",
                        color = Muted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(name: String, fingerprint: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(PaleTeal),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "E",
                color = Teal,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(name, color = Ink, fontWeight = FontWeight.SemiBold)
            Text(
                fingerprint,
                color = Muted,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun EmptyCard(symbol: String, title: String, description: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(23.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(symbol, fontSize = 30.sp, color = Muted)
            Text(
                title,
                Modifier.padding(top = 8.dp),
                color = Ink,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                Modifier.padding(top = 4.dp),
                color = Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActivityScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Text(
            "Activity",
            fontSize = 28.sp,
            color = Ink,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Your recent ECHO calls and connection events",
            Modifier.padding(top = 6.dp),
            color = Muted,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(22.dp))

        EmptyCard(
            "◷",
            "Nothing here yet",
            "Call history will appear here once local calling is implemented."
        )
    }
}
