package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

class RequestHistoryPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val searchField = JBTextField()
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode(MyMessageBundle.message("request.history.root")))
    private val historyTree = JTree(treeModel)

    private var allEntries = mutableListOf<RequestHistoryEntry>()
    private var filteredEntries = mutableListOf<RequestHistoryEntry>()

    private var onLoadToDebug: ((RequestHistoryEntry) -> Unit)? = null
    private val debounceTimer = Timer(150) { rebuildTree() }.apply { isRepeats = false }

    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val clearButton = JButton(AllIcons.Actions.GC)

    init {
        border = JBUI.Borders.empty(2, 4, 4, 4)
        loadFromState()
        setupUI()
        applyI18n()
        rebuildTree()

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    fun setOnLoadToDebug(callback: (RequestHistoryEntry) -> Unit) {
        onLoadToDebug = callback
    }

    private fun setupUI() {
        val toolbar = JPanel(BorderLayout(2, 0)).apply {
            border = JBUI.Borders.empty(2, 0, 2, 0)

            add(searchField, BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))

            refreshButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { refresh() }
            }
            buttonPanel.add(refreshButton)

            clearButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { clearHistory() }
            }
            buttonPanel.add(clearButton)

            add(buttonPanel, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounceTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = debounceTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = debounceTimer.restart()
        })

        historyTree.isRootVisible = false
        historyTree.showsRootHandles = true
        historyTree.cellRenderer = HistoryTreeCellRenderer()

        historyTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount < 2) return
                val path = historyTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val entry = node.userObject as? RequestHistoryEntry ?: return
                onLoadToDebug?.invoke(entry)
            }
        })

        add(JBScrollPane(historyTree), BorderLayout.CENTER)
    }

    private fun applyI18n() {
        searchField.emptyText.text = MyMessageBundle.message("request.history.search.placeholder")
        refreshButton.toolTipText = MyMessageBundle.message("request.history.refresh.tooltip")
        clearButton.toolTipText = MyMessageBundle.message("request.history.clear.tooltip")
        rebuildTree()
    }

    private fun loadFromState() {
        val state = PluginSettingsState.getInstance(project)
        allEntries = state.getRequestHistory().toMutableList()
    }

    fun refresh() {
        loadFromState()
        rebuildTree()
    }

    private fun rebuildTree() {
        val query = searchField.text.trim().lowercase()
        filteredEntries = if (query.isBlank()) {
            allEntries.toMutableList()
        } else {
            allEntries.filter {
                it.url.lowercase().contains(query) || it.method.lowercase().contains(query)
            }.toMutableList()
        }

        val root = DefaultMutableTreeNode(MyMessageBundle.message("request.history.root"))
        val grouped = filteredEntries.groupBy { it.displayDate() }
        for ((date, entries) in grouped) {
            val dateNode = DefaultMutableTreeNode(
                MyMessageBundle.message("request.history.group.title", date, entries.size)
            )
            for (entry in entries) {
                dateNode.add(DefaultMutableTreeNode(entry))
            }
            root.add(dateNode)
        }

        treeModel.setRoot(root)
        treeModel.reload()

        for (i in 0 until historyTree.rowCount) {
            historyTree.expandRow(i)
        }
    }

    private fun clearHistory() {
        val state = PluginSettingsState.getInstance(project)
        state.clearHistory()
        allEntries.clear()
        rebuildTree()
    }

    private class HistoryTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, sel: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val comp = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return comp
            val entry = node.userObject

            if (entry is RequestHistoryEntry) {
                val mc = HttpMethod.fromString(entry.method)?.color ?: Color.GRAY
                text = "<html><b style='color:rgb(${mc.red},${mc.green},${mc.blue})'>" +
                    "${entry.method}</b> ${entry.displayUrl()}</html>"
                icon = AllIcons.Nodes.Method
            } else if (entry is String) {
                icon = AllIcons.Nodes.Folder
            }
            return comp
        }
    }
}
