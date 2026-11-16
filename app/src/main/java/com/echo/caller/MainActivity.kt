package com.echo.caller

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF142B3A)
private val Muted = Color(0xFF6D7D87)
private val Canvas = Color(0xFFF4F7F8)
private val Teal = Color(0xFF13A891)
private val PaleTeal = Color(0xFFE4F5F1)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EchoApp() }
    }
}

@Composable
private fun EchoApp() {
    var tab by rememberSaveable { mutableStateOf("Home") }
    var pairing by rememberSaveable { mutableStateOf(false) }

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Teal, onPrimary = Color.White, secondary = Ink,
        background = Canvas, surface = Color.White, onSurface = Ink
    )) {
        Scaffold(
            containerColor = Canvas,
            bottomBar = {
                if (!pairing) NavigationBar(containerColor = Color.White) {
                    listOf("Home", "Devices", "Activity").forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Text(when (item) {
                                "Home" -> "⌂"
                                "Devices" -> "▦"
                                else -> "◷"
                            }, fontSize = 21.sp) },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { pad ->
            when {
                pairing -> PairingScreen(Modifier.padding(pad)) { pairing = false }
                tab == "Devices" -> DevicesScreen(Modifier.padding(pad)) { pairing = true }
                tab == "Activity" -> ActivityScreen(Modifier.padding(pad))
                else -> HomeScreen(Modifier.padding(pad), { pairing = true }, { tab = "Devices" })
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, onPair: () -> Unit, onDevices: () -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Ink),
                contentAlignment = Alignment.Center) {
                Text("E", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("ECHO", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, letterSpacing = 2.sp)
                Text("OFFLINE LOCAL COMMUNICATION", fontSize = 9.sp, color = Muted, letterSpacing = 1.sp)
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = CircleShape, color = PaleTeal) {
                Text("● LOCAL", Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    color = Teal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(30.dp))
        Text("Stay connected.", fontSize = 29.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text("Talk directly to people nearby, without relying on the internet.",
            Modifier.padding(top = 7.dp), color = Muted, fontSize = 14.sp)

        Spacer(Modifier.height(22.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Ink)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFFFC66D)))
                    Spacer(Modifier.width(8.dp))
                    Text("NETWORK STATUS", color = Color(0xFFB8C8D0), fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
                Text("Not connected", Modifier.padding(top = 13.dp), color = Color.White,
                    fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Nearby device discovery is not enabled yet.",
                    Modifier.padding(top = 6.dp), color = Color(0xFFB8C8D0), fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = onDevices,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                    Text("View device status")
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("GET STARTED")
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth().clickable(onClick = onPair),
            shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(PaleTeal),
                    contentAlignment = Alignment.Center) { Text("▦", fontSize = 25.sp, color = Teal) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pair a device", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Set up a trusted ECHO connection", color = Muted, fontSize = 12.sp)
                }
                Text("›", color = Teal, fontSize = 28.sp)
            }
        }

        Spacer(Modifier.height(23.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("TRUSTED DEVICES")
            Spacer(Modifier.weight(1f))
            Text("View all", color = Teal, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onDevices))
        }
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 23.dp, horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("◎", fontSize = 28.sp, color = Muted)
                Text("No trusted devices yet", Modifier.padding(top = 8.dp), color = Ink, fontWeight = FontWeight.SemiBold)
                Text("Pair another phone to see it here.", Modifier.padding(top = 4.dp), color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("ECHO  •  VERSION 0.2.0", Modifier.fillMaxWidth(), color = Muted,
            fontSize = 10.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionLabel(value: String) {
    Text(value, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
}

@Composable
private fun PairingScreen(modifier: Modifier, onBack: () -> Unit) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Text("‹  Back", color = Teal, fontSize = 15.sp,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp))
        Spacer(Modifier.height(24.dp))
        Text("Pair a device", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("Create a trusted connection between two ECHO devices.",
            Modifier.padding(top = 7.dp), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(25.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(172.dp).clip(RoundedCornerShape(18.dp)).background(Canvas),
                    contentAlignment = Alignment.Center) { Text("▦", color = Teal, fontSize = 86.sp) }
                Text("QR pairing is coming next", Modifier.padding(top = 20.dp), color = Ink,
                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("The pairing screen is ready. Camera scanning and secure device identity exchange will be added in a later implementation step.",
                    Modifier.padding(top = 8.dp), color = Muted, fontSize = 13.sp,
                    textAlign = TextAlign.Center, lineHeight = 19.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Surface(color = PaleTeal, shape = RoundedCornerShape(14.dp)) {
            Text("For now, no camera access or network connection is required. Pairing is a visual preview only.",
                Modifier.padding(14.dp), color = Ink, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text("Back to ECHO")
        }
    }
}

@Composable
private fun DevicesScreen(modifier: Modifier, onPair: () -> Unit) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Text("Devices", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("Your nearby and trusted ECHO devices", Modifier.padding(top = 6.dp), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⌕", color = Muted, fontSize = 36.sp)
                Text("No devices to show", Modifier.padding(top = 8.dp), color = Ink,
                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Device discovery and trusted-device storage are not connected yet.",
                    Modifier.padding(top = 6.dp), color = Muted, fontSize = 13.sp,
                    textAlign = TextAlign.Center, lineHeight = 19.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onPair, shape = RoundedCornerShape(12.dp)) { Text("Pair a device") }
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionLabel("CONNECTION")
        Spacer(Modifier.height(8.dp))
        Surface(color = Color.White, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE7A64A)))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Local discovery inactive", color = Ink, fontWeight = FontWeight.Medium)
                    Text("No local network scan is running", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ActivityScreen(modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Text("Activity", fontSize = 28.sp, color = Ink, fontWeight = FontWeight.Bold)
        Text("Your recent ECHO calls and connection events",
            Modifier.padding(top = 6.dp), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("◷", color = Muted, fontSize = 36.sp)
                Text("Nothing here yet", Modifier.padding(top = 8.dp), color = Ink,
                    fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("Call history will appear here once local calling is implemented.",
                    Modifier.padding(top = 6.dp), color = Muted, fontSize = 13.sp,
                    textAlign = TextAlign.Center, lineHeight = 19.sp)
            }
        }
    }
}
