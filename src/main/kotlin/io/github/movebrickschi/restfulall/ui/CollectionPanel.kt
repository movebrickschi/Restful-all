package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.CollectionEntry
import io.github.movebrickschi.restfulall.model.CollectionItem
import io.github.movebrickschi.restfulall.service.CollectionService
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.PostmanImporter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * v1.3.1 F3 - Collection 集合 UI 面板。
 *
 * 布局：
 * ```
 * ┌─────────────────────────────────────────────┐
 * │ [Search] [+ New] [Refresh] [Delete]         │ ← 顶部工具栏
 * ├─────────────────────────────────────────────┤
 * │ ▼ Collection A                              │
 * │     • GET /api/users          (item)        │
 * │     • POST /api/orders        (item)        │
 * │ ▼ Collection B                              │
 * │     ▼ Sub Collection                        │
 * │         • PATCH /api/profile  (item)        │
 * └─────────────────────────────────────────────┘
 * ```
 *
 * - 双击 item → 通过 [onLoadItem] 加载到 [ApiDebugPanel]
 * - 右键 collection / item → 弹出菜单（新建子集合 / 重命名 / 删除 / 在调试面板打开）
 * - 顶部 + 按钮 → 弹窗输入名字创建根级 collection
 * - 搜索框 → 按 collection 名 / item 名 / item url 模糊匹配，> 150ms 防抖触发
 */
