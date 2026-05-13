package io.github.movebrickschi.restfulall.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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

class RequestHistoryPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val searchField = JBTextField()
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode(MyMessageBundle.message("request.history.root")))
    private val historyTree = JTree(treeModel)

    private var allEntries = mutableListOf<RequestHistoryEntry>()
    private var filteredEntries = mutableListOf<RequestHistoryEntry>()

    private var onLoadToDebug: ((RequestHistoryEntry) -> Unit)? = null
    private val debounceTimer = Timer(150) { rebuildTree() }.apply { isRepeats = false }

    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val clearButton = JButton(AllIcons.Actions.GC)
    private val exportButton = JButton(AllIcons.ToolbarDecorator.Export)
    private val jsonMapper = ObjectMapper()

    init {
        border = JBUI.Borders.empty(2, 4, 4, 4)
        loadFromState()
        setupUI()
        applyI18n()
        rebuildTree()

        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    override fun dispose() {
        debounceTimer.stop()
        onLoadToDebug = null
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

            exportButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { exportHistory() }
            }
            buttonPanel.add(exportButton)

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

            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val path = historyTree.getPathForLocation(e.x, e.y) ?: return
                historyTree.selectionPath = path
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val entry = node.userObject as? RequestHistoryEntry ?: return
                showEntryPopupMenu(e, entry)
            }
        })

        add(JBScrollPane(historyTree), BorderLayout.CENTER)
    }

    private fun showEntryPopupMenu(e: MouseEvent, entry: RequestHistoryEntry) {
        val menu = JPopupMenu()
        menu.add(JMenuItem(MyMessageBundle.message("request.history.action.load"), AllIcons.Actions.Execute).apply {
            addActionListener { onLoadToDebug?.invoke(entry) }
        })
        menu.add(JMenuItem(MyMessageBundle.message("request.history.action.add.tag"), AllIcons.General.Add).apply {
            addActionListener { promptAddTag(entry) }
        })
        if (entry.tags.isNotEmpty()) {
            menu.add(JMenuItem(MyMessageBundle.message("request.history.action.clear.tags"), AllIcons.General.Remove).apply {
                addActionListener {
                    entry.tags.clear()
                    persistAndRefresh()
                }
            })
        }
        menu.add(JMenuItem(MyMessageBundle.message("request.history.action.edit.note"), AllIcons.Actions.Edit).apply {
            addActionListener { promptEditNote(entry) }
        })
        menu.addSeparator()
        menu.add(JMenuItem(MyMessageBundle.message("request.history.action.delete"), AllIcons.General.Remove).apply {
            addActionListener {
                val choice = Messages.showYesNoDialog(
                    this@RequestHistoryPanel,
                    MyMessageBundle.message("request.history.action.delete.confirm"),
                    MyMessageBundle.message("request.history.action.delete"),
                    Messages.getWarningIcon(),
                )
                if (choice == Messages.YES) {
                    allEntries.removeAll { it === entry }
                    PluginSettingsState.getInstance(project).getRequestHistory().removeAll { it === entry }
                    rebuildTree()
                }
            }
        })
        menu.show(e.component, e.x, e.y)
    }

    private fun promptAddTag(entry: RequestHistoryEntry) {
        if (entry.tags.size >= MAX_TAGS_PER_ENTRY) {
            Messages.showInfoMessage(
                this,
                MyMessageBundle.message("request.history.tag.limit", MAX_TAGS_PER_ENTRY),
                MyMessageBundle.message("request.history.action.add.tag"),
            )
            return
        }
        val tag = Messages.showInputDialog(
            this,
            MyMessageBundle.message("request.history.tag.prompt"),
            MyMessageBundle.message("request.history.action.add.tag"),
            null,
        )?.trim().orEmpty()
        if (tag.isEmpty() || tag.length > 30) return
        if (entry.tags.contains(tag)) return
        entry.tags.add(tag)
        persistAndRefresh()
    }

    private fun promptEditNote(entry: RequestHistoryEntry) {
        val newNote = Messages.showMultilineInputDialog(
            project,
            MyMessageBundle.message("request.history.note.prompt"),
            MyMessageBundle.message("request.history.action.edit.note"),
            entry.note,
            null,
            null,
        )?.trim() ?: return
        entry.note = newNote.take(MAX_NOTE_LENGTH)
        persistAndRefresh()
    }

    private fun persistAndRefresh() {
        // PluginSettingsState 持有的是同一对象引用，直接 reload 触发持久化 + 重绘
        rebuildTree()
    }

    /**
     * v1.3.2 F9 - 导出当前过滤后的历史到 JSON 文件。
     *
     * - 走 IntelliJ FileSaverDialog 让用户选目标路径
     * - 内容：filteredEntries（不是 allEntries），让用户能定向导出"某个 tag / 状态码"的条目
     * - 编码 UTF-8，jackson pretty-printed
     */
    private fun exportHistory() {
        if (filteredEntries.isEmpty()) {
            Messages.showInfoMessage(
                this,
                MyMessageBundle.message("request.history.export.empty"),
                MyMessageBundle.message("request.history.action.export"),
            )
            return
        }
        val descriptor = FileSaverDescriptor(
            MyMessageBundle.message("request.history.export.dialog.title"),
            MyMessageBundle.message("request.history.export.dialog.description"),
        )
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target = saver.save("restful-all-history.json") ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val json = jsonMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(filteredEntries)
                target.file.writeText(json, Charsets.UTF_8)
                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(
                        this,
                        MyMessageBundle.message("request.history.export.success", filteredEntries.size, target.file.absolutePath),
                        MyMessageBundle.message("request.history.action.export"),
                    )
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(
                        this,
                        MyMessageBundle.message("request.history.export.failure", e.message ?: "I/O error"),
                        MyMessageBundle.message("request.history.action.export"),
                    )
                }
            }
        }
    }

    private fun applyI18n() {
        searchField.emptyText.text = MyMessageBundle.message("request.history.search.placeholder")
        refreshButton.toolTipText = MyMessageBundle.message("request.history.refresh.tooltip")
        clearButton.toolTipText = MyMessageBundle.message("request.history.clear.tooltip")
        exportButton.toolTipText = MyMessageBundle.message("request.history.export.tooltip")
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
        val query = searchField.text.trim()
        filteredEntries = matchEntries(query).toMutableList()

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

    /**
     * v1.3.2 F9 - 多条件搜索匹配。
     *
     * 支持语法：
     * - 空字符串 → 全量
     * - `tag:xxx` → 标签命中
     * - `status:2xx` / `status:4xx` / `status:200` → 状态码命中
     * - `method:POST` → 方法命中（POST 是大小写不敏感）
     * - 其它纯文本 → URL / note free-text 匹配（lowercase contains）
     *
     * 同一 query 内可空格分隔多个条件，**AND** 关系（全部满足才返回）。
     */
    private fun matchEntries(rawQuery: String): List<RequestHistoryEntry> {
        if (rawQuery.isBlank()) return allEntries.toList()
        val tokens = rawQuery.trim().split(Regex("\\s+"))
        return allEntries.filter { entry -> tokens.all { matchOne(entry, it) } }
    }

    private fun matchOne(entry: RequestHistoryEntry, token: String): Boolean {
        return when {
            token.startsWith("tag:", ignoreCase = true) -> {
                val tag = token.removePrefix("tag:").removePrefix("Tag:").trim()
                entry.tags.any { it.equals(tag, ignoreCase = true) }
            }
            token.startsWith("status:", ignoreCase = true) -> {
                val v = token.removePrefix("status:").removePrefix("Status:").trim()
                when {
                    v.endsWith("xx", ignoreCase = true) -> {
                        val digit = v.removeSuffix("xx").removeSuffix("XX").toIntOrNull() ?: return true
                        entry.responseStatus / 100 == digit
                    }
                    else -> entry.responseStatus.toString() == v
                }
            }
            token.startsWith("method:", ignoreCase = true) -> {
                val m = token.removePrefix("method:").removePrefix("Method:").trim()
                entry.method.equals(m, ignoreCase = true)
            }
            else -> {
                val lower = token.lowercase()
                entry.url.lowercase().contains(lower) ||
                    entry.note.lowercase().contains(lower) ||
                    entry.method.lowercase().contains(lower)
            }
        }
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
                val tagPart = if (entry.tags.isEmpty()) "" else {
                    val joined = entry.tags.joinToString("][") { it }
                    " <i style='color:#888888'>[$joined]</i>"
                }
                val notePart = if (entry.note.isBlank()) "" else
                    " <span style='color:#666666'>· ${entry.note.take(30)}</span>"
                text = "<html><b style='color:rgb(${mc.red},${mc.green},${mc.blue})'>" +
                    "${entry.method}</b> ${entry.displayUrl()}$tagPart$notePart</html>"
                icon = AllIcons.Nodes.Method
                toolTipText = if (entry.note.isNotBlank()) entry.note else null
            } else if (entry is String) {
                icon = AllIcons.Nodes.Folder
            }
            return comp
        }
    }

    companion object {
        private const val MAX_TAGS_PER_ENTRY = 5
        private const val MAX_NOTE_LENGTH = 500
    }
}
