package com.echo.caller

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.util.Base64

data class DeviceIdentity(val publicKey: ByteArray, val publicKeyBase64: String, val fingerprint: String, val displayName: String)
data class ScannedIdentity(val publicKeyBase64: String, val fingerprint: String, val displayName: String)

class DeviceIdentityManager(private val context: Context) {
    private val alias = "echo_device_identity_v1"

    fun getOrCreate(): DeviceIdentity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        val publicKey = keyStore.getCertificate(alias).publicKey.encoded
        val encoded = Base64.encodeToString(publicKey, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return DeviceIdentity(publicKey, encoded, fingerprint(publicKey), Build.MODEL?.take(40)?.ifBlank { "Android device" } ?: "Android device")
    }

    /**
     * Signs protocol data with this device's long-term Android Keystore identity.
     * The private key is never exported from Android Keystore.
     */
    fun sign(data: ByteArray): ByteArray {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(alias, null) as java.security.PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
    }

    companion object {
        fun verifySignature(publicKeyEncoded: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
            return try {
                val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyEncoded))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey)
                    update(data)
                    verify(signature)
                }
            } catch (_: Exception) {
                false
            }
        }

        fun fingerprint(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(publicKey)
            .joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")

        fun encodeQr(identity: DeviceIdentity): String {
            val name = Base64.encodeToString(identity.displayName.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "ECHO1|${identity.publicKeyBase64}|$name"
        }

        fun decodeQr(value: String): ScannedIdentity? {
            return try {
                val parts = value.trim().split('|')
                if (parts.size != 3 || parts[0] != "ECHO1") null else {
                    val key = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    if (key.isEmpty()) null else {
                        // Reject arbitrary data: the QR must contain a valid X.509 EC public key.
                        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
                        val name = String(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
                            .filter { it.isLetterOrDigit() || it.isWhitespace() || it in "-_.()" }.take(40).ifBlank { "ECHO device" }
                        ScannedIdentity(parts[1], fingerprint(key), name)
                    }
                }
            } catch (_: Exception) { null }
        }
    }
}

data class TrustedDevice(val fingerprint: String, val publicKeyBase64: String, val displayName: String)

class TrustedDeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("echo_trusted_devices_v1", Context.MODE_PRIVATE)

    fun all(): List<TrustedDevice> = prefs.all.entries.mapNotNull { (key, value) ->
        if (!key.startsWith("device_") || value !is String) null else {
            val fields = value.split('|', limit = 2)
            if (fields.size != 2) null else TrustedDevice(key.removePrefix("device_"), fields[0], fields[1])
        }
    }.sortedBy { it.displayName.lowercase() }

    fun add(device: ScannedIdentity) {
        prefs.edit().putString("device_${device.fingerprint}", "${device.publicKeyBase64}|${device.displayName}").apply()
    }

    fun remove(fingerprint: String) { prefs.edit().remove("device_$fingerprint").apply() }
}
