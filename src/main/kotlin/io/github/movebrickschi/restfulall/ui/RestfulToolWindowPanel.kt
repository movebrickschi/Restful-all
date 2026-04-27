package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.service.ApplicationLanguageHolder
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

class RestfulToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    val apiDebugPanel = ApiDebugPanel(project)
    private val routeListPanel = RouteListPanel(project) { routeInfo ->
        apiDebugPanel.loadRoute(routeInfo)
    }
    val requestHistoryPanel = RequestHistoryPanel(project)

    private val listCardLayout = CardLayout()
    private val listCardPanel = JPanel(listCardLayout)

    private val languageLabel = com.intellij.ui.components.JBLabel()
    private val languageCombo = JComboBox<LanguageItem>().apply {
        preferredSize = Dimension(130, 26)
    }
    private val refreshRoutesButton = createHeaderIconButton(AllIcons.Actions.Refresh) {
        routeListPanel.refreshRoutes()
    }
    private val exportRoutesButton = createHeaderIconButton(AllIcons.Actions.MenuSaveall) {
        routeListPanel.exportApiDocument()
    }
    private val expandSelectedButton = createHeaderIconButton(AllIcons.Actions.Expandall) {
        routeListPanel.expandSelectedDirectory()
    }
    private val collapseSelectedButton = createHeaderIconButton(AllIcons.Actions.Collapseall) {
        routeListPanel.collapseSelectedDirectory()
    }
    private val headerBar = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 4, 2, 4)
    }

    private var suppressLanguageEvent = false

    init {
        listCardPanel.add(routeListPanel, CARD_ROUTES)
        listCardPanel.add(requestHistoryPanel, CARD_HISTORY)

        requestHistoryPanel.setOnLoadToDebug { entry ->
            apiDebugPanel.loadHistoryEntry(entry)
        }

        buildHeaderBar()
        add(headerBar, BorderLayout.NORTH)

        val splitter = JBSplitter(true, 0.35f).apply {
            firstComponent = listCardPanel
            secondComponent = apiDebugPanel
        }

        add(splitter, BorderLayout.CENTER)

        applyI18n()

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    private fun createHeaderIconButton(icon: javax.swing.Icon, onClick: () -> Unit): JButton =
        JButton(icon).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            margin = JBUI.emptyInsets()
            preferredSize = Dimension(28, 28)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        }

    private fun buildHeaderBar() {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        left.add(refreshRoutesButton)
        left.add(exportRoutesButton)
        left.add(expandSelectedButton)
        left.add(collapseSelectedButton)
        headerBar.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        right.add(languageLabel)
        right.add(languageCombo)
        headerBar.add(right, BorderLayout.EAST)

        languageCombo.addActionListener {
            if (suppressLanguageEvent) return@addActionListener
            val item = languageCombo.selectedItem as? LanguageItem ?: return@addActionListener
            PluginSettingsState.getInstance(project).setLanguage(item.code)
        }
    }

    private fun applyI18n() {
        languageLabel.text = MyMessageBundle.message("language.combo.label") + ":"
        refreshRoutesButton.toolTipText = MyMessageBundle.message("route.list.refresh.tooltip")
        exportRoutesButton.toolTipText = MyMessageBundle.message("route.list.export.tooltip")
        expandSelectedButton.toolTipText = MyMessageBundle.message("route.list.action.expand.selected")
        collapseSelectedButton.toolTipText = MyMessageBundle.message("route.list.action.collapse.selected")

        val current = PluginSettingsState.getInstance(project).getLanguage()
        val items = listOf(
            LanguageItem(ApplicationLanguageHolder.LANG_AUTO, MyMessageBundle.message("language.auto")),
            LanguageItem(ApplicationLanguageHolder.LANG_ZH_CN, MyMessageBundle.message("language.zh.cn")),
            LanguageItem(ApplicationLanguageHolder.LANG_EN, MyMessageBundle.message("language.en")),
        )
        suppressLanguageEvent = true
        try {
            languageCombo.model = DefaultComboBoxModel(items.toTypedArray())
            val idx = items.indexOfFirst { it.code == current }.coerceAtLeast(0)
            languageCombo.selectedIndex = idx
        } finally {
            suppressLanguageEvent = false
        }
    }

    fun showRoutes() {
        listCardLayout.show(listCardPanel, CARD_ROUTES)
    }

    fun showHistory() {
        requestHistoryPanel.refresh()
        listCardLayout.show(listCardPanel, CARD_HISTORY)
    }

    private data class LanguageItem(val code: String, val label: String) {
        override fun toString(): String = label
    }

    companion object {
        private const val CARD_ROUTES = "routes"
        private const val CARD_HISTORY = "history"
    }
}
