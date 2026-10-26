package dev.jano.wake.net

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HostProbeTest {
    @Test
    fun openPortIsUp(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            assertIs<ProbeResult.Up>(probeHost("127.0.0.1", listOf(server.localPort)))
        }
    }

    @Test
    fun refusedPortIsUp(): Unit = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertIs<ProbeResult.Up>(probeHost("127.0.0.1", listOf(port)))
    }

    @Test
    fun blackholeIsDown(): Unit = runBlocking {
        assertEquals(ProbeResult.Down, probeHost("10.255.255.1", listOf(47613, 135), timeoutMs = 300))
    }
}
