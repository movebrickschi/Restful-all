package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.JPanel

class RestfulToolWindowPanel(project: Project) : JPanel(BorderLayout()) {

    val apiDebugPanel = ApiDebugPanel(project)
    private val routeListPanel = RouteListPanel(project) { routeInfo ->
        apiDebugPanel.loadRoute(routeInfo)
    }
    val requestHistoryPanel = RequestHistoryPanel(project)

    private val listCardLayout = CardLayout()
    private val listCardPanel = JPanel(listCardLayout)

    init {
        listCardPanel.add(routeListPanel, CARD_ROUTES)
        listCardPanel.add(requestHistoryPanel, CARD_HISTORY)

        requestHistoryPanel.setOnLoadToDebug { entry ->
            apiDebugPanel.loadHistoryEntry(entry)
        }

        val splitter = JBSplitter(true, 0.35f).apply {
            firstComponent = listCardPanel
            secondComponent = apiDebugPanel
        }

        add(splitter, BorderLayout.CENTER)
    }

    fun showRoutes() {
        listCardLayout.show(listCardPanel, CARD_ROUTES)
    }

    fun showHistory() {
        requestHistoryPanel.refresh()
        listCardLayout.show(listCardPanel, CARD_HISTORY)
    }

    companion object {
        private const val CARD_ROUTES = "routes"
        private const val CARD_HISTORY = "history"
    }
}
