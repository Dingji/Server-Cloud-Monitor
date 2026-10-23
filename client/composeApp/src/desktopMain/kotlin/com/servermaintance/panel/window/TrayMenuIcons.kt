package com.servermaintance.panel.window

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.ImageIcon

/** 16×16 menu icons (same stroke language as panel [Icons], 24×24 viewBox). */
internal object TrayMenuIcons {
    private const val VIEW = 24f
    private const val SIZE = 16

    fun showWindowIcon(): ImageIcon = ImageIcon(raster(Color(0x43, 0x70, 0xF0), ::drawBrowser))

    fun quitIcon(): ImageIcon = ImageIcon(raster(Color(0xCC, 0x44, 0x44), ::drawLogout))

    private fun raster(ink: Color, draw: (Graphics2D) -> Unit): BufferedImage {
        val img = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val s = SIZE.toDouble() / VIEW.toDouble()
        g.scale(s, s)
        g.color = ink
        g.stroke = BasicStroke(
            1.7f,
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
        )
        draw(g)
        g.dispose()
        return img
    }

    /** [Icons] `browser` — open / show main window. */
    private fun drawBrowser(g: Graphics2D) {
        g.draw(RoundRectangle2D.Float(3f, 4.5f, 18f, 15f, 4f, 4f))
        g.drawLine(3, 9, 21, 9)
        g.fillOval(5, 6, 2, 2)
        g.fillOval(7, 6, 2, 2)
    }

    /** [Icons] `logout` — exit application. */
    private fun drawLogout(g: Graphics2D) {
        g.draw(RoundRectangle2D.Float(5f, 6f, 10f, 12f, 3f, 3f))
        g.drawLine(15, 12, 21, 12)
        g.drawLine(18, 8, 21, 12)
        g.drawLine(18, 16, 21, 12)
    }
}
