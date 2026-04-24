package io.github.movebrickschi.restfulall.report

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.stats.RouteStatsService
import io.github.movebrickschi.restfulall.theme.ThemeService
import java.awt.*
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.filechooser.FileNameExtensionFilter
import javax.imageio.ImageIO

/**
 * 年度使用报告面板。
 *
 * 布局思路：
 * - 纯 Swing + BoxLayout，用多张"卡片"垂直排列（便于导出为长图）
 * - 卡片背景色取自当前 Theme.accentColor 的低透明度版本，保持整体视觉一致
 * - 内容全部来自 [RouteStatsService.getYearStats]，不做额外 I/O
 *
 * 导出：[exportAsPng] 会把内容区（不含按钮）截图到 PNG。
 */
class YearReportPanel(
    private val project: Project,
    private val year: Int = LocalDate.now().year,
) : JPanel(BorderLayout()) {

    private val stats = RouteStatsService.getInstance(project).getYearStats(year)
    private val content = JPanel()
    private val theme = ThemeService.getInstance().current()

    init {
        border = JBUI.Borders.empty(16)
        content.layout = BoxLayout(content, BoxLayout.Y_AXIS)
        content.background = JBUI.CurrentTheme.ToolWindow.background()
        buildCards()

        add(JBScrollPane(content), BorderLayout.CENTER)
        add(buildToolbar(), BorderLayout.SOUTH)
    }

    private fun buildCards() {
        content.add(titleCard())
        content.add(Box.createVerticalStrut(12))
        content.add(summaryCard())
        content.add(Box.createVerticalStrut(12))
        content.add(topRoutesCard())
        content.add(Box.createVerticalStrut(12))
        content.add(monthlyCard())
        content.add(Box.createVerticalStrut(12))
        content.add(activeHourCard())
        content.add(Box.createVerticalStrut(12))
        content.add(milestonesCard())
        content.add(Box.createVerticalStrut(12))
        content.add(footerCard())
    }

    private fun titleCard(): JPanel = card {
        val title = JBLabel(MyMessageBundle.message("report.title", stats.year)).apply {
            font = font.deriveFont(Font.BOLD, 26f)
            foreground = theme.accentColor
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val subtitle = JBLabel(MyMessageBundle.message("report.subtitle")).apply {
            font = font.deriveFont(Font.ITALIC, 12f)
            foreground = theme.mutedColor
            alignmentX = Component.LEFT_ALIGNMENT
        }
        it.add(title)
        it.add(Box.createVerticalStrut(4))
        it.add(subtitle)
    }

    private fun summaryCard(): JPanel = card {
        it.add(heading(MyMessageBundle.message("report.section.summary")))
        it.add(row(MyMessageBundle.message("report.total_navigations"), stats.totalNavigations.toString()))
        it.add(row(MyMessageBundle.message("report.unique_routes"), stats.uniqueRoutes.toString()))
        it.add(row(MyMessageBundle.message("report.favorite_count"), stats.favoriteCount.toString()))
        it.add(row(MyMessageBundle.message("report.noted_count"), stats.notedCount.toString()))
        if (stats.firstUseTimestamp > 0) {
            val firstUse = Instant.ofEpochMilli(stats.firstUseTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            it.add(row(MyMessageBundle.message("report.first_use"), firstUse))
        }
    }

    private fun topRoutesCard(): JPanel = card {
        it.add(heading(MyMessageBundle.message("report.section.top_routes")))
        if (stats.topRoutes.isEmpty()) {
            it.add(JBLabel(MyMessageBundle.message("report.empty")).apply {
                foreground = theme.mutedColor
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            for ((index, top) in stats.topRoutes.withIndex()) {
                val line = "%2d. %-52s  × %d".format(index + 1, truncate(top.routeKey, 52), top.count)
                it.add(JBLabel(line).apply {
                    font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                    foreground = theme.fileColor
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }
    }

    private fun monthlyCard(): JPanel = card {
        it.add(heading(MyMessageBundle.message("report.section.monthly")))
        val max = (stats.perMonth.maxOrNull() ?: 0).coerceAtLeast(1)
        val barPanel = object : JPanel() {
            init {
                alignmentX = Component.LEFT_ALIGNMENT
                preferredSize = Dimension(600, 120)
                maximumSize = Dimension(Int.MAX_VALUE, 120)
                isOpaque = false
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val w = width
                val h = height - 20
                val slotWidth = w / 12
                for (i in 0 until 12) {
                    val value = stats.perMonth[i]
                    val barHeight = (h.toDouble() * value / max).toInt().coerceAtLeast(2)
                    val x = i * slotWidth + 4
                    val y = h - barHeight
                    g2.color = theme.accentColor
                    g2.fillRoundRect(x, y, slotWidth - 8, barHeight, 6, 6)
                    g2.color = theme.mutedColor
                    g2.font = g2.font.deriveFont(10f)
                    g2.drawString("${i + 1}", x + slotWidth / 2 - 6, h + 14)
                }
            }
        }
        it.add(barPanel)
    }

    private fun activeHourCard(): JPanel = card {
        it.add(heading(MyMessageBundle.message("report.section.active_hour")))
        val text = if (stats.mostActiveHour < 0) {
            MyMessageBundle.message("report.empty")
        } else {
            MyMessageBundle.message("report.active_hour_value", stats.mostActiveHour)
        }
        it.add(JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 16f)
            foreground = theme.accentColor
            alignmentX = Component.LEFT_ALIGNMENT
        })
    }

    private fun milestonesCard(): JPanel = card {
        it.add(heading(MyMessageBundle.message("report.section.milestones")))
        val badges = BadgeCalculator.compute(stats)
        if (badges.isEmpty()) {
            it.add(JBLabel(MyMessageBundle.message("report.no_badges")).apply {
                foreground = theme.mutedColor
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            for (badge in badges) {
                it.add(JBLabel("★  ${badge.title} — ${badge.description}").apply {
                    foreground = theme.accentColor
                    alignmentX = Component.LEFT_ALIGNMENT
                })
            }
        }
    }

    private fun footerCard(): JPanel = card {
        it.add(JBLabel(MyMessageBundle.message("report.footer")).apply {
            font = font.deriveFont(Font.ITALIC, 11f)
            foreground = theme.mutedColor
            alignmentX = Component.LEFT_ALIGNMENT
        })
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.RIGHT))
        val exportBtn = JButton(MyMessageBundle.message("report.action.export"))
        exportBtn.addActionListener { exportAsPng() }
        bar.add(exportBtn)
        return bar
    }

    private fun exportAsPng() {
        val chooser = JFileChooser().apply {
            dialogTitle = MyMessageBundle.message("report.export.title")
            fileFilter = FileNameExtensionFilter("PNG", "png")
            selectedFile = java.io.File("RestfulAll-Report-${stats.year}.png")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val target = chooser.selectedFile.let {
            if (it.name.endsWith(".png", ignoreCase = true)) it
            else java.io.File(it.absolutePath + ".png")
        }
        try {
            content.size = content.preferredSize
            content.doLayout()
            val image = BufferedImage(content.width.coerceAtLeast(600), content.height.coerceAtLeast(400), BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.color = content.background ?: Color(0x2B2B2B)
            g.fillRect(0, 0, image.width, image.height)
            content.paint(g)
            g.dispose()
            ImageIO.write(image, "png", target)
            JOptionPane.showMessageDialog(
                this,
                MyMessageBundle.message("report.export.success", target.absolutePath),
                MyMessageBundle.message("report.export.title"),
                JOptionPane.INFORMATION_MESSAGE,
            )
        } catch (e: Throwable) {
            JOptionPane.showMessageDialog(
                this,
                MyMessageBundle.message("report.export.failure", e.message ?: e.javaClass.simpleName),
                MyMessageBundle.message("report.export.title"),
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    private fun card(block: (JPanel) -> Unit): JPanel {
        val c = JPanel()
        c.layout = BoxLayout(c, BoxLayout.Y_AXIS)
        c.alignmentX = Component.LEFT_ALIGNMENT
        c.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(theme.mutedColor, 1, true),
            JBUI.Borders.empty(12),
        )
        c.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        block(c)
        return c
    }

    private fun heading(text: String): JBLabel = JBLabel(text).apply {
        font = font.deriveFont(Font.BOLD, 14f)
        foreground = theme.accentColor
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyBottom(8)
    }

    private fun row(label: String, value: String): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2))
        panel.isOpaque = false
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(JBLabel("$label：").apply {
            foreground = theme.mutedColor
        })
        panel.add(JBLabel(value).apply {
            foreground = theme.fileColor
            font = font.deriveFont(Font.BOLD)
        })
        return panel
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"
}

/** 简单成就计算：阈值触发一下标题 + 描述。 */
private object BadgeCalculator {
    data class Badge(val title: String, val description: String)

    fun compute(stats: RouteStatsService.YearStats): List<Badge> {
        val list = mutableListOf<Badge>()
        if (stats.totalNavigations >= 1) list += Badge(
            MyMessageBundle.message("report.badge.novice.title"),
            MyMessageBundle.message("report.badge.novice.desc"),
        )
        if (stats.totalNavigations >= 100) list += Badge(
            MyMessageBundle.message("report.badge.explorer.title"),
            MyMessageBundle.message("report.badge.explorer.desc"),
        )
        if (stats.totalNavigations >= 1000) list += Badge(
            MyMessageBundle.message("report.badge.master.title"),
            MyMessageBundle.message("report.badge.master.desc"),
        )
        if (stats.mostActiveHour in 0..5) list += Badge(
            MyMessageBundle.message("report.badge.night_owl.title"),
            MyMessageBundle.message("report.badge.night_owl.desc"),
        )
        if (stats.favoriteCount >= 5) list += Badge(
            MyMessageBundle.message("report.badge.curator.title"),
            MyMessageBundle.message("report.badge.curator.desc"),
        )
        return list
    }
}
