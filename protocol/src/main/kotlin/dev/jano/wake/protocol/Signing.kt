package dev.jano.wake.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

const val H_TIMESTAMP = "X-Wake-Timestamp"
const val H_NONCE = "X-Wake-Nonce"
const val H_SIGNATURE = "X-Wake-Signature"

data class SignedHeaders(val timestampMs: Long, val nonce: String, val signature: String)

enum class VerifyResult { OK, NOT_PAIRED, STALE, REPLAY, BAD_SIGNATURE }

private val random = SecureRandom()
private val base64Url = Base64.getUrlEncoder().withoutPadding()

internal fun b64url(bytes: ByteArray): String = base64Url.encodeToString(bytes)

internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(message)
    }

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun newNonce(): String = b64url(ByteArray(16).also(random::nextBytes))

private fun canonical(method: String, path: String, timestampMs: Long, nonce: String, body: ByteArray): ByteArray {
    val bodyHash = sha256(body).joinToString("") { "%02x".format(it) }
    return "${method.uppercase()}\n$path\n$timestampMs\n$nonce\n$bodyHash".toByteArray()
}

fun sign(
    secret: ByteArray,
    method: String,
    path: String,
    body: ByteArray,
    nowMs: Long,
    nonce: String = newNonce(),
): SignedHeaders {
    val signature = b64url(hmacSha256(secret, canonical(method, path, nowMs, nonce, body)))
    return SignedHeaders(nowMs, nonce, signature)
}

/**
 * Proves a "stale" reply's clock came from the paired agent and answers this request's nonce,
 * so nobody in the middle can push the phone into signing for a future time.
 */
fun staleProof(secret: ByteArray, nonce: String, serverTimeMs: Long): String =
    b64url(hmacSha256(secret, "stale\n$nonce\n$serverTimeMs".toByteArray()))

class RequestVerifier(
    private val secret: () -> ByteArray?,
    private val clock: () -> Long,
    private val maxSkewMs: Long = 30_000,
) {
    private val seenNonces = LinkedHashMap<String, Long>()

    fun verify(method: String, path: String, body: ByteArray, headers: SignedHeaders): VerifyResult {
        val key = secret() ?: return VerifyResult.NOT_PAIRED
        // Signature before freshness: only the paired phone ever learns the agent's clock.
        val expected = sign(key, method, path, body, headers.timestampMs, headers.nonce).signature
        if (!MessageDigest.isEqual(expected.toByteArray(), headers.signature.toByteArray())) {
            return VerifyResult.BAD_SIGNATURE
        }
        val now = clock()
        if (abs(now - headers.timestampMs) > maxSkewMs) return VerifyResult.STALE

        synchronized(seenNonces) {
            seenNonces.entries.removeAll { now - it.value > 2 * maxSkewMs }
            if (headers.nonce in seenNonces) return VerifyResult.REPLAY
            seenNonces[headers.nonce] = now
        }
        return VerifyResult.OK
    }
}
