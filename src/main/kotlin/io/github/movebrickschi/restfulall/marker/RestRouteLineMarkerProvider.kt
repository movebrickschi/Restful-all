package io.github.movebrickschi.restfulall.marker

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.Function
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.license.LicenseManager
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.RestfulToolWindowHolder
import io.github.movebrickschi.restfulall.service.RouteService
import javax.swing.Icon

class RestRouteLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = MyMessageBundle.message("marker.test.route.name")

    override fun getIcon(): Icon = AllIcons.General.Web

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        if (element.firstChild != null) return null

        val containingFile = element.containingFile ?: return null
        val virtualFile = containingFile.virtualFile ?: return null
        val project = element.project

        val service = RouteService.getInstance(project)
        if (!service.isInitialScanDone) return null

        val document = PsiDocumentManager.getInstance(project).getDocument(containingFile) ?: return null
        val startOffset = element.textRange.startOffset
        if (startOffset >= document.textLength) return null

        val line = document.getLineNumber(startOffset)
        val route = service.findRouteAtExactLine(virtualFile, line) ?: return null

        if (!isFirstNonWhitespaceLeafOnLine(startOffset, line, document)) return null

        val displayMethod = route.method.displayName
        val displayPath = route.displayPath
        val tooltipFn = Function<PsiElement, String> {
            MyMessageBundle.message("marker.test.route.tooltip", displayMethod, displayPath)
        }
        val navigationHandler = GutterIconNavigationHandler<PsiElement> { _, _ ->
            openInDebugPanel(project, route)
        }

        return LineMarkerInfo<PsiElement>(
            element,
            element.textRange,
            AllIcons.General.Web,
            tooltipFn,
            navigationHandler,
            GutterIconRenderer.Alignment.LEFT,
        )
    }

    private fun isFirstNonWhitespaceLeafOnLine(
        startOffset: Int,
        line: Int,
        document: com.intellij.openapi.editor.Document,
    ): Boolean {
        val lineStart = document.getLineStartOffset(line)
        val text = document.charsSequence
        var firstNonWs = lineStart
        while (firstNonWs < document.textLength && text[firstNonWs].isWhitespace()) {
            firstNonWs++
        }
        return startOffset == firstNonWs
    }

    private fun openInDebugPanel(project: Project, route: RouteInfo) {
        if (!LicenseManager.requirePro(project, "gutter_debug")) return
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        toolWindow.activate({
            val holder = RestfulToolWindowHolder.getInstance(project)
            holder.interfaceContent?.let { toolWindow.contentManager.setSelectedContent(it) }
            holder.panel?.apply {
                showRoutes()
                apiDebugPanel.loadRoute(route)
            }
        }, true, true)
    }

    companion object {
        private const val TOOL_WINDOW_ID = "Restful-all"
    }
}
