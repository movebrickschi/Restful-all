package io.github.movebrickschi.restfulall.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.license.LicenseManager
import io.github.movebrickschi.restfulall.theme.Theme
import io.github.movebrickschi.restfulall.theme.ThemeService

/**
 * 切换主题弹框（Pro）。用列表弹框让用户选 10 套内置主题之一。
 */
class SwitchThemeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!LicenseManager.requirePro(project, "theme")) return

        val themes = Theme.BUILTIN
        val step: ListPopupStep<Theme> = object : BaseListPopupStep<Theme>(
            MyMessageBundle.message("theme.switch.title"),
            themes,
        ) {
            override fun getTextFor(value: Theme): String = value.displayName

            override fun onChosen(selectedValue: Theme, finalChoice: Boolean): PopupStep<*>? {
                ThemeService.getInstance().setPreset(project, selectedValue.id)
                return FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).showCenteredInCurrentWindow(project)
    }
}
