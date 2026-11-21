package com.echo.caller

import android.net.wifi.p2p.WifiP2pDevice

/** Transport-independent description of a device discovered by ECHO. */
enum class TransportType(val label: String) {
    WIFI_DIRECT("Wi-Fi Direct"),
    WIFI_AWARE("Wi-Fi Aware"),
    LAN("Local network"),
    BLUETOOTH("Bluetooth")
}

enum class PeerIdentityStatus(val label: String) {
    UNVERIFIED("Not verified"),
    VERIFIED("ECHO identity verified")
}

data class DiscoveredPeer(
    /** Stable only within the discovery transport; not a cryptographic identity. */
    val peerId: String,
    val displayName: String,
    val transport: TransportType,
    val transportAddress: String,
    val availability: String,
    /** A peer remains unverified until a later authenticated handshake succeeds. */
    val identityStatus: PeerIdentityStatus = PeerIdentityStatus.UNVERIFIED,
    /** Set only after a cryptographically authenticated ECHO identity exchange. */
    val verifiedFingerprint: String? = null
)

/** Common lifecycle contract for future discovery implementations. */
interface TransportAdapter {
    val transportType: TransportType
    fun isSupported(): Boolean
    fun startDiscovery(onPeersChanged: (List<DiscoveredPeer>) -> Unit, onStatus: (String) -> Unit)
    fun stopDiscovery()
}

/** Centralises peers discovered through one or more transport adapters. */
class TransportManager {
    private val peersByTransport = linkedMapOf<TransportType, List<DiscoveredPeer>>()

    @Synchronized
    fun replacePeers(
        transport: TransportType,
        peers: List<DiscoveredPeer>
    ): List<DiscoveredPeer> {
        peersByTransport[transport] = peers
        return snapshotLocked()
    }

    @Synchronized
    fun clear(transport: TransportType = TransportType.WIFI_DIRECT): List<DiscoveredPeer> {
        peersByTransport.remove(transport)
        return snapshotLocked()
    }

    @Synchronized
    fun snapshot(): List<DiscoveredPeer> = snapshotLocked()

    private fun snapshotLocked(): List<DiscoveredPeer> = peersByTransport.values.flatten()
        .distinctBy { it.peerId }
        .sortedWith(compareBy<DiscoveredPeer> { it.displayName.lowercase() }.thenBy { it.transport.name })
}

/** Converts Android Wi-Fi Direct results into ECHO's transport-neutral peer model. */
object WifiDirectPeerMapper {
    fun map(devices: Collection<WifiP2pDevice>): List<DiscoveredPeer> = devices.map { device ->
        val address = device.deviceAddress.orEmpty()
        DiscoveredPeer(
            peerId = "wifi-direct:${address.lowercase().ifBlank { device.deviceName }}",
            displayName = device.deviceName.ifBlank { "Unnamed nearby device" },
            transport = TransportType.WIFI_DIRECT,
            transportAddress = address.ifBlank { "Address unavailable" },
            availability = when (device.status) {
                WifiP2pDevice.AVAILABLE -> "Available"
                WifiP2pDevice.INVITED -> "Invited"
                WifiP2pDevice.CONNECTED -> "Connected"
                WifiP2pDevice.FAILED -> "Failed"
                WifiP2pDevice.UNAVAILABLE -> "Unavailable"
                else -> "Status unknown"
            }
        )
    }
}
