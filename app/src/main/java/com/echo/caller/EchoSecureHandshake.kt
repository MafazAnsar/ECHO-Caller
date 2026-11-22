package com.echo.caller

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/** QR-trust-based mutual authentication and ephemeral encrypted LAN session. */
object EchoSecureHandshake {
    private const val MAX_FRAME = 16 * 1024
    private const val NONCE_SIZE = 32
    private const val HELLO = "ECHO_SECURE_HELLO/1"
    private const val REPLY = "ECHO_SECURE_REPLY/1"

    data class Session(val key: ByteArray, val transcriptHash: ByteArray)

    fun client(socket: Socket, identityManager: DeviceIdentityManager, trustedPeer: TrustedDevice): String {
        val local = identityManager.getOrCreate()
        val remoteKey = decodeKey(trustedPeer.publicKeyBase64)
        require(DeviceIdentityManager.fingerprint(remoteKey) == trustedPeer.fingerprint) { "Saved peer identity is invalid." }
        val ephemeral = EchoSessionCrypto.newEphemeralKeyPair()
        val clientNonce = randomNonce()
        val clientUnsigned = encodeFields(HELLO, local.fingerprint, trustedPeer.fingerprint,
            b64(ephemeral.public.encoded), b64(clientNonce))
        val clientHello = encodeFields(HELLO, local.fingerprint, trustedPeer.fingerprint,
            b64(ephemeral.public.encoded), b64(clientNonce), b64(identityManager.sign(clientUnsigned)))
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        writeFrame(output, clientHello)

        val reply = decodeFields(readFrame(input))
        require(reply.size == 6 && reply[0] == REPLY) { "Invalid secure handshake response." }
        require(reply[1] == trustedPeer.fingerprint && reply[2] == local.fingerprint) {
            "The responding device identity does not match the paired device."
        }
        val serverEphemeral = unb64(reply[3])
        val serverNonce = unb64(reply[4])
        val transcript = transcript(clientUnsigned, reply[1], reply[2], reply[3], reply[4])
        require(DeviceIdentityManager.verifySignature(remoteKey, transcript, unb64(reply[5]))) {
            "The paired device could not prove its identity."
        }
        val session = derive(ephemeral, serverEphemeral, clientNonce, serverNonce, transcript)
        writeFrame(output, EchoSessionCrypto.encrypt(session.key, "ECHO_SECURE_PING/1".toByteArray(), session.transcriptHash))
        val response = String(EchoSessionCrypto.decrypt(session.key, readFrame(input), session.transcriptHash), Charsets.UTF_8)
        require(response.startsWith("ECHO_SECURE_PONG/1|")) { "Encrypted peer response was invalid." }
        return response.substringAfter("|")
    }

    fun server(socket: Socket, identityManager: DeviceIdentityManager, trustedStore: TrustedDeviceStore): String {
        val local = identityManager.getOrCreate()
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val hello = decodeFields(readFrame(input))
        require(hello.size == 6 && hello[0] == HELLO) { "Invalid secure handshake request." }
        require(hello[2] == local.fingerprint) { "Handshake addressed to another device." }
        val trusted = trustedStore.all().firstOrNull { it.fingerprint == hello[1] }
            ?: throw SecurityException("Unpaired device rejected.")
        val clientKey = decodeKey(trusted.publicKeyBase64)
        require(DeviceIdentityManager.fingerprint(clientKey) == trusted.fingerprint) { "Saved peer identity is invalid." }
        val clientUnsigned = encodeFields(HELLO, hello[1], hello[2], hello[3], hello[4])
        require(DeviceIdentityManager.verifySignature(clientKey, clientUnsigned, unb64(hello[5]))) {
            "Unpaired or unauthenticated device rejected."
        }

        val ephemeral = EchoSessionCrypto.newEphemeralKeyPair()
        val serverNonce = randomNonce()
        val transcript = transcript(clientUnsigned, local.fingerprint, trusted.fingerprint,
            b64(ephemeral.public.encoded), b64(serverNonce))
        val response = encodeFields(REPLY, local.fingerprint, trusted.fingerprint,
            b64(ephemeral.public.encoded), b64(serverNonce), b64(identityManager.sign(transcript)))
        writeFrame(output, response)
        val session = derive(ephemeral, unb64(hello[3]), unb64(hello[4]), serverNonce, transcript)
        val ping = EchoSessionCrypto.decrypt(session.key, readFrame(input), session.transcriptHash)
        require(String(ping, Charsets.UTF_8) == "ECHO_SECURE_PING/1") { "Invalid encrypted test message." }
        val pong = "ECHO_SECURE_PONG/1|${android.os.Build.MODEL.take(24)}".toByteArray()
        writeFrame(output, EchoSessionCrypto.encrypt(session.key, pong, session.transcriptHash))
        return trusted.displayName
    }

    private fun derive(local: java.security.KeyPair, remote: ByteArray, clientNonce: ByteArray,
        serverNonce: ByteArray, transcript: ByteArray): Session {
        require(clientNonce.size == NONCE_SIZE && serverNonce.size == NONCE_SIZE) { "Invalid handshake nonce." }
        val shared = EchoSessionCrypto.agree(local.private, remote)
        val hash = MessageDigest.getInstance("SHA-256").digest(transcript)
        val key = EchoSessionCrypto.deriveAesKey(shared, clientNonce + serverNonce, hash)
        shared.fill(0)
        return Session(key, hash)
    }

    private fun transcript(clientHello: ByteArray, vararg fields: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(clientHello.size)
            out.write(clientHello)
            fields.forEach { out.writeUTF(it) }
        }
        return bytes.toByteArray()
    }
    private fun encodeFields(vararg fields: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> fields.forEach { out.writeUTF(it) } }
        return bytes.toByteArray()
    }
    private fun decodeFields(bytes: ByteArray): List<String> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val fields = mutableListOf<String>()
        while (input.available() > 0) fields.add(input.readUTF())
        return fields
    }
    private fun writeFrame(output: DataOutputStream, payload: ByteArray) {
        require(payload.size in 1..MAX_FRAME) { "Invalid frame size." }
        output.writeInt(payload.size); output.write(payload); output.flush()
    }
    private fun readFrame(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 1..MAX_FRAME) { "Invalid frame size." }
        return ByteArray(size).also { input.readFully(it) }
    }
    private fun decodeKey(value: String): ByteArray = unb64(value)
    private fun b64(value: ByteArray): String = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun randomNonce(): ByteArray = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
}
