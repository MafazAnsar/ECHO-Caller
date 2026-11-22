package com.echo.caller

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Cryptographic building blocks for authenticated ECHO sessions.
 *
 * This class deliberately contains primitives only. The network handshake must
 * authenticate and bind both ephemeral keys/nonces to the signed transcript
 * before a derived key is trusted.
 */
object EchoSessionCrypto {
    private const val CURVE = "secp256r1"
    private const val AES_KEY_BYTES = 32
    private const val GCM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128

    fun newEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec(CURVE))
            generateKeyPair()
        }

    fun decodeEcPublicKey(encoded: ByteArray): java.security.PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))

    fun agree(privateKey: java.security.PrivateKey, remotePublicKey: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(decodeEcPublicKey(remotePublicKey), true)
        return agreement.generateSecret()
    }

    /** HKDF-SHA256 (RFC 5869), returning a 256-bit AES key. */
    fun deriveAesKey(sharedSecret: ByteArray, salt: ByteArray, context: ByteArray): ByteArray {
        require(sharedSecret.isNotEmpty()) { "Shared secret must not be empty." }
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, sharedSecret)
        val info = context + byteArrayOf(0x01)
        return hmacSha256(prk, info).copyOf(AES_KEY_BYTES)
    }

    /** Output is nonce || AES-GCM ciphertext-and-tag. */
    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == AES_KEY_BYTES) { "AES-256 key must be 32 bytes." }
        val nonce = ByteArray(GCM_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, encrypted: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteArray {
        require(key.size == AES_KEY_BYTES) { "AES-256 key must be 32 bytes." }
        require(encrypted.size >= GCM_NONCE_BYTES + 16) { "Encrypted message is too short." }
        val nonce = encrypted.copyOfRange(0, GCM_NONCE_BYTES)
        val ciphertext = encrypted.copyOfRange(GCM_NONCE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        if (associatedData.isNotEmpty()) cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}
