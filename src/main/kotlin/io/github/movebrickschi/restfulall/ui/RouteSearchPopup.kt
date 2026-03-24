package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RouteSearchPopup(
    private val project: Project,
    private val allRoutes: List<RouteInfo>,
    private val initialQuery: String? = null,
) {
    private val listModel = DefaultListModel<RouteInfo>()
    private val routeList = JBList(listModel)
    private val searchField = JBTextField()
    private val statusLabel = JLabel()
    private var popup: JBPopup? = null
    private val debounceTimer = Timer(DEBOUNCE_MS) { updateList(searchField.text) }.apply {
        isRepeats = false
    }

    init {
        setupUI()
        if (!initialQuery.isNullOrBlank()) {
            searchField.text = initialQuery
            searchField.selectAll()
        }
        updateList(searchField.text)
    }

    private fun setupUI() {
        searchField.putClientProperty("JTextField.Search.Icon", true)
        searchField.emptyText.text = "输入路由路径进行搜索... (例: /v1/action)"

        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onSearchChanged()
            override fun removeUpdate(e: DocumentEvent) = onSearchChanged()
            override fun changedUpdate(e: DocumentEvent) = onSearchChanged()
        })

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        val next = minOf(routeList.selectedIndex + 1, listModel.size() - 1)
                        routeList.selectedIndex = next
                        routeList.ensureIndexIsVisible(next)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        val prev = maxOf(routeList.selectedIndex - 1, 0)
                        routeList.selectedIndex = prev
                        routeList.ensureIndexIsVisible(prev)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        navigateToSelected()
                        e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        popup?.cancel()
                        e.consume()
                    }
                }
            }
        })

        routeList.cellRenderer = RouteCellRenderer()
        routeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        routeList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })
    }

    private fun onSearchChanged() {
        debounceTimer.restart()
    }

    private fun updateList(query: String) {
        listModel.clear()
        val lowerQuery = query.lowercase().trim()
        var totalMatched = 0

        if (lowerQuery.isBlank()) {
            totalMatched = allRoutes.size
            val limit = minOf(allRoutes.size, MAX_VISIBLE_ITEMS)
            for (i in 0 until limit) {
                listModel.addElement(allRoutes[i])
            }
        } else {
            for (route in allRoutes) {
                if (route.searchKey.contains(lowerQuery)) {
                    totalMatched++
                    if (listModel.size() < MAX_VISIBLE_ITEMS) {
                        listModel.addElement(route)
                    }
                }
            }
        }

        if (listModel.size() > 0) {
            routeList.selectedIndex = 0
        }

        statusLabel.text = if (totalMatched > MAX_VISIBLE_ITEMS) {
            "  显示前 $MAX_VISIBLE_ITEMS 条 (共 $totalMatched 条匹配, 总计 ${allRoutes.size} 条)"
        } else {
            "  共 $totalMatched 条路由 (总计 ${allRoutes.size} 条)"
        }
    }

    private fun navigateToSelected() {
        val selected = routeList.selectedValue ?: return
        popup?.cancel()

        val descriptor = OpenFileDescriptor(project, selected.file, selected.lineNumber, 0)
        descriptor.navigate(true)
    }

    fun show() {
        val panel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            add(searchField, BorderLayout.NORTH)

            val scrollPane = JScrollPane(routeList).apply {
                preferredSize = Dimension(700, 400)
            }
            add(scrollPane, BorderLayout.CENTER)

            statusLabel.font = statusLabel.font.deriveFont(11f)
            statusLabel.foreground = java.awt.Color.GRAY
            add(statusLabel, BorderLayout.SOUTH)
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setTitle("Restful-all: 搜索路由")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setFocusable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(true)
            .setCancelCallback {
                debounceTimer.stop()
                true
            }
            .createPopup()

        popup?.showCenteredInCurrentWindow(project)
    }

    companion object {
        private const val MAX_VISIBLE_ITEMS = 200
        private const val DEBOUNCE_MS = 150
    }

    private class RouteCellRenderer : ColoredListCellRenderer<RouteInfo>() {
        override fun customizeCellRenderer(
            list: JList<out RouteInfo>,
            value: RouteInfo,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val methodAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                if (selected) null else value.method.color,
            )
            append(value.method.displayName.padEnd(8), methodAttr)

            append(value.displayPath, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            append("  ")

            val locationAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_ITALIC,
                if (selected) null else java.awt.Color.GRAY,
            )
            append("${value.className}#${value.functionName}", locationAttr)

            append("  ")

            val fileAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_SMALLER,
                if (selected) null else java.awt.Color(0x88, 0x88, 0x88),
            )
            append("${value.file.name}:${value.lineNumber + 1}", fileAttr)

            val frameworkAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_SMALLER,
                if (selected) null else getFrameworkColor(value),
            )
            append("  [${value.framework.displayName}]", frameworkAttr)
        }

        private fun getFrameworkColor(route: RouteInfo): java.awt.Color {
            return when (route.method) {
                HttpMethod.GET -> java.awt.Color(0x61, 0xAF, 0xEF)
                HttpMethod.POST -> java.awt.Color(0x98, 0xC3, 0x79)
                HttpMethod.PUT -> java.awt.Color(0xE5, 0xC0, 0x7B)
                HttpMethod.DELETE -> java.awt.Color(0xE0, 0x6C, 0x75)
                else -> java.awt.Color(0xAB, 0xB2, 0xBF)
            }
        }
    }
}
