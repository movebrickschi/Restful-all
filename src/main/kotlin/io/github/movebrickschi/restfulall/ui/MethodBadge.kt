package io.github.movebrickschi.restfulall.ui

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JLabel
import javax.swing.SwingConstants

class MethodBadge : JLabel() {
    private var badgeColor: Color = Color.GRAY

    init {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
        alignmentY = CENTER_ALIGNMENT
        font = font.deriveFont(Font.BOLD, 10.5f)
        border = JBUI.Borders.empty(0, 6)
        isOpaque = false
    }

    fun setMethod(text: String, color: Color) {
        this.text = text
        badgeColor = color
        foreground = color
        repaint()
    }

    fun applyFixedSize(width: Int, height: Int) {
        val size = Dimension(width, height)
        preferredSize = size
        minimumSize = size
        maximumSize = size
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(8)
            val stroke = JBUI.scale(1).toFloat()
            val x = stroke.toInt()
            val y = stroke.toInt()
            val w = width - JBUI.scale(1)
            val h = height - JBUI.scale(1)
            g2.color = Color(badgeColor.red, badgeColor.green, badgeColor.blue, 20)
            g2.fillRoundRect(x, y, w - x, h - y, arc, arc)
            g2.stroke = BasicStroke(stroke)
            g2.color = badgeColor
            g2.drawRoundRect(x, y, w - x, h - y, arc, arc)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}
