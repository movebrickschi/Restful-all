package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class FormDataParamPanel : JPanel(BorderLayout()) {

    private val params = mutableListOf(FormDataRow(true, "", "", "text"))
    private val tableModel = FormDataModel()
    val table = JBTable(tableModel)

    init {
        table.setShowGrid(true)
        table.rowHeight = 28
        table.tableHeader.reorderingAllowed = false
        table.putClientProperty("terminateEditOnFocusLost", true)

        table.columnModel.getColumn(COL_ENABLED).apply {
            preferredWidth = 32
            maxWidth = 32
            minWidth = 32
        }
        table.columnModel.getColumn(COL_NAME).preferredWidth = 150
        table.columnModel.getColumn(COL_VALUE).preferredWidth = 200
        table.columnModel.getColumn(COL_TYPE).apply {
            preferredWidth = 80
            maxWidth = 100
            cellEditor = DefaultCellEditor(JComboBox(arrayOf("text", "file")))
        }
        table.columnModel.getColumn(COL_DELETE).apply {
            preferredWidth = 32
            maxWidth = 32
            minWidth = 32
            cellRenderer = DeleteCellRenderer()
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0) return

                if (col == COL_DELETE && params.size > 1) {
                    if (table.isEditing) table.cellEditor?.stopCellEditing()
                    params.removeAt(row)
                    tableModel.fireTableDataChanged()
                } else if (col == COL_VALUE && params[row].type == "file") {
                    chooseFile(row)
                }
            }
        })

        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    private fun chooseFile(row: Int) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
        val file = FileChooser.chooseFile(descriptor, null, null)
        if (file != null) {
            params[row].value = file.path
            tableModel.fireTableCellUpdated(row, COL_VALUE)
        }
    }

    fun getParams(): List<Triple<String, String, String>> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return params.filter { it.enabled && it.name.isNotBlank() }
            .map { Triple(it.name, it.value, it.type) }
    }

    fun getParamPairs(): List<Pair<String, String>> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return params.filter { it.enabled && it.name.isNotBlank() }
            .map { it.name to it.value }
    }

    fun setParams(list: List<Pair<String, String>>) {
        params.clear()
        for ((name, value) in list) {
            params.add(FormDataRow(true, name, value, "text"))
        }
        params.add(FormDataRow(true, "", "", "text"))
        tableModel.fireTableDataChanged()
    }

    fun clear() {
        params.clear()
        params.add(FormDataRow(true, "", "", "text"))
        tableModel.fireTableDataChanged()
    }

    data class FormDataRow(var enabled: Boolean, var name: String, var value: String, var type: String)

    private inner class FormDataModel : AbstractTableModel() {
        override fun getRowCount() = params.size
        override fun getColumnCount() = 5
        override fun getColumnName(column: Int) = when (column) {
            COL_ENABLED -> ""
            COL_NAME -> "参数名"
            COL_VALUE -> "参数值"
            COL_TYPE -> "参数类型"
            COL_DELETE -> ""
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ENABLED -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            if (columnIndex == COL_DELETE) return false
            if (columnIndex == COL_VALUE && params[rowIndex].type == "file") return false
            return true
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            COL_ENABLED -> params[rowIndex].enabled
            COL_NAME -> params[rowIndex].name
            COL_VALUE -> params[rowIndex].value
            COL_TYPE -> params[rowIndex].type
            COL_DELETE -> "✕"
            else -> ""
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            when (columnIndex) {
                COL_ENABLED -> params[rowIndex].enabled = aValue as Boolean
                COL_NAME -> {
                    params[rowIndex].name = aValue as String
                    ensureEmptyRow()
                }
                COL_VALUE -> {
                    params[rowIndex].value = aValue as String
                    ensureEmptyRow()
                }
                COL_TYPE -> {
                    params[rowIndex].type = aValue as String
                    if (aValue == "file") params[rowIndex].value = ""
                    fireTableRowsUpdated(rowIndex, rowIndex)
                }
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private fun ensureEmptyRow() {
        val last = params.lastOrNull()
        if (last == null || last.name.isNotBlank() || last.value.isNotBlank()) {
            params.add(FormDataRow(true, "", "", "text"))
            tableModel.fireTableRowsInserted(params.size - 1, params.size - 1)
        }
    }

    private class DeleteCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = JLabel("✕", SwingConstants.CENTER)
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.foreground = if (isSelected) table.selectionForeground else java.awt.Color.GRAY
            if (isSelected) {
                label.isOpaque = true
                label.background = table.selectionBackground
            }
            return label
        }
    }

    companion object {
        private const val COL_ENABLED = 0
        private const val COL_NAME = 1
        private const val COL_VALUE = 2
        private const val COL_TYPE = 3
        private const val COL_DELETE = 4
    }
}
