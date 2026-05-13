package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.model.Assertion
import io.github.movebrickschi.restfulall.model.AssertionResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * F7: 断言编辑表格 + 最近一次执行结果展示。
 *
 * 表格列：[enabled] · [expression] · [operator] · [expected] · 删除
 * 当前简化版固定 source = JSON_PATH（覆盖 80%+ 场景）；source 选择留给 v1.3.2 完整版。
 * 不做持久化（不挂 PluginSettingsState），跨 IDE 重启会丢——MVP 阶段足够；
 * 后续接入 F3 `CollectionItem.assertions` 持久化。
 */
class AssertionTablePanel : JPanel(BorderLayout()) {

    private val rows = mutableListOf(Row())
    private val model = Model()
    val table = JBTable(model)

    private val resultLabel = JBLabel(" ").apply {
        border = JBUI.Borders.emptyLeft(8)
    }
    private val runButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Re-run last response's assertions"
        isVisible = false
    }

    /** 用户点击「重跑」按钮时回调；由 [io.github.movebrickschi.restfulall.ui.ApiDebugPanel] 提供。 */
    var onRerunRequested: (() -> Unit)? = null

    init {
        table.setShowGrid(true)
        table.rowHeight = 26
        table.tableHeader.reorderingAllowed = false
        table.putClientProperty("terminateEditOnFocusLost", true)

        table.columnModel.getColumn(COL_ENABLED).apply {
            preferredWidth = 32; maxWidth = 32; minWidth = 32
        }
        table.columnModel.getColumn(COL_OPERATOR).apply {
            preferredWidth = 110; maxWidth = 140; minWidth = 80
            cellEditor = DefaultCellEditor(JComboBox(OPERATOR_DISPLAY))
        }
        table.columnModel.getColumn(COL_DELETE).apply {
            preferredWidth = 32; maxWidth = 32; minWidth = 32
            cellRenderer = DeleteRenderer()
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (col == COL_DELETE && row >= 0 && rows.size > 1) {
                    if (table.isEditing) table.cellEditor?.stopCellEditing()
                    rows.removeAt(row)
                    model.fireTableDataChanged()
                }
            }
        })

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(resultLabel)
            add(runButton)
        }
        runButton.addActionListener { onRerunRequested?.invoke() }

        add(topBar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun getAssertions(): List<Assertion> {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        return rows
            .filter { it.expression.isNotBlank() }
            .map {
                Assertion(
                    enabled = it.enabled,
                    source = Assertion.Source.JSON_PATH,
                    expression = it.expression,
                    operator = OPERATOR_FROM_DISPLAY[it.operatorDisplay] ?: Assertion.Operator.EQUALS,
                    expected = it.expected,
                )
            }
    }

    fun showResults(results: List<AssertionResult>) {
        if (results.isEmpty()) {
            resultLabel.text = " "
            runButton.isVisible = false
            return
        }
        val passed = results.count { it.passed }
        val failed = results.size - passed
        resultLabel.text = buildString {
            append("$passed passed")
            if (failed > 0) append(" · $failed failed")
        }
        resultLabel.foreground = if (failed == 0)
            JBColor(Color(0x33, 0x90, 0x55), Color(0x6C, 0xB8, 0x73))
        else
            JBColor(Color(0xC0, 0x39, 0x2B), Color(0xE0, 0x6C, 0x75))
        runButton.isVisible = true
    }

    private data class Row(
        var enabled: Boolean = true,
        var expression: String = "",
        var operatorDisplay: String = OPERATOR_DISPLAY[0],
        var expected: String = "",
    )

    private inner class Model : AbstractTableModel() {
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 5
        override fun getColumnName(column: Int) = when (column) {
            COL_ENABLED -> ""
            COL_EXPRESSION -> "JSON Path"
            COL_OPERATOR -> "Op"
            COL_EXPECTED -> "Expected"
            COL_DELETE -> ""
            else -> ""
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ENABLED -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex != COL_DELETE

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = when (columnIndex) {
            COL_ENABLED -> rows[rowIndex].enabled
            COL_EXPRESSION -> rows[rowIndex].expression
            COL_OPERATOR -> rows[rowIndex].operatorDisplay
            COL_EXPECTED -> rows[rowIndex].expected
            COL_DELETE -> "✕"
            else -> ""
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            when (columnIndex) {
                COL_ENABLED -> rows[rowIndex].enabled = aValue as Boolean
                COL_EXPRESSION -> {
                    rows[rowIndex].expression = (aValue as String).trim()
                    ensureEmptyRow()
                }
                COL_OPERATOR -> rows[rowIndex].operatorDisplay = aValue as String
                COL_EXPECTED -> rows[rowIndex].expected = aValue as String
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }
    }

    private fun ensureEmptyRow() {
        val last = rows.lastOrNull()
        if (last == null || last.expression.isNotBlank()) {
            rows.add(Row())
            model.fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }
    }

    private class DeleteRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int,
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
        private const val COL_ENABLED = 0
        private const val COL_EXPRESSION = 1
        private const val COL_OPERATOR = 2
        private const val COL_EXPECTED = 3
        private const val COL_DELETE = 4

        private val OPERATOR_DISPLAY = arrayOf(
            "equals", "!=", "contains", "exists", "not exists",
            ">", "<", "regex",
        )

        private val OPERATOR_FROM_DISPLAY = mapOf(
            "equals" to Assertion.Operator.EQUALS,
            "!=" to Assertion.Operator.NOT_EQUALS,
            "contains" to Assertion.Operator.CONTAINS,
            "exists" to Assertion.Operator.EXISTS,
            "not exists" to Assertion.Operator.NOT_EXISTS,
            ">" to Assertion.Operator.GREATER_THAN,
            "<" to Assertion.Operator.LESS_THAN,
            "regex" to Assertion.Operator.MATCHES_REGEX,
        )
    }
}
