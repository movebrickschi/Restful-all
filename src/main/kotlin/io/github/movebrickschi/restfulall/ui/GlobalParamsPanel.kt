package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.model.GlobalParamsData
import io.github.movebrickschi.restfulall.model.ParamEntry
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class GlobalParamsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val queryPanel = ParamTablePanel()
    private val headersPanel = ParamTablePanel()
    private val cookiesPanel = ParamTablePanel()
    private val bodyTextArea = JsonSyntaxTextPane(editable = true)
    private val tabs = JBTabbedPane()

    init {
        border = JBUI.Borders.empty(2, 4, 4, 4)
        loadFromState()
        setupUI()
    }

    private fun setupUI() {
        tabs.apply {
            addTab("Query", wrapWithTopButtons(queryPanel) { clearQueryPanel() })
            addTab("Body", createBodyPanel())
            addTab("Headers", wrapWithTopButtons(headersPanel) { clearHeadersPanel() })
            addTab("Cookies", wrapWithTopButtons(cookiesPanel) { clearCookiesPanel() })
        }
        add(tabs, BorderLayout.CENTER)

        val hintLabel = JBLabel("提示: 全局参数会自动追加到每次请求中，局部参数中同名的参数会覆盖全局参数").apply {
            font = font.deriveFont(11f)
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(4, 0, 0, 0)
        }
        add(hintLabel, BorderLayout.SOUTH)
    }

    private fun createTopButtons(onClear: () -> Unit): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            val saveButton = JButton(AllIcons.Actions.MenuSaveall).apply {
                toolTipText = "保存"
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = java.awt.Dimension(28, 28)
                addActionListener { saveToState() }
            }
            add(saveButton)

            val clearButton = JButton(AllIcons.Actions.GC).apply {
                toolTipText = "清空"
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = java.awt.Dimension(28, 28)
                addActionListener { onClear(); saveToState() }
            }
            add(clearButton)
        }
    }

    private fun wrapWithTopButtons(content: JPanel, onClear: () -> Unit): JPanel {
        return JPanel(BorderLayout()).apply {
            add(createTopButtons(onClear), BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    private fun createBodyPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

        val saveButton = JButton(AllIcons.Actions.MenuSaveall).apply {
            toolTipText = "保存"
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = java.awt.Dimension(28, 28)
            addActionListener { saveToState() }
        }
        toolbar.add(saveButton)

        val clearButton = JButton(AllIcons.Actions.GC).apply {
            toolTipText = "清空"
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = java.awt.Dimension(28, 28)
            addActionListener { bodyTextArea.text = ""; saveToState() }
        }
        toolbar.add(clearButton)

        val formatButton = JButton(AllIcons.Actions.ReformatCode).apply {
            toolTipText = "格式化 JSON"
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = java.awt.Dimension(28, 28)
            addActionListener {
                val formatted = formatJson(bodyTextArea.text)
                if (formatted != null) {
                    bodyTextArea.text = formatted
                    bodyTextArea.caretPosition = 0
                }
            }
        }
        toolbar.add(formatButton)

        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)
        return panel
    }

    private fun loadFromState() {
        val state = PluginSettingsState.getInstance(project)
        val params = state.getGlobalParams()

        if (params.queryParams.isNotEmpty()) {
            queryPanel.setParams(params.queryParams.map { it.name to it.value })
        }
        if (params.headerParams.isNotEmpty()) {
            headersPanel.setParams(params.headerParams.map { it.name to it.value })
        }
        if (params.cookieParams.isNotEmpty()) {
            cookiesPanel.setParams(params.cookieParams.map { it.name to it.value })
        }
        bodyTextArea.text = params.bodyContent
    }

    private fun saveToState() {
        val state = PluginSettingsState.getInstance(project)
        val data = GlobalParamsData(
            queryParams = queryPanel.getParams().map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            headerParams = headersPanel.getParams().map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            cookieParams = cookiesPanel.getParams().map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            bodyContent = bodyTextArea.text,
        )
        state.setGlobalParams(data)
    }

    private fun clearQueryPanel() { queryPanel.clear() }
    private fun clearHeadersPanel() { headersPanel.clear() }
    private fun clearCookiesPanel() { cookiesPanel.clear() }

    companion object {
        private const val INDENT = "  "
        private val WHITESPACE = charArrayOf(' ', '\n', '\r', '\t')

        fun formatJson(text: String): String? {
            val trimmed = text.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null

            return try {
                val sb = StringBuilder()
                var indent = 0
                var inString = false
                var escaped = false

                for (ch in trimmed) {
                    when {
                        escaped -> {
                            sb.append(ch); escaped = false
                        }
                        ch == '\\' && inString -> {
                            sb.append(ch); escaped = true
                        }
                        ch == '"' -> {
                            inString = !inString; sb.append(ch)
                        }
                        inString -> sb.append(ch)
                        ch == '{' || ch == '[' -> {
                            sb.append(ch).append('\n'); indent++; sb.append(INDENT.repeat(indent))
                        }
                        ch == '}' || ch == ']' -> {
                            sb.append('\n'); indent--; sb.append(INDENT.repeat(indent)).append(ch)
                        }
                        ch == ',' -> {
                            sb.append(ch).append('\n').append(INDENT.repeat(indent))
                        }
                        ch == ':' -> sb.append(": ")
                        ch in WHITESPACE -> {}
                        else -> sb.append(ch)
                    }
                }
                sb.toString()
            } catch (_: Exception) {
                null
            }
        }
    }
}
