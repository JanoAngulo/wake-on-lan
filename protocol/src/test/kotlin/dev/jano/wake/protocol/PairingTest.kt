package dev.jano.wake.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PairingTest {
    private val phone = generateX25519()
    private val agent = generateX25519()
    private val phonePub = encodePublic(phone.public)
    private val agentPub = encodePublic(agent.public)
    private val t = transcript(phonePub, agentPub)

    @Test
    fun bothSidesDeriveSameSecret() {
        val onPhone = deriveSecret(phone.private, decodePublic(agentPub), t)
        val onAgent = deriveSecret(agent.private, decodePublic(phonePub), t)
        assertContentEquals(onPhone, onAgent)
    }

    @Test
    fun secretIs32Bytes() {
        assertEquals(32, deriveSecret(phone.private, agent.public, t).size)
    }

    @Test
    fun confirmTagDependsOnCode() {
        assertNotEquals(confirmTag("123456", "agent", t), confirmTag("123457", "agent", t))
    }

    @Test
    fun confirmTagDependsOnRole() {
        assertNotEquals(confirmTag("123456", "agent", t), confirmTag("123456", "phone", t))
    }

    @Test
    fun publicKeyRoundTrips() {
        assertContentEquals(phone.public.encoded, decodePublic(phonePub).encoded)
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = hkdfSha256(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            okm.joinToString("") { "%02x".format(it) },
        )
    }
}
