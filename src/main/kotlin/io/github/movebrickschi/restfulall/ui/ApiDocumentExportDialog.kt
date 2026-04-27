package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.export.ApiDocumentFormat
import io.github.movebrickschi.restfulall.export.ApiDocumentOptions
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
import javax.swing.ComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultTreeModel

class ApiDocumentExportDialog(
    private val project: Project,
    routes: List<RouteInfo>,
) : DialogWrapper(project) {

    private val settings = PluginSettingsState.getInstance(project)
    private val selectionModel = ApiDocumentExportSelectionModel(routes)
    private val searchField = JBTextField()
    private val summaryLabel = JBLabel()
    private val formatCombo = JComboBox(formatComboModel()).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): java.awt.Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                text = (value as? ApiDocumentFormat)?.label ?: value?.toString().orEmpty()
                return component
            }
        }
    }
    private val titleField = JBTextField(defaultTitle())
    private val versionField = JBTextField(defaultVersion())
    private val descriptionArea = JBTextArea(defaultDescription(), DESCRIPTION_ROWS, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val tree = CheckboxTree(ExportTreeRenderer(), buildCheckedRoot(""))

    val selectedRoutes: List<RouteInfo>
        get() {
            syncSelectionFromTree()
            return selectionModel.selectedRoutes()
        }

    val format: ApiDocumentFormat
        get() = formatCombo.selectedItem as? ApiDocumentFormat ?: ApiDocumentFormat.OPENAPI_JSON

    val options: ApiDocumentOptions
        get() = ApiDocumentOptions(
            title = titleField.text.trim(),
            version = versionField.text.trim(),
            description = descriptionArea.text.trim(),
        )

    init {
        title = MyMessageBundle.message("route.list.export.dialog.title")
        searchField.emptyText.text = MyMessageBundle.message("route.list.export.dialog.search.placeholder")
        tree.isRootVisible = true
        tree.showsRootHandles = true
        tree.rowHeight = JBUI.scale(24)

        val formListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateOkState()
            override fun removeUpdate(e: DocumentEvent) = updateOkState()
            override fun changedUpdate(e: DocumentEvent) = updateOkState()
        }
        titleField.document.addDocumentListener(formListener)
        versionField.document.addDocumentListener(formListener)
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuildTreeFromSearch()
            override fun removeUpdate(e: DocumentEvent) = rebuildTreeFromSearch()
            override fun changedUpdate(e: DocumentEvent) = rebuildTreeFromSearch()
        })

        updateSummary()
        init()
        updateOkState()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(560))
        }
        panel.add(buildToolbar(), BorderLayout.NORTH)
        panel.add(JBScrollPane(tree), BorderLayout.CENTER)
        panel.add(buildForm(), BorderLayout.SOUTH)
        return panel
    }

    override fun doOKAction() {
        syncSelectionFromTree()
        ApiDocumentExportSelectionModel.persistOptions(settings.state, format, options)
        super.doOKAction()
    }

    private fun buildToolbar(): JComponent {
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        panel.add(searchField, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            add(button("route.list.export.dialog.select.all") {
                selectionModel.selectAll()
                rebuildTreePreservingSearch()
            })
            add(button("route.list.export.dialog.select.none") {
                selectionModel.selectNone()
                rebuildTreePreservingSearch()
            })
            add(button("route.list.export.dialog.select.filtered") {
                syncSelectionFromTree()
                selectionModel.selectVisible(searchField.text)
                rebuildTreePreservingSearch()
            })
            add(button("route.list.export.dialog.invert.filtered") {
                syncSelectionFromTree()
                selectionModel.invertVisible(searchField.text)
                rebuildTreePreservingSearch()
            })
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(summaryLabel)
        }
        panel.add(buttons, BorderLayout.EAST)
        return panel
    }

    private fun buildForm(): JComponent =
        FormBuilder.createFormBuilder()
            .addLabeledComponent(MyMessageBundle.message("route.list.export.dialog.format"), formatCombo)
            .addLabeledComponent(MyMessageBundle.message("route.list.export.dialog.title.label"), titleField)
            .addLabeledComponent(MyMessageBundle.message("route.list.export.dialog.version"), versionField)
            .addLabeledComponent(
                MyMessageBundle.message("route.list.export.dialog.description"),
                JBScrollPane(descriptionArea).apply {
                    preferredSize = Dimension(JBUI.scale(520), JBUI.scale(72))
                },
            )
            .panel

    private fun button(messageKey: String, action: () -> Unit): JButton =
        JButton(MyMessageBundle.message(messageKey)).apply {
            addActionListener { action() }
        }

    private fun rebuildTreeFromSearch() {
        syncSelectionFromTree()
        rebuildTreePreservingSearch()
    }

    private fun rebuildTreePreservingSearch() {
        tree.model = DefaultTreeModel(buildCheckedRoot(searchField.text))
        expandAllRows()
        updateSummary()
    }

    private fun buildCheckedRoot(query: String): CheckedTreeNode {
        val visibleRoutes = selectionModel.visibleRoutes(query)
        val root = RouteTreeBuilder.buildTree(visibleRoutes, project.name, project.basePath)
        return toCheckedNode(root)
    }

    private fun toCheckedNode(node: RouteTreeBuilder.RouteTreeNode): CheckedTreeNode {
        val checkedNode = CheckedTreeNode(node.item)
        node.children.forEach { checkedNode.add(toCheckedNode(it)) }
        applyCheckedState(checkedNode)
        return checkedNode
    }

    private fun applyCheckedState(node: CheckedTreeNode): Boolean {
        val item = node.userObject
        if (item is RouteTreeItem.Leaf) {
            val checked = selectionModel.isSelected(item.route)
            node.setChecked(checked)
            return checked
        }

        var checkedChildren = 0
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            if (applyCheckedState(child)) checkedChildren++
        }
        val allChecked = node.childCount > 0 && checkedChildren == node.childCount
        node.setChecked(allChecked)
        return allChecked
    }

    private fun syncSelectionFromTree() {
        collectSelection(tree.model.root as? CheckedTreeNode ?: return)
        updateSummary()
    }

    private fun collectSelection(node: CheckedTreeNode) {
        when (val item = node.userObject) {
            is RouteTreeItem.Leaf -> selectionModel.setSelected(item.route, node.isChecked)
            else -> {
                for (i in 0 until node.childCount) {
                    collectSelection(node.getChildAt(i) as? CheckedTreeNode ?: continue)
                }
            }
        }
    }

    private fun expandAllRows() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun updateSummary() {
        summaryLabel.text = MyMessageBundle.message(
            "route.list.export.dialog.selection.summary",
            selectionModel.selectedCount,
            selectionModel.totalCount,
        )
    }

    private fun updateOkState() {
        isOKActionEnabled = titleField.text.isNotBlank() && versionField.text.isNotBlank()
    }

    private fun formatComboModel(): ComboBoxModel<ApiDocumentFormat> =
        DefaultComboBoxModel(ApiDocumentFormat.entries.toTypedArray()).apply {
            selectedItem = savedFormat()
        }

    private fun savedFormat(): ApiDocumentFormat =
        ApiDocumentFormat.entries.firstOrNull { it.name == settings.state.lastExportFormat }
            ?: ApiDocumentFormat.OPENAPI_JSON

    private fun defaultTitle(): String =
        settings.state.lastExportTitle.ifBlank { "${project.name} API" }

    private fun defaultVersion(): String =
        settings.state.lastExportVersion.ifBlank { "1.0.0" }

    private fun defaultDescription(): String =
        settings.state.lastExportDescription.ifBlank {
            MyMessageBundle.message("route.list.export.description")
        }

    private class ExportTreeRenderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = value as? CheckedTreeNode ?: return
            when (val item = node.userObject) {
                is RouteTreeItem.Group -> {
                    val text = if (item.level == RouteTreeLevel.ROOT) {
                        MyMessageBundle.message("route.list.tree.root", item.count)
                    } else {
                        MyMessageBundle.message("route.list.tree.group", item.title, item.count)
                    }
                    textRenderer.append(text)
                }
                is RouteTreeItem.Leaf -> {
                    val route = item.route
                    textRenderer.append(route.methodLabel(), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, route.method.color))
                    textRenderer.append("  ${route.routeName.ifBlank { route.functionName }}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    textRenderer.append("  ${route.displayPath}", SimpleTextAttributes.GRAY_ATTRIBUTES)
                }
            }
        }

        private fun RouteInfo.methodLabel(): String =
            if (method.displayName.equals("DELETE", ignoreCase = true)) "DEL" else method.displayName
    }

    companion object {
        private const val DESCRIPTION_ROWS = 3
    }
}
