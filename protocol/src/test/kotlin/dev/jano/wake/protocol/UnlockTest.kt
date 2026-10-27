package dev.jano.wake.protocol

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class UnlockTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val otherSecret = ByteArray(32) { (it + 1).toByte() }
    private val password = "pässwörd".toByteArray()

    @Test
    fun sealThenOpenRoundTrips() {
        val opened = openPassword(secret, "n1", sealPassword(secret, "n1", password))
        assertContentEquals(password, opened)
    }

    @Test
    fun openWithOtherNonceFails() {
        assertNull(openPassword(secret, "n2", sealPassword(secret, "n1", password)))
    }

    @Test
    fun openWithOtherSecretFails() {
        assertNull(openPassword(otherSecret, "n1", sealPassword(secret, "n1", password)))
    }

    @Test
    fun tamperedCiphertextFails() {
        val sealed = sealPassword(secret, "n1", password)
        val bytes = Base64.getUrlDecoder().decode(sealed.password)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = sealed.copy(password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        assertNull(openPassword(secret, "n1", tampered))
    }

    @Test
    fun badBase64AndWrongIvLengthFail() {
        val sealed = sealPassword(secret, "n1", password)
        assertNull(openPassword(secret, "n1", sealed.copy(password = "!!")))
        assertNull(openPassword(secret, "n1", sealed.copy(iv = "AAAA")))
    }

    @Test
    fun ivIsFreshEachSeal() {
        assertNotEquals(sealPassword(secret, "n1", password).iv, sealPassword(secret, "n1", password).iv)
    }

    @Test
    fun unlockKeyIsNotTheSecret() {
        val sealed = sealPassword(secret, "n1", password)
        val decoder = Base64.getUrlDecoder()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, decoder.decode(sealed.iv)))
        cipher.updateAAD("n1".toByteArray())
        assertFailsWith<javax.crypto.AEADBadTagException> { cipher.doFinal(decoder.decode(sealed.password)) }
    }

    @Test
    fun statusWithoutLockScreenDecodesAsNull() {
        val json = """{"hostname":"MY-PC","ip":"192.168.1.14","mac":"0A:1B:2C:3D:4E:5F",""" +
            """"uptimeSeconds":1,"agentVersion":"1","serverTimeMs":0}"""
        assertNull(WakeJson.decodeFromString(StatusResponse.serializer(), json).lockScreen)
    }

    @Test
    fun resultWireNames() {
        val json = WakeJson.encodeToString(
            UnlockResponse.serializer(),
            UnlockResponse(UnlockResult.WRONG_PASSWORD, 3221225581),
        )
        assertTrue(json.contains("\"wrong_password\""), json)
    }
}
