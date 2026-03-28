package io.github.movebrickschi.restfulall

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.movebrickschi.restfulall.ui.BaseUrlPanel
import io.github.movebrickschi.restfulall.ui.GlobalParamsPanel
import io.github.movebrickschi.restfulall.ui.RestfulToolWindowPanel
import java.awt.BorderLayout
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val mainPanel = RestfulToolWindowPanel(project)

        val routesWrapper = WrapperPanel(mainPanel)
        val historyWrapper = WrapperPanel(mainPanel)

        val interfaceContent = contentFactory.createContent(routesWrapper, "接口", false).apply {
            isCloseable = false
        }
        val historyContent = contentFactory.createContent(historyWrapper, "请求历史", false).apply {
            isCloseable = false
        }
        val baseUrlContent = contentFactory.createContent(BaseUrlPanel(project), "前置 URL", false).apply {
            isCloseable = false
        }
        val globalParamsContent = contentFactory.createContent(GlobalParamsPanel(project), "全局参数", false).apply {
            isCloseable = false
        }

        toolWindow.contentManager.addContent(interfaceContent)
        toolWindow.contentManager.addContent(historyContent)
        toolWindow.contentManager.addContent(baseUrlContent)
        toolWindow.contentManager.addContent(globalParamsContent)

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (!event.content.isSelected) return
                when (event.content) {
                    interfaceContent -> {
                        routesWrapper.attach(mainPanel)
                        mainPanel.showRoutes()
                    }
                    historyContent -> {
                        historyWrapper.attach(mainPanel)
                        mainPanel.showHistory()
                    }
                }
            }
        })

        routesWrapper.attach(mainPanel)
        mainPanel.showRoutes()

        project.messageBus.connect(toolWindow.contentManager).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun toolWindowShown(tw: ToolWindow) {
                    if (tw.id == toolWindow.id) {
                        // Restful-all 被激活时，隐藏其他可见的工具窗口
                        val manager = ToolWindowManager.getInstance(project)
                        for (id in manager.toolWindowIds) {
                            if (id != toolWindow.id) {
                                val other = manager.getToolWindow(id)
                                if (other != null && other.isVisible) {
                                    other.hide()
                                }
                            }
                        }
                    } else if (toolWindow.isVisible) {
                        // 其他工具窗口被激活时，隐藏 Restful-all
                        toolWindow.hide()
                    }
                }
            }
        )
    }
}

private class WrapperPanel(initialChild: JPanel? = null) : JPanel(BorderLayout()) {
    fun attach(child: JPanel) {
        removeAll()
        add(child, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    init {
        initialChild?.let { attach(it) }
    }
}
