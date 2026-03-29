package com.clauditor.util

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.plaf.basic.BasicProgressBarUI

class RoundedProgressBarUI : BasicProgressBarUI() {
    override fun getPreferredSize(c: JComponent): Dimension = super.getPreferredSize(c)

    override fun paintDeterminate(g: Graphics, c: JComponent) {
        paintRounded(g, c)
    }

    override fun paintIndeterminate(g: Graphics, c: JComponent) {
        paintRounded(g, c)
    }

    private fun paintRounded(g: Graphics, c: JComponent) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val bar = c as JProgressBar
        val i = bar.insets
        val x = i.left.toDouble()
        val y = i.top.toDouble()
        val w = (c.width - i.left - i.right).toDouble()
        val h = (c.height - i.top - i.bottom).toDouble()
        val arc = h

        val trackColor = UIManager.getColor("ProgressBar.trackColor")
            ?: bar.background.darker()
        val fillColor = bar.foreground

        // Track
        g2.color = trackColor
        g2.fill(RoundRectangle2D.Double(x, y, w, h, arc, arc))

        // Fill
        val fillWidth = w * bar.percentComplete
        if (fillWidth > 0) {
            g2.color = fillColor
            g2.fill(RoundRectangle2D.Double(x, y, fillWidth, h, arc, arc))
        }

        // Text — draw twice with clipping for contrast over fill vs track
        if (bar.isStringPainted && bar.string != null) {
            val fm = g2.getFontMetrics(bar.font)
            val text = bar.string
            val textWidth = fm.stringWidth(text)
            val textX = i.left + ((w - textWidth) / 2).toInt()
            val textY = i.top + ((h - fm.height) / 2).toInt() + fm.ascent

            val fillEdge = x + fillWidth
            val oldClip = g2.clip

            // Text over filled area
            g2.clipRect(i.left, i.top, fillWidth.toInt(), h.toInt())
            g2.color = contrastColor(fillColor)
            g2.drawString(text, textX, textY)

            // Text over track area
            g2.clip = oldClip
            g2.clipRect(fillEdge.toInt(), i.top, (w - fillWidth).toInt() + 1, h.toInt())
            g2.color = contrastColor(trackColor)
            g2.drawString(text, textX, textY)

            g2.clip = oldClip
        }

        g2.dispose()
    }

    private fun contrastColor(bg: Color): Color {
        // Relative luminance (ITU-R BT.709)
        val lum = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) / 255.0
        return if (lum > 0.5) Color.BLACK else Color.WHITE
    }
}