class CollectionPanel(
    private val project: Project,
    private val onLoadItem: (CollectionItem) -> Unit,
) : JPanel(BorderLayout()), Disposable {

    private val service get() = CollectionService.getInstance(project)

    private val searchField = JBTextField()
    private val rootNode = DefaultMutableTreeNode("ROOT")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = JTree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = CollectionCellRenderer()
    }

    private val statusLabel = JBLabel()
    private val refreshTimer = Timer(150) { rebuildTree() }.apply { isRepeats = false }

    private val newButton = createIconButton(AllIcons.General.Add)
    private val importButton = createIconButton(AllIcons.ToolbarDecorator.Import)
    private val mockButton = createIconButton(AllIcons.Actions.Execute)
    private val refreshButton = createIconButton(AllIcons.Actions.Refresh)
    private val deleteButton = createIconButton(AllIcons.General.Remove)

    init {
        border = JBUI.Borders.empty(2, 4)
        setupToolbar()
        setupTree()
        applyI18n()
        rebuildTree()

        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    override fun dispose() {
        refreshTimer.stop()
    }

    private fun setupToolbar() {
        val toolbar = JPanel(BorderLayout(2, 0)).apply {
            border = JBUI.Borders.empty(2, 0)
        }
        toolbar.add(searchField, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
        newButton.addActionListener { promptNewCollection(parentId = null) }
        importButton.addActionListener { importPostmanFile() }
        mockButton.addActionListener { toggleMockServer() }
        refreshButton.addActionListener { rebuildTree() }
        deleteButton.addActionListener { deleteSelected() }
        buttons.add(newButton)
        buttons.add(importButton)
        buttons.add(mockButton)
        buttons.add(refreshButton)
        buttons.add(deleteButton)
        toolbar.add(buttons, BorderLayout.EAST)

        add(toolbar, BorderLayout.NORTH)

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refreshTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = refreshTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = refreshTimer.restart()
        })
    }

    private fun setupTree() {
        val scroll = JBScrollPane(tree)
        add(scroll, BorderLayout.CENTER)

        val statusBar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 4)
            add(statusLabel, BorderLayout.WEST)
        }
        add(statusBar, BorderLayout.SOUTH)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                tree.selectionPath = path
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopupMenu(e, path)
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || !SwingUtilities.isLeftMouseButton(e)) return
                val payload = selectedPayload() ?: return
                if (payload is ItemPayload) onLoadItem(payload.item)
            }
        })
    }

    private fun applyI18n() {
        searchField.toolTipText = MyMessageBundle.message("collection.search.placeholder")
        newButton.toolTipText = MyMessageBundle.message("collection.new.tooltip")
        importButton.toolTipText = MyMessageBundle.message("collection.import.postman.tooltip")
        mockButton.toolTipText = MyMessageBundle.message("collection.mock.tooltip")
        refreshButton.toolTipText = MyMessageBundle.message("collection.refresh.tooltip")
        deleteButton.toolTipText = MyMessageBundle.message("collection.delete.tooltip")
    }

    /**
     * v1.3.3 P1-6 - 切换 Mock Server 状态。
     *
     * - 已运行 → stop 并提示
     * - 未运行 → 走 ProFeatureGate.MOCK_SERVER（Team gate）→ 选当前选中 collection 的 items
     *   作为 mock 数据源 → 启动 server in pool thread
     */
    private fun toggleMockServer() {
        val svc = io.github.movebrickschi.restfulall.service.MockServerService.getInstance(project)
        if (svc.isRunning()) {
            svc.stop()
            Messages.showInfoMessage(this,
                MyMessageBundle.message("collection.mock.stopped"),
                MyMessageBundle.message("collection.mock.title"))
            return
        }
        val gateOk = io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(
            project,
            io.github.movebrickschi.restfulall.service.ProFeature.MOCK_SERVER,
        )
        if (!gateOk) return

        val payload = collectMockItems()
        if (payload.isEmpty()) {
            Messages.showInfoMessage(this,
                MyMessageBundle.message("collection.mock.empty"),
                MyMessageBundle.message("collection.mock.title"))
            return
        }
        val portStr = Messages.showInputDialog(
            this,
            MyMessageBundle.message("collection.mock.port.prompt"),
            MyMessageBundle.message("collection.mock.title"),
            null,
            io.github.movebrickschi.restfulall.service.MockServerService.DEFAULT_PORT.toString(),
            null,
        )?.trim().orEmpty()
        val port = portStr.toIntOrNull() ?: io.github.movebrickschi.restfulall.service.MockServerService.DEFAULT_PORT
        try {
            val running = svc.start(port, payload)
            Messages.showInfoMessage(this,
                MyMessageBundle.message("collection.mock.started", running.port, payload.size),
                MyMessageBundle.message("collection.mock.title"))
        } catch (e: Exception) {
            Messages.showErrorDialog(this,
                MyMessageBundle.message("collection.mock.failed", e.message ?: "I/O error"),
                MyMessageBundle.message("collection.mock.title"))
        }
    }

    /** 选中根 collection → 用其全部 items（含子节点）；否则用所有顶级 collection items 合并。 */
    private fun collectMockItems(): List<CollectionItem> {
        val service = CollectionService.getInstance(project)
        val payload = selectedPayload()
        if (payload is CollectionPayload) {
            val ids = setOf(payload.entry.id) + collectChildIds(payload.entry.id)
            return service.list().filter { it.id in ids }.flatMap { it.sortedItems() }
        }
        return service.list().flatMap { it.sortedItems() }
    }

    private fun collectChildIds(rootId: String): Set<String> {
        val out = mutableSetOf<String>()
        val q = ArrayDeque<String>().apply { add(rootId) }
        val all = CollectionService.getInstance(project).list()
        while (q.isNotEmpty()) {
            val cur = q.removeFirst()
            for (c in all) if (c.parentId == cur && out.add(c.id)) q.add(c.id)
        }
        return out
    }

    /**
     * v1.3.1 F12 - 弹文件选择，把 Postman / Apifox JSON 转为 Collection 并写入持久化层。
     */
    private fun importPostmanFile() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(
            true, false, false, false, false, false,
        )
            .withFileFilter { vf -> vf.extension?.lowercase() == "json" }
            .withTitle(MyMessageBundle.message("collection.import.postman.dialog.title"))
        val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val jsonText = try {
                String(chosen.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        this,
                        MyMessageBundle.message("collection.import.postman.read.failure", e.message ?: "I/O"),
                        MyMessageBundle.message("collection.error.title"),
                    )
                }
                return@executeOnPooledThread
            }
            val result = PostmanImporter.getInstance(project).importCollection(jsonText, chosen.name)
            ApplicationManager.getApplication().invokeLater {
                if (!result.success) {
                    Messages.showErrorDialog(
                        this,
                        result.error ?: "Unknown error",
                        MyMessageBundle.message("collection.import.postman.failure.title"),
                    )
                    return@invokeLater
                }
                refresh()
                val summary = MyMessageBundle.message(
                    "collection.import.postman.success",
                    result.totalItems,
                    result.collectionName,
                )
                if (result.warnings.isEmpty()) {
                    Messages.showInfoMessage(this, summary, MyMessageBundle.message("collection.import.postman.title"))
                } else {
                    val joined = result.warnings.take(8).joinToString("\n")
                    Messages.showWarningDialog(
                        this,
                        "$summary\n\n${MyMessageBundle.message("collection.import.postman.warnings")}\n$joined",
                        MyMessageBundle.message("collection.import.postman.title"),
                    )
                }
            }
        }
    }

    private fun rebuildTree() {
        val query = searchField.text.trim().lowercase()
        rootNode.removeAllChildren()

        val allCollections = service.list()
        val roots = allCollections.filter { it.parentId == null }
        val byParent = allCollections.groupBy { it.parentId }

        var totalCount = 0
        for (root in roots) {
            val node = buildCollectionNode(root, byParent, query)
            if (node != null) {
                rootNode.add(node)
                totalCount += countLeaves(node)
            }
        }

        treeModel.nodeStructureChanged(rootNode)
        for (i in 0 until tree.rowCount) tree.expandRow(i)

        statusLabel.text = MyMessageBundle.message(
            "collection.status.total",
            allCollections.size,
            totalCount,
        )
    }

    private fun buildCollectionNode(
        coll: CollectionEntry,
        byParent: Map<String?, List<CollectionEntry>>,
        query: String,
    ): DefaultMutableTreeNode? {
        val nameMatch = query.isEmpty() || coll.name.lowercase().contains(query)
        val node = DefaultMutableTreeNode(CollectionPayload(coll))

        var matchedAny = nameMatch
        for (item in coll.sortedItems()) {
            val urlMatch = item.spec.url.lowercase().contains(query)
            val itemMatch = query.isEmpty() ||
                item.name.lowercase().contains(query) ||
                urlMatch
            if (itemMatch) {
                node.add(DefaultMutableTreeNode(ItemPayload(coll, item)))
                matchedAny = true
            }
        }

        for (child in byParent[coll.id].orEmpty()) {
            val childNode = buildCollectionNode(child, byParent, query)
            if (childNode != null) {
                node.add(childNode)
                matchedAny = true
            }
        }

        return if (matchedAny) node else null
    }

    private fun countLeaves(node: DefaultMutableTreeNode): Int {
        var c = 0
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (child.userObject is ItemPayload) c++ else c += countLeaves(child)
        }
        return c
    }

    private fun showPopupMenu(e: MouseEvent, path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val payload = node.userObject

        val menu = JPopupMenu()
        when (payload) {
            is CollectionPayload -> {
                menu.add(JMenuItem(
                    MyMessageBundle.message("collection.menu.new.sub"),
                    AllIcons.General.Add,
                ).apply {
                    addActionListener { promptNewCollection(parentId = payload.entry.id) }
                })
                menu.add(JMenuItem(
                    MyMessageBundle.message("collection.menu.rename"),
                    AllIcons.Actions.Edit,
                ).apply {
                    addActionListener { promptRenameCollection(payload.entry) }
                })
                menu.add(JMenuItem(
                    MyMessageBundle.message("collection.menu.delete"),
                    AllIcons.General.Remove,
                ).apply {
                    addActionListener { confirmDeleteCollection(payload.entry) }
                })
            }
            is ItemPayload -> {
                menu.add(JMenuItem(
                    MyMessageBundle.message("collection.menu.open.debug"),
                    AllIcons.Actions.Execute,
                ).apply {
                    addActionListener { onLoadItem(payload.item) }
                })
                menu.add(JMenuItem(
                    MyMessageBundle.message("collection.menu.rename"),
                    AllIcons.Actions.Edit,
                ).apply {
                    addActionListener { promptRenameItem(payload.item) }
                })
                menu.add(JMenuItem(
                    MyMessageBundle.message("collection.menu.delete"),
                    AllIcons.General.Remove,
                ).apply {
                    addActionListener { confirmDeleteItem(payload.item) }
                })
            }
        }
        menu.show(e.component, e.x, e.y)
    }

    private fun selectedPayload(): Any? {
        val path = tree.selectionPath ?: return null
        return (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject
    }

    private fun promptNewCollection(parentId: String?) {
        val name = Messages.showInputDialog(
            this,
            MyMessageBundle.message("collection.new.prompt"),
            MyMessageBundle.message("collection.new.title"),
            null,
        )?.trim().orEmpty()
        if (name.isEmpty()) return

        try {
            service.upsert(CollectionEntry(name = name, parentId = parentId))
            rebuildTree()
        } catch (ex: Exception) {
            Messages.showErrorDialog(
                this,
                ex.message ?: "Create failed",
                MyMessageBundle.message("collection.error.title"),
            )
        }
    }

    private fun promptRenameCollection(entry: CollectionEntry) {
        val name = Messages.showInputDialog(
            this,
            MyMessageBundle.message("collection.rename.prompt"),
            MyMessageBundle.message("collection.rename.title"),
            null,
            entry.name,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty() || name == entry.name) return
        try {
            entry.name = name
            service.upsert(entry)
            rebuildTree()
        } catch (ex: Exception) {
            Messages.showErrorDialog(
                this,
                ex.message ?: "Rename failed",
                MyMessageBundle.message("collection.error.title"),
            )
        }
    }

    private fun confirmDeleteCollection(entry: CollectionEntry) {
        val choice = Messages.showYesNoDialog(
            this,
            MyMessageBundle.message("collection.delete.confirm", entry.name),
            MyMessageBundle.message("collection.delete.title"),
            Messages.getWarningIcon(),
        )
        if (choice != Messages.YES) return
        service.delete(entry.id)
        rebuildTree()
    }

    private fun promptRenameItem(item: CollectionItem) {
        val name = Messages.showInputDialog(
            this,
            MyMessageBundle.message("collection.item.rename.prompt"),
            MyMessageBundle.message("collection.item.rename.title"),
            null,
            item.name,
            null,
        )?.trim().orEmpty()
        if (name.isEmpty() || name == item.name) return
        try {
            service.renameItem(item.id, name)
            rebuildTree()
        } catch (ex: Exception) {
            Messages.showErrorDialog(
                this,
                ex.message ?: "Rename failed",
                MyMessageBundle.message("collection.error.title"),
            )
        }
    }

    private fun confirmDeleteItem(item: CollectionItem) {
        val choice = Messages.showYesNoDialog(
            this,
            MyMessageBundle.message("collection.item.delete.confirm", item.name),
            MyMessageBundle.message("collection.delete.title"),
            Messages.getWarningIcon(),
        )
        if (choice != Messages.YES) return
        service.removeItem(item.id)
        rebuildTree()
    }

    private fun deleteSelected() {
        when (val payload = selectedPayload()) {
            is CollectionPayload -> confirmDeleteCollection(payload.entry)
            is ItemPayload -> confirmDeleteItem(payload.item)
            else -> {}
        }
    }

    fun refresh() {
        rebuildTree()
    }

    private fun createIconButton(icon: Icon): JButton = JButton(icon).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusable = false
        margin = JBUI.emptyInsets()
        preferredSize = Dimension(28, 28)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private data class CollectionPayload(val entry: CollectionEntry)
    private data class ItemPayload(val collection: CollectionEntry, val item: CollectionItem)

    private class CollectionCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            val node = value as? DefaultMutableTreeNode ?: return this
            when (val payload = node.userObject) {
                is CollectionPayload -> {
                    text = payload.entry.name +
                        if (payload.entry.items.isNotEmpty()) "  (${payload.entry.items.size})" else ""
                    icon = AllIcons.Nodes.Folder
                    foreground = if (sel) JLabel().foreground else null
                }
                is ItemPayload -> {
                    val method = payload.item.spec.method
                    val url = payload.item.spec.url
                    val name = payload.item.name
                    text = if (name == url) "$method  $url" else "$name  $method $url"
                    icon = AllIcons.General.Web
                    if (payload.item.disabled) {
                        foreground = Color.GRAY
                        text = "(disabled) $text"
                    }
                }
                else -> {}
            }
            return this
        }
    }
}
