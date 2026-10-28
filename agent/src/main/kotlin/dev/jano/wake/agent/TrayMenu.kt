package dev.jano.wake.agent

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.Arc2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

// The phone app's Dusk colors, so the PC side looks like the same product.
internal object TrayColors {
    val Background = Color(0x0F, 0x17, 0x28)
    val Surface = Color(0x18, 0x22, 0x3A)
    val Outline = Color(0x26, 0x34, 0x5A)
    val Text = Color(0xE8, 0xEE, 0xFA)
    val Muted = Color(0xA3, 0xB1, 0xCC)
    val Success = Color(0x5B, 0xD6, 0x9A)
    val Warning = Color(0xF5, 0xC2, 0x6B)
    val Danger = Color(0xFF, 0x9A, 0x9A)
}

/** Segoe UI is on every Windows 10/11 PC and has the glyphs (like "…") the AWT default font lacked. */
internal fun uiFont(style: Int, size: Float): Font = Font("Segoe UI", style, 1).deriveFont(style, size)

internal fun pairingStatus(paired: Boolean) = if (paired) "Paired with phone" else "Not paired yet"

internal fun networkStatus(ip: String?) = if (ip != null) "Listening on $ip" else "No network"

/**
 * Where the menu goes: beside the tray icon, on the side away from the taskbar, inside the usable screen
 * area. [click] is where the tray icon was clicked; [usable] is the screen minus the taskbar.
 */
internal fun popupLocation(click: Point, size: Dimension, usable: Rectangle, gap: Int = 8): Point {
    val x = (click.x - size.width).coerceIn(usable.x, usable.x + usable.width - size.width)
    val belowMiddle = click.y > usable.y + usable.height / 2
    val y = if (belowMiddle) minOf(click.y, usable.y + usable.height - gap) - size.height else maxOf(click.y, usable.y + gap)
    return Point(x, y.coerceIn(usable.y, usable.y + usable.height - size.height))
}

