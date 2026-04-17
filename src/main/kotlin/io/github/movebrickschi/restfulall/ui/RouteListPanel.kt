package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.RouteService
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class RouteListPanel(
    private val project: Project,
    private val onDebugRoute: (RouteInfo) -> Unit,
) : JPanel(BorderLayout()) {

    private val allRoutes = mutableListOf<RouteInfo>()
    private val filteredRoutes = mutableListOf<RouteInfo>()
    private val tableModel = RouteTableModel()
    private val table = JBTable(tableModel)
    private val statusLabel = JBLabel()
    private val searchField = JBTextField()
    private val refreshButton = JButton(AllIcons.Actions.Refresh)
    private val debugCellRenderer = IconCellRenderer(AllIcons.Actions.Execute, "")
    private val navCellRenderer = IconCellRenderer(AllIcons.Actions.EditSource, "")

    private val debounceTimer = Timer(150) { filterRoutes() }.apply { isRepeats = false }

    init {
        setupToolbar()
        setupTable()
        applyI18n()
        loadRoutes()

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    private fun setupToolbar() {
        val toolbar = JPanel(BorderLayout(2, 0)).apply {
            border = JBUI.Borders.empty(2, 4)

            add(searchField, BorderLayout.CENTER)

            refreshButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener { refreshRoutes() }
            }
            add(refreshButton, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = debounceTimer.restart()
            override fun removeUpdate(e: DocumentEvent) = debounceTimer.restart()
            override fun changedUpdate(e: DocumentEvent) = debounceTimer.restart()
        })

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        val next = (table.selectedRow + 1).coerceAtMost(filteredRoutes.size - 1)
                        table.setRowSelectionInterval(next, next)
                        table.scrollRectToVisible(table.getCellRect(next, 0, true))
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        val prev = (table.selectedRow - 1).coerceAtLeast(0)
                        table.setRowSelectionInterval(prev, prev)
                        table.scrollRectToVisible(table.getCellRect(prev, 0, true))
                        e.consume()
                    }
                }
            }
        })
    }

    private fun setupTable() {
        table.apply {
            setShowGrid(false)
            intercellSpacing = Dimension(0, 0)
            rowHeight = 28
            tableHeader.reorderingAllowed = false
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION

            columnModel.getColumn(COL_METHOD).apply {
                preferredWidth = 70
                maxWidth = 80
                minWidth = 60
                cellRenderer = MethodCellRenderer()
            }
            columnModel.getColumn(COL_PATH).apply {
                preferredWidth = 300
            }
            columnModel.getColumn(COL_DEBUG).apply {
                preferredWidth = 32
                maxWidth = 32
                minWidth = 32
                cellRenderer = debugCellRenderer
            }
            columnModel.getColumn(COL_NAV).apply {
                preferredWidth = 32
                maxWidth = 32
                minWidth = 32
                cellRenderer = navCellRenderer
            }
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || row >= filteredRoutes.size) return

                when {
                    col == COL_DEBUG -> onDebugRoute(filteredRoutes[row])
                    col == COL_NAV -> navigateToSource(filteredRoutes[row])
                    e.clickCount == 2 -> onDebugRoute(filteredRoutes[row])
                }
            }
        })

        val scrollPane = JBScrollPane(table)
        add(scrollPane, BorderLayout.CENTER)

        statusLabel.apply {
            font = font.deriveFont(11f)
            foreground = Color.GRAY
            border = JBUI.Borders.empty(2, 8)
        }
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun applyI18n() {
        searchField.emptyText.text = MyMessageBundle.message("route.list.search.placeholder")
        refreshButton.toolTipText = MyMessageBundle.message("route.list.refresh.tooltip")
        debugCellRenderer.tooltip = MyMessageBundle.message("route.list.icon.debug")
        navCellRenderer.tooltip = MyMessageBundle.message("route.list.icon.navigate")
        tableModel.fireTableStructureChanged()
        table.columnModel.getColumn(COL_METHOD).apply {
            preferredWidth = 70
            maxWidth = 80
            minWidth = 60
            cellRenderer = MethodCellRenderer()
        }
        table.columnModel.getColumn(COL_DEBUG).apply {
            preferredWidth = 32
            maxWidth = 32
            minWidth = 32
            cellRenderer = debugCellRenderer
        }
        table.columnModel.getColumn(COL_NAV).apply {
            preferredWidth = 32
            maxWidth = 32
            minWidth = 32
            cellRenderer = navCellRenderer
        }
        updateStatusLabel()
    }

    private fun updateStatusLabel() {
        val query = searchField.text.trim().lowercase()
        statusLabel.text = if (query.isBlank()) {
            if (filteredRoutes.isEmpty()) MyMessageBundle.message("route.list.empty")
            else MyMessageBundle.message("route.list.count", filteredRoutes.size)
        } else {
            MyMessageBundle.message("route.list.matched", filteredRoutes.size, allRoutes.size)
        }
    }

    private fun loadRoutes() {
        val routeService = RouteService.getInstance(project)
        if (routeService.isInitialScanDone) {
            updateRoutes(routeService.getCachedRoutes())
        } else {
            statusLabel.text = MyMessageBundle.message("route.list.empty")
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project, MyMessageBundle.message("route.list.task.scanning"), true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    routeService.scanProject()
                }

                override fun onSuccess() {
                    ApplicationManager.getApplication().invokeLater {
                        updateRoutes(routeService.getCachedRoutes())
                    }
                }
            })
        }
    }

    private fun refreshRoutes() {
        val routeService = RouteService.getInstance(project)
        if (routeService.isScanning) return

        statusLabel.text = MyMessageBundle.message("route.list.scanning")
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, MyMessageBundle.message("route.list.task.rescanning"), true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                routeService.scanProject()
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    updateRoutes(routeService.getCachedRoutes())
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = MyMessageBundle.message("route.list.scan.cancelled")
                }
            }
        })
    }

    private fun updateRoutes(newRoutes: List<RouteInfo>) {
        allRoutes.clear()
        allRoutes.addAll(newRoutes)
        filterRoutes()
    }

    private fun filterRoutes() {
        val query = searchField.text.trim().lowercase()
        filteredRoutes.clear()

        if (query.isBlank()) {
            filteredRoutes.addAll(allRoutes)
        } else {
            filteredRoutes.addAll(allRoutes.filter { it.searchKey.contains(query) })
        }

        tableModel.fireTableDataChanged()

        updateStatusLabel()

        if (filteredRoutes.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
        }
    }

    private fun navigateToSource(route: RouteInfo) {
        OpenFileDescriptor(project, route.file, route.lineNumber, 0).navigate(true)
    }

    private inner class RouteTableModel : AbstractTableModel() {
        override fun getRowCount() = filteredRoutes.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = when (column) {
            COL_METHOD -> MyMessageBundle.message("route.list.column.method")
            COL_PATH -> MyMessageBundle.message("route.list.column.path")
            COL_DEBUG -> ""
            COL_NAV -> ""
            else -> ""
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val route = filteredRoutes[rowIndex]
            return when (columnIndex) {
                COL_METHOD -> route.method.displayName
                COL_PATH -> route.displayPath
                COL_DEBUG -> ""
                COL_NAV -> ""
                else -> ""
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = false
    }

    private class MethodCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            label.horizontalAlignment = CENTER
            label.font = label.font.deriveFont(Font.BOLD, 11f)
            if (!isSelected) {
                label.foreground = HttpMethod.fromString(value.toString())?.color ?: Color.GRAY
            }
            return label
        }
    }

    private class IconCellRenderer(
        private val icon: Icon,
        var tooltip: String,
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            return JLabel(icon).apply {
                horizontalAlignment = CENTER
                toolTipText = tooltip
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                isOpaque = true
                background = if (isSelected) table.selectionBackground else table.background
            }
        }
    }

    companion object {
        private const val COL_METHOD = 0
        private const val COL_PATH = 1
        private const val COL_DEBUG = 2
        private const val COL_NAV = 3
    }
}
