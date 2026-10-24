package com.servermaintance.panel.window

import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Window
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JMenuItem
import com.servermaintance.panel.i18n.tr
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Tray menu labels as Unicode escapes so they stay correct regardless of source file encoding on Windows builds. */
private const val TRAY_BALLOON_BODY =
    "\u5DF2\u9A7B\u7559\u5230\u7CFB\u7EDF\u6258\u76D8\uFF0C\u540E\u53F0\u5C06\u7EE7\u7EED\u63A5\u6536\u544A\u8B66\u3002"

internal class WindowsTrayHost(
    private val window: Window,
    private val onQuit: () -> Unit,
    var onTrayHidden: (Boolean) -> Unit = {},
) {
    private var trayIcon: TrayIcon? = null
    private var trayClickListener: MouseAdapter? = null

    val isTraySupported: Boolean
        get() = SystemTray.isSupported()

    fun install() {
        if (!isTraySupported || trayIcon != null) return
        runCatching {
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(trayImage(), "Cloud Monitor")
            icon.isImageAutoSize = true
            val clickListener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1 && e.clickCount >= 2) showMain()
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (!e.isPopupTrigger && e.button != MouseEvent.BUTTON3) return
                    // TrayIcon events rarely expose valid x/y; read the pointer on this thread before invokeLater.
                    val pointer = MouseInfo.getPointerInfo()?.location
                        ?: Point(e.xOnScreen, e.yOnScreen)
                    showTrayPopupAt(pointer)
                }
            }
            icon.addMouseListener(clickListener)
            trayClickListener = clickListener
            tray.add(icon)
            trayIcon = icon
        }
    }

    fun dispose() {
        val icon = trayIcon ?: return
        trayIcon = null
        trayClickListener?.let { icon.removeMouseListener(it) }
        trayClickListener = null
        runCatching { SystemTray.getSystemTray().remove(icon) }
    }

    fun hideToTray() {
        if (isTraySupported) {
            install()
            window.isVisible = false
            onTrayHidden(true)
            trayIcon?.displayMessage(
                "Cloud Monitor",
                TRAY_BALLOON_BODY,
                TrayIcon.MessageType.INFO,
            )
        } else {
            minimizeToTaskbar()
            onTrayHidden(false)
        }
    }

    fun showMain() {
        window.isVisible = true
        (window as? Frame)?.extendedState = Frame.NORMAL
        window.toFront()
        window.requestFocus()
        onTrayHidden(false)
    }

    fun minimizeToTaskbar() {
        when (window) {
            is Frame -> window.extendedState = Frame.ICONIFIED
            else -> window.isVisible = false
        }
    }

    fun notifyAlert(title: String, body: String, levelBad: Boolean) {
        install()
        val type = if (levelBad) TrayIcon.MessageType.ERROR else TrayIcon.MessageType.WARNING
        trayIcon?.displayMessage(title, body, type)
    }

    private fun quitFromTray() {
        dispose()
        onQuit()
    }

    private fun showTrayPopupAt(pointer: Point) {
        SwingUtilities.invokeLater {
            val popup = buildTrayPopup()
            val size = popup.preferredSize
            val screenOrigin = trayMenuScreenOrigin(pointer, size)
            val local = Point(screenOrigin)
            SwingUtilities.convertPointFromScreen(local, window)
            popup.show(window, local.x, local.y)
        }
    }

    private fun buildTrayPopup(): JPopupMenu {
        val font = trayMenuFont()
        val popup = JPopupMenu()
        popup.font = font
        val show = JMenuItem(tr("tray.show_main"), TrayMenuIcons.showWindowIcon())
        show.font = font
        show.iconTextGap = 8
        show.addActionListener { showMain() }
        val quit = JMenuItem(tr("tray.quit"), TrayMenuIcons.quitIcon())
        quit.font = font
        quit.iconTextGap = 8
        quit.addActionListener { quitFromTray() }
        popup.add(show)
        popup.addSeparator()
        popup.add(quit)
        return popup
    }

    /** Place menu at the pointer; prefer opening upward (typical for taskbar / tray). */
    private fun trayMenuScreenOrigin(pointer: Point, menuSize: Dimension): Point {
        val bounds: Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        var x = pointer.x
        var y = pointer.y - menuSize.height
        if (y < bounds.y) y = pointer.y
        if (x + menuSize.width > bounds.x + bounds.width) {
            x = bounds.x + bounds.width - menuSize.width
        }
        x = x.coerceIn(bounds.x, maxOf(bounds.x, bounds.x + bounds.width - menuSize.width))
        y = y.coerceIn(bounds.y, maxOf(bounds.y, bounds.y + bounds.height - menuSize.height))
        return Point(x, y)
    }

    private fun trayMenuFont(): Font =
        UIManager.getFont("MenuItem.font")
            ?: Font("Microsoft YaHei UI", Font.PLAIN, 12)

    private fun trayImage(): BufferedImage {
        val stream = WindowsTrayHost::class.java.getResourceAsStream("/cloud-monitor-tray.png")
        if (stream != null) {
            return ImageIO.read(stream)
        }
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics() as Graphics2D
        g.color = java.awt.Color(0x0C, 0x11, 0x17)
        g.fillRect(0, 0, size, size)
        g.color = java.awt.Color(0x43, 0x70, 0xF0)
        g.fillRect(0, 0, size, 2)
        g.dispose()
        return img
    }
}

internal fun isWindowsBackgroundWindow(window: Window, hiddenInTray: Boolean): Boolean {
    if (hiddenInTray || !window.isVisible) return true
    val frame = window as? Frame ?: return false
    return frame.extendedState and Frame.ICONIFIED != 0
}
