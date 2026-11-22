package com.echo.caller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EchoSessionCryptoTest {
    @Test
    fun bothPeersDeriveSameSessionKey() {
        val alice = EchoSessionCrypto.newEphemeralKeyPair()
        val bob = EchoSessionCrypto.newEphemeralKeyPair()

        val aliceSecret = EchoSessionCrypto.agree(alice.private, bob.public.encoded)
        val bobSecret = EchoSessionCrypto.agree(bob.private, alice.public.encoded)

        assertArrayEquals(aliceSecret, bobSecret)
        val salt = ByteArray(32) { it.toByte() }
        val context = "ECHO session test".toByteArray()
        val aliceKey = EchoSessionCrypto.deriveAesKey(aliceSecret, salt, context)
        val bobKey = EchoSessionCrypto.deriveAesKey(bobSecret, salt, context)
        assertArrayEquals(aliceKey, bobKey)
    }

    @Test
    fun aesGcmRoundTripAndTamperDetection() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val aad = "ECHO transcript".toByteArray()
        val message = "Encrypted hello from ECHO".toByteArray()
        val encrypted = EchoSessionCrypto.encrypt(key, message, aad)

        assertArrayEquals(message, EchoSessionCrypto.decrypt(key, encrypted, aad))
        val altered = encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(Exception::class.java) {
            EchoSessionCrypto.decrypt(key, altered, aad)
        }
    }

    @Test
    fun wrongAssociatedDataIsRejected() {
        val key = ByteArray(32) { 7 }
        val encrypted = EchoSessionCrypto.encrypt(key, "hello".toByteArray(), "correct".toByteArray())
        assertThrows(Exception::class.java) {
            EchoSessionCrypto.decrypt(key, encrypted, "wrong".toByteArray())
        }
    }

    @Test
    fun encryptionUsesDifferentNonceForSameMessage() {
        val key = ByteArray(32) { 3 }
        val message = "same message".toByteArray()
        val first = EchoSessionCrypto.encrypt(key, message)
        val second = EchoSessionCrypto.encrypt(key, message)
        assertFalse(first.contentEquals(second))
        assertNotEquals(first.take(12), second.take(12))
    }
}
