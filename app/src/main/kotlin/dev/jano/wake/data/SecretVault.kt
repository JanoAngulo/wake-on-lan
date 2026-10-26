package dev.jano.wake.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface Secrets {
    fun put(deviceId: String, secret: ByteArray)
    fun get(deviceId: String): ByteArray?
    fun remove(deviceId: String)
}

/** Pairing secrets encrypted with an AES-GCM key that never leaves the Android Keystore. */
class SecretVault(context: Context) : Secrets {
    private val prefs = context.getSharedPreferences("wake-secrets", Context.MODE_PRIVATE)

    private val key: SecretKey by lazy {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    override fun put(deviceId: String, secret: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val sealed = cipher.iv + cipher.doFinal(secret)
        prefs.edit().putString(deviceId, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun get(deviceId: String): ByteArray? {
        val sealed = prefs.getString(deviceId, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, IV_BYTES))
            }
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }.getOrNull()
    }

    override fun remove(deviceId: String) {
        prefs.edit().remove(deviceId).apply()
    }

    private companion object {
        const val ALIAS = "wake-secrets"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
