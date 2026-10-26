package dev.jano.wake.agent

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
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

/** System tray icon: shows pairing status and opens the pairing-code window. */
class Tray(
    private val onPair: () -> String,
    private val isPairing: () -> Boolean,
    private val onQuit: () -> Unit,
) {
    private val status = MenuItem("Not paired").apply { isEnabled = false }
    private var icon: TrayIcon? = null

    fun show() {
        if (!SystemTray.isSupported()) return
        val menu = PopupMenu().apply {
            add(status)
            addSeparator()
            add(MenuItem("Pair phone…").apply { addActionListener { showPairingWindow(onPair()) } })
            add(MenuItem("Quit").apply { addActionListener { onQuit() } })
        }
        icon = TrayIcon(powerGlyph(), "Wake agent", menu).apply { isImageAutoSize = true }
        SystemTray.getSystemTray().add(icon)
    }

    fun setPaired(paired: Boolean) {
        status.label = if (paired) "Paired" else "Not paired"
    }

    fun remove() {
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
        font = font.deriveFont(style, size)
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
