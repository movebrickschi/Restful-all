package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.BaseUrlEntry
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import io.github.movebrickschi.restfulall.service.RouteService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class BaseUrlPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val entries = mutableListOf<BaseUrlEntry>()
    private val tableModel = BaseUrlTableModel()
    private val table = JBTable(tableModel)

    private val titleLabel = JBLabel().apply { font = font.deriveFont(Font.BOLD, 13f) }
    private val detectButton = JButton(AllIcons.Actions.Find)
    private val addButton = JButton(AllIcons.General.Add)
    private val removeButton = JButton(AllIcons.General.Remove)
    private val saveButton = JButton(AllIcons.Actions.MenuSaveall)
    private val hintLabel = JBLabel().apply {
        font = font.deriveFont(11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(4, 0, 0, 0)
    }

    init {
        border = JBUI.Borders.empty(2, 4, 4, 4)
        loadFromState()
        setupUI()
        applyI18n()

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    private fun setupUI() {
        val toolbar = JPanel(BorderLayout(2, 0)).apply {
            border = JBUI.Borders.empty(2, 0, 4, 0)
            add(titleLabel, BorderLayout.WEST)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))

            detectButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { detectModules() }
            }
            buttonPanel.add(detectButton)

            addButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { addEntry() }
            }
            buttonPanel.add(addButton)

            removeButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { removeSelectedEntry() }
            }
            buttonPanel.add(removeButton)

            saveButton.apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                addActionListener { saveToState() }
            }
            buttonPanel.add(saveButton)

            add(buttonPanel, BorderLayout.EAST)
        }
        add(toolbar, BorderLayout.NORTH)

        table.apply {
            setShowGrid(true)
            rowHeight = 30
            tableHeader.reorderingAllowed = false
            putClientProperty("terminateEditOnFocusLost", true)

            columnModel.getColumn(COL_MODULE).preferredWidth = 160
            columnModel.getColumn(COL_TYPE).apply {
                preferredWidth = 100
                maxWidth = 120
            }
            columnModel.getColumn(COL_SERVER).preferredWidth = 120
            columnModel.getColumn(COL_PORT).apply {
                preferredWidth = 70
                maxWidth = 80
            }
            columnModel.getColumn(COL_CONTEXT_PATH).preferredWidth = 120
            columnModel.getColumn(COL_PREVIEW).apply {
                preferredWidth = 200
                cellRenderer = PreviewCellRenderer()
            }
            columnModel.getColumn(COL_DELETE).apply {
                preferredWidth = 32
                maxWidth = 32
                minWidth = 32
                cellRenderer = DeleteCellRenderer()
            }
        }
        refreshTypeEditor()

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (col == COL_DELETE && row >= 0 && row < entries.size) {
                    if (table.isEditing) table.cellEditor?.stopCellEditing()
                    entries.removeAt(row)
                    tableModel.fireTableDataChanged()
                    saveToState()
                }
            }
        })

        add(JBScrollPane(table), BorderLayout.CENTER)
        add(hintLabel, BorderLayout.SOUTH)
    }

    private fun applyI18n() {
        titleLabel.text = MyMessageBundle.message("base.url.title")
        detectButton.toolTipText = MyMessageBundle.message("base.url.detect.tooltip")
        addButton.toolTipText = MyMessageBundle.message("base.url.add.tooltip")
        removeButton.toolTipText = MyMessageBundle.message("base.url.remove.tooltip")
        saveButton.toolTipText = MyMessageBundle.message("base.url.save.tooltip")
        hintLabel.text = MyMessageBundle.message("base.url.hint")
        refreshTypeEditor()
        tableModel.fireTableStructureChanged()
        table.columnModel.getColumn(COL_MODULE).preferredWidth = 160
        table.columnModel.getColumn(COL_TYPE).apply {
            preferredWidth = 100
            maxWidth = 120
        }
        table.columnModel.getColumn(COL_SERVER).preferredWidth = 120
        table.columnModel.getColumn(COL_PORT).apply {
            preferredWidth = 70
            maxWidth = 80
        }
        table.columnModel.getColumn(COL_CONTEXT_PATH).preferredWidth = 120
        table.columnModel.getColumn(COL_PREVIEW).apply {
            preferredWidth = 200
            cellRenderer = PreviewCellRenderer()
        }
        table.columnModel.getColumn(COL_DELETE).apply {
            preferredWidth = 32
            maxWidth = 32
            minWidth = 32
            cellRenderer = DeleteCellRenderer()
        }
    }

    private fun refreshTypeEditor() {
        val autoLabel = MyMessageBundle.message("base.url.type.auto")
        val manualLabel = MyMessageBundle.message("base.url.type.manual")
        table.columnModel.getColumn(COL_TYPE).cellEditor =
            DefaultCellEditor(JComboBox(arrayOf(autoLabel, manualLabel)))
    }

    private fun loadFromState() {
        val state = PluginSettingsState.getInstance(project)
        entries.clear()
        entries.addAll(state.getBaseUrls())
    }

    private fun saveToState() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        val state = PluginSettingsState.getInstance(project)
        state.setBaseUrls(entries.toList())
        tableModel.fireTableDataChanged()
    }

    private fun addEntry() {
        entries.add(BaseUrlEntry())
        tableModel.fireTableRowsInserted(entries.size - 1, entries.size - 1)
    }

    private fun removeSelectedEntry() {
        val row = table.selectedRow
        if (row >= 0 && row < entries.size) {
            if (table.isEditing) table.cellEditor?.stopCellEditing()
            entries.removeAt(row)
            tableModel.fireTableDataChanged()
            saveToState()
        }
    }

    private fun detectModules() {
        val routeService = RouteService.getInstance(project)
        val routes = routeService.getCachedRoutes()
        val existingModules = entries.map { it.moduleName }.toSet()

        val moduleNames = routes.mapNotNull { route ->
            detectModuleName(route.file.path)
        }.distinct().filter { it !in existingModules }

        for (name in moduleNames) {
            val entry = BaseUrlEntry(moduleName = name)
            tryAutoDetect(entry)
            entries.add(entry)
        }

        tableModel.fireTableDataChanged()
        if (moduleNames.isNotEmpty()) saveToState()
    }

    private fun detectModuleName(filePath: String): String? {
        val basePath = project.basePath ?: return null
        val normalizedFile = filePath.replace("\\", "/")
        val normalizedBase = basePath.replace("\\", "/")

        if (!normalizedFile.startsWith(normalizedBase)) return null

        val relative = normalizedFile.removePrefix(normalizedBase).trimStart('/')
        val parts = relative.split("/")

        for (i in parts.indices) {
            val dir = "$normalizedBase/${parts.take(i + 1).joinToString("/")}"
            val dirFile = java.io.File(dir)
            if (dirFile.isDirectory) {
                val hasBuildFile = dirFile.listFiles()?.any {
                    it.name in setOf("build.gradle", "build.gradle.kts", "pom.xml", "package.json")
                } == true
                if (hasBuildFile && dir != normalizedBase) {
                    return parts[i]
                }
            }
        }

        return project.name
    }

    private fun tryAutoDetect(entry: BaseUrlEntry) {
        val basePath = project.basePath ?: return
        val modulePath = if (entry.moduleName == project.name) basePath
        else "$basePath/${entry.moduleName}"

        val resourcesDir = java.io.File(modulePath, "src/main/resources")
        if (!resourcesDir.exists()) return

        val propsFile = java.io.File(resourcesDir, "application.properties")
        if (propsFile.exists()) {
            val props = java.util.Properties()
            propsFile.inputStream().use { props.load(it) }
            props.getProperty("server.port")?.toIntOrNull()?.let { entry.port = it }
            props.getProperty("server.servlet.context-path")?.let { entry.contextPath = it }
            entry.type = BaseUrlEntry.TYPE_AUTO
            return
        }

        val ymlFile = java.io.File(resourcesDir, "application.yml")
        if (ymlFile.exists()) {
            parseSimpleYaml(ymlFile, entry)
            entry.type = BaseUrlEntry.TYPE_AUTO
        }
    }

    private fun parseSimpleYaml(file: java.io.File, entry: BaseUrlEntry) {
        val lines = file.readLines()
        var inServer = false
        var inServlet = false
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("server:") -> inServer = true
                inServer && trimmed.startsWith("port:") -> {
                    trimmed.substringAfter("port:").trim().toIntOrNull()?.let { entry.port = it }
                }
                inServer && trimmed.startsWith("servlet:") -> inServlet = true
                inServlet && trimmed.startsWith("context-path:") -> {
                    entry.contextPath = trimmed.substringAfter("context-path:").trim()
                }
                !line.startsWith(" ") && !line.startsWith("\t") && !trimmed.startsWith("server:") -> {
                    inServer = false
                    inServlet = false
                }
            }
        }
    }

    private inner class BaseUrlTableModel : AbstractTableModel() {
        override fun getRowCount() = entries.size
        override fun getColumnCount() = 7
        override fun getColumnName(column: Int) = when (column) {
            COL_MODULE -> MyMessageBundle.message("base.url.column.module")
            COL_TYPE -> MyMessageBundle.message("base.url.column.type")
            COL_SERVER -> MyMessageBundle.message("base.url.column.server")
            COL_PORT -> MyMessageBundle.message("base.url.column.port")
            COL_CONTEXT_PATH -> MyMessageBundle.message("base.url.column.context.path")
            COL_PREVIEW -> MyMessageBundle.message("base.url.column.preview")
            COL_DELETE -> ""
            else -> ""
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) =
            columnIndex != COL_PREVIEW && columnIndex != COL_DELETE

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val entry = entries[rowIndex]
            return when (columnIndex) {
                COL_MODULE -> entry.moduleName
                COL_TYPE -> if (entry.type == BaseUrlEntry.TYPE_AUTO)
                    MyMessageBundle.message("base.url.type.auto")
                else
                    MyMessageBundle.message("base.url.type.manual")
                COL_SERVER -> entry.server
                COL_PORT -> entry.port
                COL_CONTEXT_PATH -> entry.contextPath
                COL_PREVIEW -> entry.buildBaseUrl()
                COL_DELETE -> "✕"
                else -> ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val entry = entries[rowIndex]
            when (columnIndex) {
                COL_MODULE -> entry.moduleName = aValue as? String ?: ""
                COL_TYPE -> entry.type = if (aValue == MyMessageBundle.message("base.url.type.auto"))
                    BaseUrlEntry.TYPE_AUTO
                else
                    BaseUrlEntry.TYPE_MANUAL
                COL_SERVER -> entry.server = aValue as? String ?: "127.0.0.1"
                COL_PORT -> entry.port = (aValue as? String)?.toIntOrNull() ?: (aValue as? Int) ?: 8080
                COL_CONTEXT_PATH -> entry.contextPath = aValue as? String ?: ""
            }
            fireTableRowsUpdated(rowIndex, rowIndex)
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
    }

    private class PreviewCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
            label.foreground = if (isSelected) table.selectionForeground else JBColor(Color(0x58, 0x9D, 0xF6), Color(0x58, 0x9D, 0xF6))
            label.font = label.font.deriveFont(Font.ITALIC)
            return label
        }
    }

    private class DeleteCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = JLabel("✕", SwingConstants.CENTER)
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.foreground = if (isSelected) table.selectionForeground else Color.GRAY
            if (isSelected) {
                label.isOpaque = true
                label.background = table.selectionBackground
            }
            return label
        }
    }

    companion object {
        private const val COL_MODULE = 0
        private const val COL_TYPE = 1
        private const val COL_SERVER = 2
        private const val COL_PORT = 3
        private const val COL_CONTEXT_PATH = 4
        private const val COL_PREVIEW = 5
        private const val COL_DELETE = 6
    }
}
