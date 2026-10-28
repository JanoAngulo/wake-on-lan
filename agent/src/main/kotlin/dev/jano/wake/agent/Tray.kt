package dev.jano.wake.agent

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.MouseInfo
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Arc2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/** System tray icon: a click opens the menu (status, pairing, log, quit). */
class Tray(
    hostname: String,
    private val onPair: () -> String,
    private val isPairing: () -> Boolean,
    onOpenLog: () -> Unit,
    onQuit: () -> Unit,
) {
    private val menu = TrayMenu(hostname, { showPairingWindow(onPair()) }, onOpenLog, onQuit)
    private var icon: TrayIcon? = null

    fun show() {
        if (!SystemTray.isSupported()) return
        icon = TrayIcon(powerGlyph(), "Wake agent").apply {
            isImageAutoSize = true
            // Left or right click opens the menu. The pointer position is in the same (scaled) coordinates
            // as windows, which the event's own point isn't on every Windows display setup.
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 || e.isPopupTrigger) {
                        menu.showAt(MouseInfo.getPointerInfo()?.location ?: e.locationOnScreen)
                    }
                }
            })
        }
        SystemTray.getSystemTray().add(icon)
    }

    fun setPaired(paired: Boolean) {
        menu.paired = paired
        icon?.toolTip = "Wake agent: ${pairingStatus(paired).lowercase()}"
    }

    fun setAddress(ip: String?) {
        menu.address = ip
    }

    fun remove() {
        menu.close()
        icon?.let { SystemTray.getSystemTray().remove(it) }
    }

    private fun showPairingWindow(code: String) = SwingUtilities.invokeLater {
        val background = Color(0x0F, 0x17, 0x28)
        val text = Color(0xE8, 0xEE, 0xFA)
        val frame = JFrame("Pair phone")
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            this.background = background
            border = BorderFactory.createEmptyBorder(28, 40, 28, 40)
            add(label("Enter this code on your phone", 16f, Font.PLAIN, text))
            add(label(code.chunked(3).joinToString(" "), 56f, Font.BOLD, text))
            add(label("Waiting for phone…", 14f, Font.PLAIN, Color(0xA3, 0xB1, 0xCC)))
        }
        frame.contentPane = panel
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isAlwaysOnTop = true
        frame.isVisible = true
        Timer(500) { event ->
            if (!isPairing()) {
                (event.source as Timer).stop()
                frame.dispose()
            }
        }.start()
    }

    private fun label(value: String, size: Float, style: Int, color: Color) = JLabel(value).apply {
        font = uiFont(style, size)
        foreground = color
        alignmentX = 0.5f
        border = BorderFactory.createEmptyBorder(6, 0, 6, 0)
    }

    private fun powerGlyph(): BufferedImage {
        val size = 32
        return BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).apply {
            createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                color = Color(0x5B, 0xD6, 0x9A)
                stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                draw(Arc2D.Double(5.0, 6.0, 22.0, 22.0, 120.0, 300.0, Arc2D.OPEN))
                draw(Line2D.Double(16.0, 3.0, 16.0, 15.0))
                dispose()
            }
        }
    }
}