/** The tray's menu: a small dark panel with the agent's status and three actions. */
internal class TrayMenu(
    private val hostname: String,
    private val onPair: () -> Unit,
    private val onOpenLog: () -> Unit,
    private val onQuit: () -> Unit,
) {
    @Volatile var paired = false
    @Volatile var address: String? = null
    private var window: JDialog? = null

    fun showAt(click: Point) = SwingUtilities.invokeLater {
        window?.dispose()
        val dialog = JDialog().apply {
            isUndecorated = true
            type = Window.Type.POPUP
            isAlwaysOnTop = true
            contentPane = content(::close)
            pack()
            shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), 16.0, 16.0)
            location = popupLocation(click, size, usableScreen(click))
            // Close when the user clicks anywhere else or presses Esc, like a normal menu.
            addWindowFocusListener(object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent) = close()
            })
            rootPane.registerKeyboardAction({ close() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        }
        window = dialog
        dialog.isVisible = true
        dialog.toFront()
        dialog.requestFocus()
    }

    fun close() = SwingUtilities.invokeLater {
        window?.dispose()
        window = null
    }

    /** The panel itself, also used to render a preview without showing a window. */
    fun content(dismiss: () -> Unit): JPanel = RoundedPanel().apply {
        layout = BorderLayout()
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        add(header(), BorderLayout.NORTH)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(divider())
            add(row("Pair a phone…", "Shows a 6-digit code to type in the app", TrayColors.Text) { dismiss(); onPair() })
            add(row("Open log folder", null, TrayColors.Text) { dismiss(); onOpenLog() })
            add(divider())
            add(row("Quit Wake agent", null, TrayColors.Danger) { dismiss(); onQuit() })
        }, BorderLayout.CENTER)
        preferredSize = Dimension(300, preferredSize.height)
    }

    private fun header() = JPanel(BorderLayout(12, 0)).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        add(GlyphBadge(), BorderLayout.WEST)
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(text("Wake agent", uiFont(Font.BOLD, 15f), TrayColors.Text))
            add(text(hostname, uiFont(Font.PLAIN, 12f), TrayColors.Muted))
            add(Box.createVerticalStrut(8))
            add(statusLine(pairingStatus(paired), if (paired) TrayColors.Success else TrayColors.Warning))
            add(Box.createVerticalStrut(2))
            add(statusLine(networkStatus(address), if (address != null) TrayColors.Success else TrayColors.Warning))
        }, BorderLayout.CENTER)
    }

    private fun statusLine(value: String, dot: Color) = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = 0f
        add(Dot(dot))
        add(Box.createHorizontalStrut(8))
        add(text(value, uiFont(Font.PLAIN, 12.5f), TrayColors.Text))
    }

    private fun row(title: String, detail: String?, color: Color, action: () -> Unit): JComponent {
        val body = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(text(title, uiFont(Font.PLAIN, 13.5f), color))
            if (detail != null) add(text(detail, uiFont(Font.PLAIN, 11.5f), TrayColors.Muted))
        }
        return HoverRow().apply {
            layout = BorderLayout()
            border = BorderFactory.createEmptyBorder(9, 12, 9, 12)
            add(body, BorderLayout.CENTER)
            alignmentX = 0f
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hovered = false; repaint() }
                override fun mouseClicked(e: MouseEvent) { if (SwingUtilities.isLeftMouseButton(e)) action() }
            })
        }
    }

    private fun divider() = object : JComponent() {
        init {
            alignmentX = 0f
            preferredSize = Dimension(0, 9)
            maximumSize = Dimension(Int.MAX_VALUE, 9)
        }

        override fun paintComponent(g: Graphics) {
            g.color = TrayColors.Outline
            g.fillRect(8, 4, width - 16, 1)
        }
    }

    private fun text(value: String, font: Font, color: Color) = JLabel(value).apply {
        this.font = font
        foreground = color
        alignmentX = 0f
    }

    private fun usableScreen(point: Point): Rectangle {
        val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .map { it.defaultConfiguration }
            .firstOrNull { it.bounds.contains(point) }
            ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = config.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return Rectangle(
            bounds.x + insets.left,
            bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
    }

    /** Dark rounded card with a 1 px outline. */
    private class RoundedPanel : JPanel() {
        init { isOpaque = false }

        override fun paintComponent(g: Graphics) {
            val g2 = (g.create() as Graphics2D).smooth()
            g2.color = TrayColors.Background
            g2.fillRoundRect(0, 0, width, height, 16, 16)
            g2.color = TrayColors.Outline
            g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
            g2.dispose()
        }
    }

    private class HoverRow : JPanel() {
        var hovered = false

        init { isOpaque = false }

        override fun paintComponent(g: Graphics) {
            if (!hovered) return
            val g2 = (g.create() as Graphics2D).smooth()
            g2.color = TrayColors.Surface
            g2.fillRoundRect(0, 0, width, height, 10, 10)
            g2.dispose()
        }
    }

    private class Dot(private val color: Color) : JComponent() {
        init {
            preferredSize = Dimension(8, 8)
            maximumSize = Dimension(8, 8)
            alignmentY = 0.5f
        }

        override fun paintComponent(g: Graphics) {
            val g2 = (g.create() as Graphics2D).smooth()
            g2.color = color
            g2.fillOval(0, 0, 8, 8)
            g2.dispose()
        }
    }

    /** The power glyph on a soft green disc, as in the app's "on" state. */
    private class GlyphBadge : JComponent() {
        init { preferredSize = Dimension(40, 40) }

        override fun paintComponent(g: Graphics) {
            val g2 = (g.create() as Graphics2D).smooth()
            g2.color = Color(0x5B, 0xD6, 0x9A, 0x33)
            g2.fillOval(0, 0, 40, 40)
            g2.color = TrayColors.Success
            g2.stroke = BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(Arc2D.Double(11.0, 12.0, 18.0, 18.0, 120.0, 300.0, Arc2D.OPEN))
            g2.draw(Line2D.Double(20.0, 9.5, 20.0, 19.0))
            g2.dispose()
        }
    }
}

private fun Graphics2D.smooth(): Graphics2D = apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}
