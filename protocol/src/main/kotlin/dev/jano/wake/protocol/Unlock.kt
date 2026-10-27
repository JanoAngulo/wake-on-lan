package dev.jano.wake.protocol

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class UnlockResult {
    @SerialName("unlocked") UNLOCKED,
    @SerialName("wrong_password") WRONG_PASSWORD,
    @SerialName("not_locked") NOT_LOCKED,
    @SerialName("no_module") NO_MODULE,
    @SerialName("timeout") TIMEOUT,
    @SerialName("failed") FAILED,
}

/** The Windows password, AES-GCM sealed under a key derived from the pairing secret. */
@Serializable
data class UnlockRequest(val iv: String, val password: String)

/** [ntstatus] is what Windows answered, set for WRONG_PASSWORD and FAILED. */
@Serializable
data class UnlockResponse(val result: UnlockResult, val ntstatus: Long? = null)

private const val IV_BYTES = 12
private const val TAG_BITS = 128
private val random = SecureRandom()

private fun unlockKey(secret: ByteArray) =
    SecretKeySpec(hkdfSha256(secret, ByteArray(0), "wake-unlock-v1".toByteArray(), 32), "AES")

/** The nonce is the request's X-Wake-Nonce, bound in as AAD so the ciphertext can't move to another request. */
fun sealPassword(secret: ByteArray, nonce: String, password: ByteArray): UnlockRequest {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, unlockKey(secret), GCMParameterSpec(TAG_BITS, iv))
    cipher.updateAAD(nonce.toByteArray())
    return UnlockRequest(b64url(iv), b64url(cipher.doFinal(password)))
}

/** Null when the request is malformed or wasn't sealed with this secret and nonce. */
fun openPassword(secret: ByteArray, nonce: String, request: UnlockRequest): ByteArray? {
    val decoder = Base64.getUrlDecoder()
    return try {
        val iv = decoder.decode(request.iv)
        if (iv.size != IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, unlockKey(secret), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(nonce.toByteArray())
        cipher.doFinal(decoder.decode(request.password))
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: GeneralSecurityException) {
        null
    }
}
