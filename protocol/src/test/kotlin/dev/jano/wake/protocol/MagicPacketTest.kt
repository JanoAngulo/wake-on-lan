package dev.jano.wake.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class MagicPacketTest {
    @Test
    fun magicPacketLayout() {
        val packet = magicPacket(MacAddress("0A:1B:2C:3D:4E:5F"))
        val unsigned = packet.map { it.toInt() and 0xFF }

        assertEquals(102, packet.size)
        assertEquals(List(6) { 0xFF }, unsigned.subList(0, 6))
        val mac = listOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F)
        for (k in 0 until 16) {
            assertEquals(mac, unsigned.subList(6 + 6 * k, 12 + 6 * k), "repetition $k")
        }
    }
}
