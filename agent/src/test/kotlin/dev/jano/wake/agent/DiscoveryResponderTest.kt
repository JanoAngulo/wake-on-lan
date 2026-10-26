package dev.jano.wake.agent

import dev.jano.wake.protocol.DISCOVERY_PROBE
import dev.jano.wake.protocol.DiscoveryReply
import dev.jano.wake.protocol.WakeJson
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiscoveryResponderTest {
    private val reply = DiscoveryReply("MY-PC", "192.168.1.14", "0A:1B:2C:3D:4E:5F", 47613, "1.0")
    private val responder = DiscoveryResponder(port = 0, lanPrefix = "192.168.1.0" to 24, reply = { reply })

    @Test
    fun probeFromLanGetsReply() {
        val bytes = assertNotNull(responder.handle(DISCOVERY_PROBE.toByteArray(), InetAddress.getByName("192.168.1.23")))
        assertEquals(reply, WakeJson.decodeFromString(DiscoveryReply.serializer(), bytes.decodeToString()))
    }

    @Test
    fun probeFromOutsideIgnored() {
        assertNull(responder.handle(DISCOVERY_PROBE.toByteArray(), InetAddress.getByName("26.1.2.3")))
    }

    @Test
    fun wrongPayloadIgnored() {
        assertNull(responder.handle("HELLO".toByteArray(), InetAddress.getByName("192.168.1.23")))
    }
}
