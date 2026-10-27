package dev.jano.wake.data

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** [UnlockPasswords] with a software AES-GCM key: same cipher flow as the Keystore one, minus the fingerprint. */
class FakeUnlockPasswords : UnlockPasswords {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val sealed = mutableMapOf<String, ByteArray>()

    /** Devices whose key "was invalidated by a new fingerprint". */
    val invalidated = mutableSetOf<String>()
    val removed = mutableListOf<String>()

    override fun saved(deviceId: String) = deviceId in sealed

    override fun sealCipher(deviceId: String): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun save(deviceId: String, cipher: Cipher, password: ByteArray) {
        sealed[deviceId] = cipher.iv + cipher.doFinal(password)
    }

    override fun openCipher(deviceId: String): OpenResult {
        if (deviceId in invalidated) {
            remove(deviceId)
            return OpenResult.Invalidated
        }
        val data = sealed[deviceId] ?: return OpenResult.NotSaved
        return OpenResult.Ready(
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12)) },
        )
    }

    override fun open(cipher: Cipher, deviceId: String): ByteArray {
        val data = sealed.getValue(deviceId)
        return cipher.doFinal(data, 12, data.size - 12)
    }

    override fun remove(deviceId: String) {
        sealed.remove(deviceId)
        removed += deviceId
    }
}
