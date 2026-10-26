package dev.jano.wake.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingModeTest {
    private var now = 0L
    private val pairing = PairingMode(clock = { now }, ttlMs = 120_000)

    @Test
    fun codeIsSixDigits() {
        repeat(50) {
            val code = pairing.begin()
            assertTrue(code.matches(Regex("^\\d{6}$")), code)
        }
    }

    @Test
    fun codeExpiresAfterTtl() {
        val code = pairing.begin()
        now += 119_999
        assertEquals(code, pairing.activeCode())
        now += 1
        assertNull(pairing.activeCode())
    }

    @Test
    fun endClearsCode() {
        pairing.begin()
        pairing.end()
        assertNull(pairing.activeCode())
    }
}
