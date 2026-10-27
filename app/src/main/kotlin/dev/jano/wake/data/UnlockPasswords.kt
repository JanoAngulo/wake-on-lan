package dev.jano.wake.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface OpenResult {
    /** Initialised for decryption; usable only after the fingerprint prompt authorises it. */
    data class Ready(val cipher: Cipher) : OpenResult
    data object NotSaved : OpenResult

    /** Fingerprints changed on the phone, so the key is gone. The saved password has been removed. */
    data object Invalidated : OpenResult
}

/** The PC's Windows password, one per device, sealed by a Keystore key that needs a fingerprint for every use. */
interface UnlockPasswords {
    fun saved(deviceId: String): Boolean

    /** A fresh key (replacing any old one) and an ENCRYPT cipher to authorise with the fingerprint. */
    fun sealCipher(deviceId: String): Cipher

    /** [cipher] must be the authorised one from [sealCipher]. */
    fun save(deviceId: String, cipher: Cipher, password: ByteArray)
    fun openCipher(deviceId: String): OpenResult

    /** [cipher] must be the authorised one from [openCipher]. The caller zeroes the result. */
    fun open(cipher: Cipher, deviceId: String): ByteArray
    fun remove(deviceId: String)
}

/** Shows the fingerprint prompt for [cipher]; null when cancelled or failed. */
fun interface Authenticator {
    suspend fun authenticate(cipher: Cipher): Cipher?
}

class KeystoreUnlockPasswords(context: Context) : UnlockPasswords {
    private val prefs = context.getSharedPreferences("wake-unlock", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    override fun saved(deviceId: String) = prefs.contains(deviceId) && keyStore.containsAlias(alias(deviceId))

    override fun sealCipher(deviceId: String): Cipher {
        val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias(deviceId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true)
                    // Timeout 0: every use needs a fresh fingerprint (or the phone's PIN as fallback).
                    .setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            generateKey()
        }
        prefs.edit().remove(deviceId).apply()
        return Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    override fun save(deviceId: String, cipher: Cipher, password: ByteArray) {
        val sealed = cipher.iv + cipher.doFinal(password)
        prefs.edit().putString(deviceId, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun openCipher(deviceId: String): OpenResult {
        val sealed = sealed(deviceId) ?: return OpenResult.NotSaved
        val key = keyStore.getKey(alias(deviceId), null) as? SecretKey ?: return OpenResult.NotSaved
        return try {
            OpenResult.Ready(
                Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES)) },
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            remove(deviceId)
            OpenResult.Invalidated
        }
    }

    override fun open(cipher: Cipher, deviceId: String): ByteArray {
        val sealed = checkNotNull(sealed(deviceId)) { "no saved password" }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    override fun remove(deviceId: String) {
        prefs.edit().remove(deviceId).apply()
        if (keyStore.containsAlias(alias(deviceId))) keyStore.deleteEntry(alias(deviceId))
    }

    private fun sealed(deviceId: String) = prefs.getString(deviceId, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private fun alias(deviceId: String) = "wake-unlock-$deviceId"

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
