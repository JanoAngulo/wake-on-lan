package dev.jano.wake.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanInterfaceTest {
    private val ethernet = NicInfo("eth0", "Realtek PCIe GbE Family Controller", "192.168.1.14", 24, "0A:1B:2C:3D:4E:5F", up = true)
    private val radmin = NicInfo("eth1", "Radmin VPN", "26.10.20.30", 8, "02:50:00:00:00:01", up = true)
    private val vEthernet = NicInfo("eth2", "vEthernet (WSL)", "172.24.0.1", 20, "00:15:5D:00:00:01", up = true)

    @Test
    fun picksEthernetOnGatewaySubnet() {
        assertEquals(ethernet, pickLanInterface(listOf(radmin, vEthernet, ethernet), "192.168.1.1"))
    }

    @Test
    fun radminIsVirtual() {
        assertTrue(isVirtual(radmin))
        assertTrue(isVirtual(vEthernet))
    }

    @Test
    fun noGatewayFallsBackToPrivateNonVirtual() {
        assertEquals(ethernet, pickLanInterface(listOf(radmin, vEthernet, ethernet), null))
    }

    // Host with a Hyper-V External switch: the LAN runs on the vEthernet adapter, which keeps the NIC's MAC.
    private val externalSwitch = NicInfo("eth3", "Hyper-V Virtual Ethernet Adapter #2", "192.168.1.14", 24, "0A:1B:2C:3D:4E:5F", up = true)
    private val defaultSwitch = NicInfo("eth4", "Hyper-V Virtual Ethernet Adapter", "172.20.0.1", 20, "00:15:5D:00:00:02", up = true)
    // Inside a Hyper-V VM: the VM's only network card.
    private val guestNic = NicInfo("eth5", "Microsoft Hyper-V Network Adapter", "192.168.1.50", 24, "00:15:5D:00:00:03", up = true)

    @Test
    fun picksHyperVSwitchAdapterOnGatewaySubnet() {
        assertEquals(externalSwitch, pickLanInterface(listOf(radmin, defaultSwitch, externalSwitch), "192.168.1.1"))
    }

    @Test
    fun picksHyperVGuestAdapterOnGatewaySubnet() {
        assertEquals(guestNic, pickLanInterface(listOf(guestNic), "192.168.1.1"))
    }

    @Test
    fun prefersPhysicalAdapterOverHyperVOnSameSubnet() {
        assertEquals(ethernet, pickLanInterface(listOf(externalSwitch, ethernet), "192.168.1.1"))
    }

    @Test
    fun hyperVAdaptersNeverPickedWithoutGateway() {
        assertNull(pickLanInterface(listOf(defaultSwitch, externalSwitch), null))
    }

    @Test
    fun vpnOnGatewaySubnetStaysExcluded() {
        assertNull(pickLanInterface(listOf(radmin, defaultSwitch), "26.0.0.1"))
    }

    @Test
    fun nothingSuitableReturnsNull() {
        val down = ethernet.copy(up = false)
        assertNull(pickLanInterface(listOf(radmin, vEthernet, down), "192.168.1.1"))
    }
}
