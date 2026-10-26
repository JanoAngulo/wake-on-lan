package dev.jano.wake.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsSystemTest {
    // Captured from MY-PC: Radmin VPN's default route is listed first but has a worse metric.
    private val routePrint = """
        ===========================================================================
        Interface List
         13...02 50 00 00 00 01 ......Famatech Radmin VPN Ethernet Adapter
          7...0a 1b 2c 3d 4e 5f ......Realtek PCIe GbE Family Controller
         15...00 1a 00 00 00 02 ......Bluetooth Device (Personal Area Network)
          1...........................Software Loopback Interface 1
        ===========================================================================

        IPv4 Route Table
        ===========================================================================
        Active Routes:
        Network Destination        Netmask          Gateway       Interface  Metric
                  0.0.0.0          0.0.0.0         26.0.0.1    26.10.20.30   9257
                  0.0.0.0          0.0.0.0    192.168.1.1   192.168.1.14     25
        ===========================================================================
        Persistent Routes:
          Network Address          Netmask  Gateway Address  Metric
                  0.0.0.0          0.0.0.0         26.0.0.1    9256
        ===========================================================================
    """.trimIndent()

    @Test
    fun parsesGatewayFromRoutePrint() {
        assertEquals("192.168.1.1", WindowsSystem.parseDefaultGateway(routePrint))
    }

    @Test
    fun noActiveDefaultRouteReturnsNull() {
        assertNull(WindowsSystem.parseDefaultGateway("IPv4 Route Table\nActive Routes:\nNone\n"))
    }
}
