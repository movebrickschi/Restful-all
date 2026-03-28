package io.github.movebrickschi.restfulall.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import io.github.movebrickschi.restfulall.service.RouteService
import java.awt.datatransfer.StringSelection

class CopyRoutePathAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val routeService = RouteService.getInstance(project)
        val caretLine = editor.caretModel.logicalPosition.line

        val route = routeService.findRouteAt(file, caretLine)
        if (route != null) {
            CopyPasteManager.getInstance().setContents(StringSelection(route.displayPath))
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            statusBar?.info = "已复制路由: ${route.displayPath}"
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)

        if (project == null || editor == null || file == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val routeService = RouteService.getInstance(project)
        val caretLine = editor.caretModel.logicalPosition.line
        e.presentation.isEnabled = routeService.findRouteAt(file, caretLine) != null
    }
}
