package io.github.movebrickschi.restfulall.ui

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

class ParamTablePanel : JPanel(BorderLayout()) {

    private val params = mutableListOf(ParamRow(true, "", ""))
    private val tableModel = ParamModel()
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
                if (col == COL_DELETE && row >= 0 && params.size > 1) {
                    if (table.isEditing) table.cellEditor?.stopCellEditing()
                    params.removeAt(row)
                    tableModel.fireTableDataChanged()
                }
            }
        })

        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun getParams(): List<Pair<String, String>> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return params.filter { it.enabled && it.name.isNotBlank() }
            .map { it.name to it.value }
    }

    fun setParams(list: List<Pair<String, String>>) {
        params.clear()
        for ((name, value) in list) {
            params.add(ParamRow(true, name, value))
        }
        params.add(ParamRow(true, "", ""))
        tableModel.fireTableDataChanged()
    }

    fun addParam(name: String, value: String) {
        val lastIndex = (params.size - 1).coerceAtLeast(0)
        params.add(lastIndex, ParamRow(true, name, value))
        tableModel.fireTableDataChanged()
    }

    fun clear() {
        params.clear()
        params.add(ParamRow(true, "", ""))
        tableModel.fireTableDataChanged()
    }

    data class ParamRow(var enabled: Boolean, var name: String, var value: String)

    private inner class ParamModel : AbstractTableModel() {
        override fun getRowCount() = params.size
        override fun getColumnCount() = 4
        override fun getColumnName(column: Int) = when (column) {
            COL_ENABLED -> ""
            COL_NAME -> "参数名"
            COL_VALUE -> "参数值"
            COL_DELETE -> ""
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ENABLED -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex != COL_DELETE

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            COL_ENABLED -> params[rowIndex].enabled
            COL_NAME -> params[rowIndex].name
            COL_VALUE -> params[rowIndex].value
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
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private fun ensureEmptyRow() {
        val last = params.lastOrNull()
        if (last == null || last.name.isNotBlank() || last.value.isNotBlank()) {
            params.add(ParamRow(true, "", ""))
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
        private const val COL_DELETE = 3
    }
}
