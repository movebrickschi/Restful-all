package io.github.movebrickschi.restfulall.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.license.LicenseManager
import io.github.movebrickschi.restfulall.report.YearReportPanel
import io.github.movebrickschi.restfulall.theme.ThemeService
import java.awt.Dimension
import javax.swing.JComponent

/**
 * "查看年度报告" 菜单项。弹出一个独立对话框展示报告面板。
 * 不走 ToolWindow，避免常驻占据 UI。
 */
class ShowYearReportAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        showFor(project)
    }

    companion object {
        /** 供通知 / 其他模块复用的入口。 */
        fun showFor(project: com.intellij.openapi.project.Project) {
            if (!LicenseManager.requirePro(project, "year_report")) return
            ThemeService.getInstance().syncFrom(project)
            val dialog = object : DialogWrapper(project) {
                private val panel = YearReportPanel(project)

                init {
                    title = MyMessageBundle.message("report.dialog.title")
                    init()
                    setSize(720, 760)
                    isResizable = true
                }

                override fun createCenterPanel(): JComponent {
                    panel.preferredSize = Dimension(680, 700)
                    return panel
                }

                override fun createActions(): Array<javax.swing.Action> = arrayOf(okAction)
            }
            dialog.show()
        }
    }
}
