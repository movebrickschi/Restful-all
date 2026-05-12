package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.EnvironmentEntry
import io.github.movebrickschi.restfulall.model.EnvVariable
import io.github.movebrickschi.restfulall.service.EnvironmentService
import io.github.movebrickschi.restfulall.service.SecretStorageService
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * v1.3 F1 - 环境变量管理面板。
 *
 * 布局：
 * ```
 * ┌─────────────────────────────────────────────┐
 * │ [环境下拉] [+] [-] [保存]                   │  ← 顶部工具栏
 * ├─────────────────────────────────────────────┤
 * │ ☑ │ Key        │ Value       │ Secret │ Desc│  ← 变量表格
 * │ ☑ │ HOST       │ localhost   │   ☐    │ ... │
 * │ ☑ │ DB_PASS    │ ••••••      │   ☑    │ ... │
 * │ ☐ │ OLD_KEY    │ deprecated  │   ☐    │ ... │
 * ├─────────────────────────────────────────────┤
 * │ [添加变量] [删除变量]                        │  ← 底部操作栏
 * └─────────────────────────────────────────────┘
 * ```
 */
class EnvironmentPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val envService get() = EnvironmentService.getInstance(project)
    private val secretService get() = SecretStorageService.getInstance(project)

    private val envCombo = JComboBox<EnvComboItem>().apply {
        preferredSize = Dimension(180, 28)
    }
    private val tableModel = EnvVarTableModel()
    private val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        rowHeight = 28
    }
    private var currentEnv: EnvironmentEntry? = null
    private var suppressComboEvent = false

    init {
        border = JBUI.Borders.empty(4)
        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(buildBottomBar(), BorderLayout.SOUTH)
        refreshEnvList()
    }

    private fun buildToolbar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        bar.add(JBLabel(MyMessageBundle.message("env.panel.label")))
        bar.add(envCombo)
        bar.add(iconBtn(AllIcons.General.Add) { addEnvironment() })
        bar.add(iconBtn(AllIcons.General.Remove) { deleteEnvironment() })
        bar.add(iconBtn(AllIcons.Actions.MenuSaveall) { saveCurrentEnv() })

        envCombo.addActionListener {
            if (suppressComboEvent) return@addActionListener
            val item = envCombo.selectedItem as? EnvComboItem ?: return@addActionListener
            envService.setActive(item.id)
            loadEnv(item.id)
        }
        return bar
    }

    private fun buildBottomBar(): JPanel {
        val bar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        bar.add(JButton(MyMessageBundle.message("env.var.add")).apply {
            addActionListener { addVariable() }
        })
        bar.add(JButton(MyMessageBundle.message("env.var.delete")).apply {
            addActionListener { deleteVariable() }
        })
        return bar
    }

    fun refreshEnvList() {
        suppressComboEvent = true
        try {
            val envs = envService.listEnvironments()
            val items = envs.map { EnvComboItem(it.id, it.name) }.toTypedArray()
            envCombo.model = DefaultComboBoxModel(items)
            val active = envService.getActive()
            if (active != null) {
                val idx = items.indexOfFirst { it.id == active.id }.coerceAtLeast(0)
                envCombo.selectedIndex = idx
                loadEnv(active.id)
            }
        } finally {
            suppressComboEvent = false
        }
    }

    private fun loadEnv(envId: String) {
        currentEnv = envService.findById(envId)
        tableModel.setVariables(currentEnv?.variables ?: mutableListOf())
    }

    private fun saveCurrentEnv() {
        val env = currentEnv ?: return
        for (v in env.variables) {
            if (v.secret && !v.isSecretRef()) {
                val nsKey = SecretStorageService.envKey(env.id, v.key)
                secretService.setSecret(nsKey, v.value)
                v.value = EnvVariable.secretRef(nsKey)
            }
        }
        envService.upsert(env)
    }

    private fun addEnvironment() {
        val name = JOptionPane.showInputDialog(this, MyMessageBundle.message("env.add.prompt"), "New Environment")
        if (name.isNullOrBlank()) return
        val env = EnvironmentEntry(name = name)
        envService.upsert(env)
        refreshEnvList()
    }

    private fun deleteEnvironment() {
        val env = currentEnv ?: return
        if (env.id == EnvironmentEntry.DEFAULT_ID) return
        val confirm = JOptionPane.showConfirmDialog(
            this,
            MyMessageBundle.message("env.delete.confirm", env.name),
            "Delete",
            JOptionPane.YES_NO_OPTION,
        )
        if (confirm != JOptionPane.YES_OPTION) return
        envService.delete(env.id)
        refreshEnvList()
    }

    private fun addVariable() {
        val env = currentEnv ?: return
        env.variables.add(EnvVariable())
        tableModel.fireTableRowsInserted(env.variables.size - 1, env.variables.size - 1)
    }

    private fun deleteVariable() {
        val row = table.selectedRow
        val env = currentEnv ?: return
        if (row < 0 || row >= env.variables.size) return
        env.variables.removeAt(row)
        tableModel.fireTableRowsDeleted(row, row)
    }

    private fun iconBtn(icon: javax.swing.Icon, onClick: () -> Unit): JButton =
        JButton(icon).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusable = false
            margin = JBUI.emptyInsets()
            preferredSize = Dimension(28, 28)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { onClick() }
        }

    private data class EnvComboItem(val id: String, val name: String) {
        override fun toString(): String = name
    }

    inner class EnvVarTableModel : AbstractTableModel() {
        private var vars: MutableList<EnvVariable> = mutableListOf()
        private val columns = arrayOf("Enabled", "Key", "Value", "Secret", "Description")

        fun setVariables(v: MutableList<EnvVariable>) { vars = v; fireTableDataChanged() }

        override fun getRowCount(): Int = vars.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(col: Int): String = columns[col]
        override fun getColumnClass(col: Int): Class<*> = when (col) {
            0, 3 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
        override fun isCellEditable(row: Int, col: Int): Boolean = true

        override fun getValueAt(row: Int, col: Int): Any {
            val v = vars[row]
            return when (col) {
                0 -> v.enabled
                1 -> v.key
                2 -> if (v.secret) "\u2022\u2022\u2022\u2022\u2022\u2022" else v.value
                3 -> v.secret
                4 -> v.description
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            val v = vars[row]
            when (col) {
                0 -> v.enabled = value as Boolean
                1 -> v.key = value as String
                2 -> {
                    if (v.secret) {
                        val nsKey = SecretStorageService.envKey(currentEnv?.id ?: "", v.key)
                        secretService.setSecret(nsKey, value as String)
                        v.value = EnvVariable.secretRef(nsKey)
                    } else {
                        v.value = value as String
                    }
                }
                3 -> v.secret = value as Boolean
                4 -> v.description = value as String
            }
            fireTableCellUpdated(row, col)
        }
    }
}
