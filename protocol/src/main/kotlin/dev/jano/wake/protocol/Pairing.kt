package dev.jano.wake.protocol

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement

private const val SECRET_INFO = "wake-secret-v1"

// Desktop JDKs name the algorithm "X25519"; some Android providers only register "XDH".
private inline fun <T> withX25519(block: (String) -> T): T =
    try {
        block("X25519")
    } catch (e: java.security.NoSuchAlgorithmException) {
        block("XDH")
    }

fun generateX25519(): KeyPair = withX25519 { algorithm ->
    KeyPairGenerator.getInstance(algorithm).apply {
        if (algorithm == "XDH") initialize(NamedParameterSpec.X25519)
    }.generateKeyPair()
}

fun encodePublic(key: PublicKey): String = b64url(key.encoded)

fun decodePublic(b64: String): PublicKey = withX25519 { algorithm ->
    KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(b64)))
}

fun transcript(phonePub: String, agentPub: String): ByteArray =
    "wake-pair-v1\n$phonePub\n$agentPub".toByteArray()

fun confirmTag(code: String, role: String, transcript: ByteArray): String =
    b64url(hmacSha256(code.toByteArray(), "$role\n".toByteArray() + transcript))

fun deriveSecret(own: PrivateKey, peerPublic: PublicKey, transcript: ByteArray): ByteArray {
    val shared = withX25519 { algorithm ->
        KeyAgreement.getInstance(algorithm).run {
            init(own)
            doPhase(peerPublic, true)
            generateSecret()
        }
    }
    return hkdfSha256(shared, sha256(transcript), SECRET_INFO.toByteArray(), 32)
}

/** RFC 5869 HKDF with HMAC-SHA256. */
fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
    val out = ByteArrayOutputStream()
    var block = ByteArray(0)
    var counter = 1
    while (out.size() < length) {
        block = hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
        out.write(block)
        counter++
    }
    return out.toByteArray().copyOf(length)
}
