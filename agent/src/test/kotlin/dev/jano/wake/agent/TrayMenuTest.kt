package dev.jano.wake.agent

import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class TrayMenuTest {
    // 1920x1080 screen with a 48 px taskbar at the bottom.
    private val usable = Rectangle(0, 0, 1920, 1032)
    private val panel = Dimension(300, 240)

    @Test
    fun opensAboveAndLeftOfTrayIcon() {
        assertEquals(Point(1580, 784), popupLocation(Point(1880, 1050), panel, usable, gap = 8))
    }

    @Test
    fun staysOnScreenNearLeftEdge() {
        assertEquals(Point(0, 784), popupLocation(Point(100, 1050), panel, usable, gap = 8))
    }

    @Test
    fun opensBelowWhenTaskbarIsAtTheTop() {
        val topBar = Rectangle(0, 48, 1920, 1032)
        assertEquals(Point(1580, 56), popupLocation(Point(1880, 20), panel, topBar, gap = 8))
    }

    @Test
    fun statusTexts() {
        assertEquals("Paired with phone", pairingStatus(paired = true))
        assertEquals("Not paired yet", pairingStatus(paired = false))
        assertEquals("Listening on 192.168.1.14", networkStatus("192.168.1.14"))
        assertEquals("No network", networkStatus(null))
    }
}
