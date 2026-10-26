package dev.jano.wake.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkKindTest {
    @Test
    fun wiredAdapterIsEthernet() {
        assertEquals("Ethernet", linkKind("Realtek PCIe GbE Family Controller"))
    }

    @Test
    fun wirelessAdapterIsWifi() {
        assertEquals("Wi-Fi", linkKind("Intel(R) Wi-Fi 6E AX211 160MHz"))
        assertEquals("Wi-Fi", linkKind("Qualcomm Wireless Network Adapter"))
    }
}
