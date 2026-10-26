package dev.jano.wake.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test
    fun actionSerializesLowercase() {
        assertEquals(
            """{"action":"shutdown"}""",
            WakeJson.encodeToString(CommandRequest.serializer(), CommandRequest(Action.SHUTDOWN)),
        )
    }

    @Test
    fun statusRoundTrips() {
        val plain = StatusResponse(
            hostname = "MY-PC",
            ip = "192.168.1.14",
            mac = "0A:1B:2C:3D:4E:5F",
            uptimeSeconds = 8040,
            agentVersion = "1.0",
            serverTimeMs = 1_793_000_000_000,
        )
        val withPending = plain.copy(pending = PendingCommand("c1", Action.REBOOT, 1_793_000_003_000))

        for (original in listOf(plain, withPending)) {
            val json = WakeJson.encodeToString(StatusResponse.serializer(), original)
            assertEquals(original, WakeJson.decodeFromString(StatusResponse.serializer(), json))
        }
    }

    @Test
    fun unknownKeysIgnored() {
        val json = """{"hostname":"MY-PC","ip":"192.168.1.14","mac":"0A:1B:2C:3D:4E:5F",""" +
            """"httpPort":47613,"agentVersion":"1.0","extra":true}"""
        assertEquals(
            DiscoveryReply("MY-PC", "192.168.1.14", "0A:1B:2C:3D:4E:5F", 47613, "1.0"),
            WakeJson.decodeFromString(DiscoveryReply.serializer(), json),
        )
    }
}
