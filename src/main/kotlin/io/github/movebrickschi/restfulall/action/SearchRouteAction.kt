package io.github.movebrickschi.restfulall.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import io.github.movebrickschi.restfulall.service.RouteService
import io.github.movebrickschi.restfulall.ui.RouteSearchPopup

class SearchRouteAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val routeService = RouteService.getInstance(project)

        val selectedText = e.getData(CommonDataKeys.EDITOR)
            ?.selectionModel?.selectedText?.trim()?.takeIf { it.isNotEmpty() }

        if (routeService.isInitialScanDone) {
            val routes = routeService.getCachedRoutes()
            RouteSearchPopup(project, routes, selectedText).show()
        } else {
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "首次扫描路由...", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    routeService.scanProject()
                }

                override fun onSuccess() {
                    val routes = routeService.getCachedRoutes()
                    ApplicationManager.getApplication().invokeLater {
                        RouteSearchPopup(project, routes, selectedText).show()
                    }
                }
            })
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
