package io.github.movebrickschi.restfulall

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
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

        val interfaceContent = contentFactory.createContent(routesWrapper, MyMessageBundle.message("tab.interface"), false).apply {
            isCloseable = false
        }
        val historyContent = contentFactory.createContent(historyWrapper, MyMessageBundle.message("tab.request.history"), false).apply {
            isCloseable = false
        }
        val baseUrlContent = contentFactory.createContent(BaseUrlPanel(project), MyMessageBundle.message("tab.base.url"), false).apply {
            isCloseable = false
        }
        val globalParamsContent = contentFactory.createContent(GlobalParamsPanel(project), MyMessageBundle.message("tab.global.params"), false).apply {
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

        val tabBindings = listOf(
            interfaceContent to "tab.interface",
            historyContent to "tab.request.history",
            baseUrlContent to "tab.base.url",
            globalParamsContent to "tab.global.params",
        )
        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener {
                refreshTabTitles(tabBindings)
            })
    }

    private fun refreshTabTitles(tabBindings: List<Pair<Content, String>>) {
        for ((content, key) in tabBindings) {
            content.displayName = MyMessageBundle.message(key)
        }
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
