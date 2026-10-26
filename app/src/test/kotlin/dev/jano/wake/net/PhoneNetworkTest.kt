package dev.jano.wake.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneNetworkTest {
    @Test
    fun subnetOfMasksHostBits() {
        assertEquals("192.168.1.0/24", subnetOf("192.168.1.23", 24))
        assertEquals("10.0.0.0/8", subnetOf("10.1.2.3", 8))
    }

    @Test
    fun broadcastOfSetsHostBits() {
        assertEquals("192.168.1.255", broadcastOf("192.168.1.23", 24))
        assertEquals("10.255.255.255", broadcastOf("10.1.2.3", 8))
    }

    @Test
    fun isHomeNeedsWifiAndSameSubnet() {
        val home = PhoneNet(onWifi = true, ip = "192.168.1.23", prefixLength = 24)
        assertTrue(isHome(home, "192.168.1.0/24"))
        assertFalse(isHome(home.copy(onWifi = false), "192.168.1.0/24"))
        assertFalse(isHome(home.copy(ip = "10.0.0.42"), "192.168.1.0/24"))
        assertFalse(isHome(home.copy(ip = null, prefixLength = null), "192.168.1.0/24"))
    }

    @Test
    fun unknownDeviceSubnetTrustsWifi() {
        assertTrue(isHome(PhoneNet(true, "192.168.1.23", 24), null))
    }
}
