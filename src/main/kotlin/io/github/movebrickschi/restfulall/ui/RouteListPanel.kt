package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.export.ApiDocumentExporter
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.ExpressParamExtractor
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.NestJsParamExtractor
import io.github.movebrickschi.restfulall.service.PythonParamExtractor
import io.github.movebrickschi.restfulall.service.RouteService
import io.github.movebrickschi.restfulall.service.SpringPsiParamExtractor
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.plaf.basic.BasicTreeUI

class RouteListPanel(
    private val project: Project,
    private val loadInitialRoutes: Boolean = true,
    private val onDebugRoute: (RouteInfo) -> Unit,
) : JPanel(BorderLayout()) {

    private val allRoutes = mutableListOf<RouteInfo>()
    private val filteredRoutes = mutableListOf<RouteInfo>()
    private val treeModel = DefaultTreeModel(
        DefaultMutableTreeNode(RouteTreeItem.Group("", 0, RouteTreeLevel.ROOT))
    )
    private val routeTree = JTree(treeModel)
    private val statusLabel = JBLabel()
    private val searchField = JBTextField()

    private val debounceTimer = Timer(150) { filterRoutes() }.apply { isRepeats = false }
    private var routeTextColumnWidth = MIN_ROUTE_TEXT_COLUMN_WIDTH
    private var nameColumnWidth = DEFAULT_NAME_COLUMN_WIDTH
    private var pathColumnWidth = DEFAULT_PATH_COLUMN_WIDTH
    private var userResizedColumns = false
    private var hoverRow = -1
    private lateinit var columnHeaderPanel: RouteColumnHeaderPanel

    init {
        setupToolbar()
        setupTree()
        applyI18n()
        if (loadInitialRoutes) {
            loadRoutes()
        } else {
            statusLabel.text = MyMessageBundle.message("route.list.empty")
        }

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    private fun setupToolbar() {
        val toolbar = JPanel(BorderLayout(2, 0)).apply {
            border = JBUI.Borders.empty(2, 4)

            add(searchField, BorderLayout.CENTER)
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
                        moveTreeSelection(1)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        moveTreeSelection(-1)
                        e.consume()
                    }
                }
            }
        })
    }

    private fun setupTree() {
        routeTree.apply {
            isRootVisible = true
            showsRootHandles = true
            rowHeight = JBUI.scale(24)
            font = UIManager.getFont("Tree.font") ?: font
            putClientProperty("JTree.lineStyle", "None")
            border = JBUI.Borders.emptyLeft(JBUI.scale(2))
            setUI(CompactRouteTreeUi())
            cellRenderer = RouteTreeCellRenderer()
            selectionModel.selectionMode = javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION
        }

        routeTree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                selectPathAt(e)
                showRoutePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                selectPathAt(e)
                showRoutePopup(e)
            }

            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e)) return
                val route = routeAt(e.x, e.y) ?: return
                if (isNavigateIconClick(e)) {
                    navigateToSource(route)
                    return
                }
                if (e.clickCount == 2) {
                    onDebugRoute(route)
                }
            }

            override fun mouseExited(e: MouseEvent) {
                updateHoverRow(-1)
            }
        })
        routeTree.addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val row = routeTree.getClosestRowForLocation(e.x, e.y)
                val bounds = if (row >= 0) routeTree.getRowBounds(row) else null
                updateHoverRow(if (bounds != null && e.y in bounds.y until bounds.y + bounds.height) row else -1)
            }
        })

        routeTree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val route = selectedRoute() ?: return
                when {
                    e.keyCode == KeyEvent.VK_ENTER && isMenuShortcutDown(e) -> {
                        navigateToSource(route)
                        e.consume()
                    }
                    e.keyCode == KeyEvent.VK_ENTER -> {
                        onDebugRoute(route)
                        e.consume()
                    }
                }
            }
        })

        val scrollPane = JBScrollPane(routeTree)
        columnHeaderPanel = RouteColumnHeaderPanel()
        val treeContainer = JPanel(BorderLayout()).apply {
            add(columnHeaderPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
        add(treeContainer, BorderLayout.CENTER)

        statusLabel.apply {
            font = font.deriveFont(11f)
            foreground = Color.GRAY
            border = JBUI.Borders.empty(2, 8)
        }
        add(statusLabel, BorderLayout.SOUTH)
    }

    private fun applyI18n() {
        searchField.emptyText.text = MyMessageBundle.message("route.list.search.placeholder")
        searchField.toolTipText = MyMessageBundle.message("route.list.search.placeholder")
        searchField.revalidate()
        searchField.repaint()
        if (::columnHeaderPanel.isInitialized) {
            columnHeaderPanel.applyI18n()
        }
        routeTree.cellRenderer = RouteTreeCellRenderer()
        rebuildTree()
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

    fun refreshRoutes() {
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

    fun exportApiDocument() {
        val routes = allRoutes.toList()
        if (routes.isEmpty()) {
            Messages.showInfoMessage(
                project,
                MyMessageBundle.message("route.list.export.empty"),
                MyMessageBundle.message("route.list.export.title"),
            )
            return
        }

        val dialog = ApiDocumentExportDialog(project, routes)
        if (!dialog.showAndGet()) return

        val selectedRoutes = dialog.selectedRoutes
        if (selectedRoutes.isEmpty()) {
            Messages.showInfoMessage(
                project,
                MyMessageBundle.message("route.list.export.no.selection"),
                MyMessageBundle.message("route.list.export.title"),
            )
            return
        }
        val format = dialog.format
        val options = dialog.options

        val chooser = JFileChooser().apply {
            dialogTitle = MyMessageBundle.message("route.list.export.title")
            selectedFile = File("restful-all-api.${format.extension}")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return

        val target = ensureExtension(chooser.selectedFile, format.extension)

        // Extract params in background with progress dialog, then write on EDT
        val paramsMap = mutableMapOf<RouteInfo, ExtractedMethodParams?>()
        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            MyMessageBundle.message("route.list.export.extracting"),
            false,
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                selectedRoutes.forEachIndexed { index, route ->
                    indicator.fraction = index.toDouble() / selectedRoutes.size
                    indicator.text2 = route.displayPath
                    paramsMap[route] = ReadAction.compute<ExtractedMethodParams?, Throwable> {
                        extractRouteParams(route)
                    }
                }
            }

            override fun onSuccess() {
                try {
                    val endpoints = ApiDocumentExporter.fromRoutes(selectedRoutes, paramsMap)
                    val content = ApiDocumentExporter.export(endpoints, format, options)
                    target.writeText(content, Charsets.UTF_8)
                    Messages.showInfoMessage(
                        project,
                        MyMessageBundle.message("route.list.export.success", target.absolutePath),
                        MyMessageBundle.message("route.list.export.title"),
                    )
                } catch (e: Exception) {
                    Messages.showErrorDialog(
                        project,
                        MyMessageBundle.message("route.list.export.failure", e.message ?: e.javaClass.simpleName),
                        MyMessageBundle.message("route.list.export.title"),
                    )
                }
            }

            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(
                    project,
                    MyMessageBundle.message("route.list.export.failure", error.message ?: error.javaClass.simpleName),
                    MyMessageBundle.message("route.list.export.title"),
                )
            }
        })
    }

    private fun extractRouteParams(routeInfo: RouteInfo): ExtractedMethodParams? =
        when (routeInfo.framework) {
            Framework.SPRING -> SpringPsiParamExtractor.extract(project, routeInfo)
            Framework.NESTJS -> NestJsParamExtractor.extract(routeInfo)
            Framework.EXPRESS -> ExpressParamExtractor.extract(routeInfo)
            Framework.PYTHON -> PythonParamExtractor.extract(routeInfo)
        }

    private fun updateRoutes(newRoutes: List<RouteInfo>) {
        allRoutes.clear()
        allRoutes.addAll(newRoutes)
        filterRoutes()
    }

    private fun ensureExtension(file: File, extension: String): File {
        val normalized = extension.trimStart('.')
        return if (file.name.endsWith(".$normalized", ignoreCase = true)) {
            file
        } else {
            File(file.parentFile, "${file.name}.$normalized")
        }
    }

    private fun filterRoutes() {
        val query = searchField.text.trim().lowercase()
        filteredRoutes.clear()

        if (query.isBlank()) {
            filteredRoutes.addAll(allRoutes)
        } else {
            filteredRoutes.addAll(allRoutes.filter { it.searchKey.contains(query) })
        }

        rebuildTree()
        updateStatusLabel()
    }

    private fun navigateToSource(route: RouteInfo) {
        OpenFileDescriptor(project, route.file, route.lineNumber, 0).navigate(true)
    }

    fun expandSelectedDirectory() {
        val path = selectedDirectoryPath() ?: return
        expandPathRecursively(path)
        routeTree.scrollPathToVisible(path)
    }

    fun collapseSelectedDirectory() {
        val path = selectedDirectoryPath() ?: return
        collapsePathRecursively(path)
    }

    private fun rebuildTree() {
        hoverRow = -1
        updateRouteTextColumnWidth()

        val root = RouteTreeBuilder.buildSwingTree(filteredRoutes, project.name, project.basePath)

        treeModel.setRoot(root)
        treeModel.reload()
        expandAllRows()
        selectFirstRoute()
    }

    private fun updateRouteTextColumnWidth() {
        val metrics = routeTree.getFontMetrics(routeTree.font)
        if (!userResizedColumns) {
            val maxNameWidth = filteredRoutes.maxOfOrNull { route ->
                metrics.stringWidth(route.routeName.ifBlank { route.functionName })
            } ?: 0
            val maxPathWidth = filteredRoutes.maxOfOrNull { route ->
                metrics.stringWidth(route.displayPath)
            } ?: 0
            nameColumnWidth = maxNameWidth.coerceIn(DEFAULT_NAME_COLUMN_WIDTH, MAX_NAME_COLUMN_WIDTH)
            pathColumnWidth = maxPathWidth.coerceIn(DEFAULT_PATH_COLUMN_WIDTH, MAX_PATH_COLUMN_WIDTH)
        }
        routeTextColumnWidth = (METHOD_TEXT_WIDTH + ROUTE_TEXT_GAP + nameColumnWidth + ROUTE_TEXT_GAP + pathColumnWidth)
            .coerceAtLeast(MIN_ROUTE_TEXT_COLUMN_WIDTH)
        if (::columnHeaderPanel.isInitialized) {
            columnHeaderPanel.revalidate()
            columnHeaderPanel.repaint()
        }
    }

    private fun expandAllRows() {
        var row = 0
        while (row < routeTree.rowCount) {
            routeTree.expandRow(row)
            row++
        }
    }

    private fun selectFirstRoute() {
        val root = treeModel.root as? DefaultMutableTreeNode ?: return
        findFirstRoutePath(root)?.let {
            routeTree.selectionPath = it
            routeTree.scrollPathToVisible(it)
        }
    }

    private fun findFirstRoutePath(node: DefaultMutableTreeNode): TreePath? {
        if (node.userObject is RouteTreeItem.Leaf) {
            return TreePath(node.path)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            findFirstRoutePath(child)?.let { return it }
        }

        return null
    }

    private fun expandPathRecursively(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            expandPathRecursively(TreePath(child.path))
        }
        routeTree.expandPath(path)
    }

    private fun collapsePathRecursively(path: TreePath) {
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            collapsePathRecursively(TreePath(child.path))
        }
        routeTree.collapsePath(path)
    }

    private fun moveTreeSelection(delta: Int) {
        val rowCount = routeTree.rowCount
        if (rowCount == 0) return

        val current = routeTree.selectionRows?.firstOrNull() ?: 0
        val next = (current + delta).coerceIn(0, rowCount - 1)
        routeTree.setSelectionRow(next)
        routeTree.scrollRowToVisible(next)
    }

    private fun selectPathAt(e: MouseEvent) {
        routeTree.getPathForLocation(e.x, e.y)?.let {
            routeTree.selectionPath = it
        }
    }

    private fun routeAt(x: Int, y: Int): RouteInfo? {
        val row = routeTree.getClosestRowForLocation(x, y)
        if (row < 0) return null
        val bounds = routeTree.getRowBounds(row) ?: return null
        if (y !in bounds.y until (bounds.y + bounds.height)) return null
        return routeFromPath(routeTree.getPathForRow(row))
    }

    private fun selectedRoute(): RouteInfo? =
        routeFromPath(routeTree.selectionPath)

    private fun selectedDirectoryPath(): TreePath? {
        val selectedPath = routeTree.selectionPath ?: return null
        val selectedNode = selectedPath.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return when (selectedNode.userObject) {
            is RouteTreeItem.Group -> selectedPath
            is RouteTreeItem.Leaf -> selectedPath.parentPath
            else -> null
        }
    }

    private fun routeFromPath(path: TreePath?): RouteInfo? {
        val node = path?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return (node.userObject as? RouteTreeItem.Leaf)?.route
    }

    private fun showRoutePopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = routeTree.getPathForLocation(e.x, e.y) ?: return
        val route = routeFromPath(path) ?: return
        routeTree.selectionPath = path
        createRoutePopupMenu(route).show(routeTree, e.x, e.y)
        e.consume()
    }

    private fun createRoutePopupMenu(route: RouteInfo): JPopupMenu =
        JPopupMenu().apply {
            add(JMenuItem(MyMessageBundle.message("route.list.action.debug"), AllIcons.Actions.Execute).apply {
                addActionListener { onDebugRoute(route) }
            })
            add(JMenuItem(MyMessageBundle.message("route.list.action.navigate"), AllIcons.Actions.Find).apply {
                addActionListener { navigateToSource(route) }
            })
        }

    private fun isNavigateIconClick(e: MouseEvent): Boolean {
        val route = routeAt(e.x, e.y) ?: return false
        val row = routeTree.getClosestRowForLocation(e.x, e.y)
        if (row < 0) return false
        val bounds = routeTree.getRowBounds(row) ?: return false
        val iconStartX = bounds.x +
            leafLeadingPad(bounds.x) +
            METHOD_TEXT_WIDTH +
            ROUTE_TEXT_GAP +
            nameColumnWidth +
            ROUTE_TEXT_GAP +
            routePathTextWidth(route.displayPath)
        return e.x in iconStartX until (iconStartX + NAV_ICON_HIT_WIDTH)
    }

    private fun isMenuShortcutDown(e: KeyEvent): Boolean = e.isControlDown || e.isMetaDown

    private fun updateHoverRow(row: Int) {
        if (hoverRow == row) return
        val old = hoverRow
        hoverRow = row
        if (old >= 0) repaintTreeRow(old)
        if (row >= 0) repaintTreeRow(row)
    }

    private fun repaintTreeRow(row: Int) {
        routeTree.getRowBounds(row)?.let { bounds ->
            routeTree.repaint(0, bounds.y, routeTree.width, bounds.height)
        }
    }

    private fun leafBaseX(depth: Int): Int =
        (depth * (JBUI.scale(TREE_LEFT_CHILD_INDENT) + JBUI.scale(TREE_RIGHT_CHILD_INDENT)))
            .coerceAtLeast(JBUI.scale(LEAF_LEFT_INDENT))

    private fun leafLeadingPad(@Suppress("UNUSED_PARAMETER") cellX: Int): Int = 0

    private fun routePathTextWidth(path: String): Int =
        (routeTree.getFontMetrics(routeTree.font).stringWidth(path) + JBUI.scale(4))
            .coerceAtMost(pathColumnWidth)

    private fun hoverBackground(): Color =
        UIManager.getColor("List.hoverBackground") ?: Color(0x3D, 0x3F, 0x41)

    private inner class CompactRouteTreeUi : BasicTreeUI() {
        override fun installDefaults() {
            super.installDefaults()
            leftChildIndent = JBUI.scale(TREE_LEFT_CHILD_INDENT)
            rightChildIndent = JBUI.scale(TREE_RIGHT_CHILD_INDENT)
        }

        override fun getRowX(row: Int, depth: Int): Int {
            val node = routeTree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
            if (node?.userObject is RouteTreeItem.Leaf) {
                return leafBaseX(depth)
            }
            return super.getRowX(row, depth)
        }
    }

    private inner class RouteTreeCellRenderer : DefaultTreeCellRenderer() {
        private val leafPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.empty(1, 2, 1, 4)
            isOpaque = false
        }
        private val methodBadge = MethodBadge()
        private val nameLabel = JLabel().apply {
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.CENTER
            alignmentY = Component.CENTER_ALIGNMENT
            isOpaque = false
        }
        private val pathLabel = JLabel().apply {
            horizontalAlignment = SwingConstants.LEFT
            verticalAlignment = SwingConstants.CENTER
            alignmentY = Component.CENTER_ALIGNMENT
            isOpaque = false
        }
        private val navigateLabel = JLabel(AllIcons.Actions.Find).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            alignmentY = Component.CENTER_ALIGNMENT
            preferredSize = Dimension(NAV_ICON_HIT_WIDTH, 24)
            toolTipText = MyMessageBundle.message("route.list.action.navigate")
            isOpaque = false
        }

        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ): Component {
            val node = value as? DefaultMutableTreeNode
                ?: return super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

            when (val item = node.userObject) {
                is RouteTreeItem.Group -> {
                    val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                    val hovered = row == hoverRow
                    text = if (item.level == RouteTreeLevel.ROOT) {
                        MyMessageBundle.message("route.list.tree.root", item.count)
                    } else {
                        MyMessageBundle.message("route.list.tree.group", item.title, item.count)
                    }
                    icon = AllIcons.Nodes.Folder
                    toolTipText = null
                    isOpaque = sel || hovered
                    backgroundNonSelectionColor = if (hovered) hoverBackground() else tree.background
                    backgroundSelectionColor = UIManager.getColor("Tree.selectionBackground") ?: Color(0x38, 0x73, 0xD1)
                    borderSelectionColor = tree.background
                    return component
                }
                is RouteTreeItem.Leaf -> {
                    val route = item.route
                    val methodColor = route.method.color
                    val routeName = route.routeName.ifBlank { route.functionName }
                    val hovered = row == hoverRow
                    val primaryTextColor = if (sel) textSelectionColor else textNonSelectionColor
                    val secondaryTextColor = if (sel) primaryTextColor else Color.GRAY

                    methodBadge.setMethod(route.methodLabel(), methodColor)

                    nameLabel.text = routeName
                    nameLabel.foreground = primaryTextColor
                    pathLabel.text = route.displayPath
                    pathLabel.foreground = secondaryTextColor
                    val tooltip = MyMessageBundle.message(
                        "route.list.tree.leaf.tooltip",
                        route.className,
                        route.functionName,
                        route.file.name,
                        route.lineNumber + 1,
                    )
                    methodBadge.toolTipText = tooltip
                    nameLabel.toolTipText = tooltip
                    pathLabel.toolTipText = tooltip
                    leafPanel.toolTipText = tooltip

                    val cellX = leafBaseX(node.level)
                    val leadingPad = leafLeadingPad(cellX)
                    leafPanel.removeAll()
                    if (leadingPad > 0) {
                        leafPanel.add(Box.createHorizontalStrut(leadingPad))
                    }
                    leafPanel.add(methodBadge)
                    leafPanel.add(Box.createHorizontalStrut(ROUTE_TEXT_GAP))
                    leafPanel.add(nameLabel)
                    leafPanel.add(Box.createHorizontalStrut(ROUTE_TEXT_GAP))
                    leafPanel.add(pathLabel)
                    leafPanel.add(navigateLabel)
                    leafPanel.add(Box.createHorizontalGlue())

                    methodBadge.applyFixedSize(METHOD_BADGE_WIDTH, METHOD_BADGE_HEIGHT)

                    val nameSize = Dimension(nameColumnWidth, ROW_CONTENT_HEIGHT)
                    nameLabel.preferredSize = nameSize
                    nameLabel.minimumSize = nameSize
                    nameLabel.maximumSize = nameSize

                    val pathSize = Dimension(routePathTextWidth(route.displayPath), ROW_CONTENT_HEIGHT)
                    pathLabel.preferredSize = pathSize
                    pathLabel.minimumSize = pathSize
                    pathLabel.maximumSize = pathSize

                    val iconSize = Dimension(NAV_ICON_HIT_WIDTH, ROW_CONTENT_HEIGHT)
                    navigateLabel.preferredSize = iconSize
                    navigateLabel.minimumSize = iconSize
                    navigateLabel.maximumSize = iconSize

                    val leafSize = Dimension(
                        maxOf(tree.width - cellX, leadingPad + routeTextColumnWidth + NAV_ICON_HIT_WIDTH),
                        tree.rowHeight.coerceAtLeast(24),
                    )
                    leafPanel.preferredSize = leafSize
                    leafPanel.minimumSize = leafSize
                    leafPanel.maximumSize = leafSize
                    leafPanel.isOpaque = sel || hovered
                    leafPanel.background = when {
                        sel -> UIManager.getColor("Tree.selectionBackground") ?: Color(0x38, 0x73, 0xD1)
                        hovered -> hoverBackground()
                        else -> tree.background
                    }
                    return leafPanel
                }
                else -> {
                    return super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
                }
            }
        }
    }

    private inner class RouteColumnHeaderPanel : JPanel(null) {
        private val nameHeader = JBLabel(MyMessageBundle.message("route.list.column.name")).apply {
            horizontalAlignment = SwingConstants.LEFT
            foreground = Color.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        private val pathHeader = JBLabel(MyMessageBundle.message("route.list.column.path")).apply {
            horizontalAlignment = SwingConstants.LEFT
            foreground = Color.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        private var resizing = false

        init {
            border = JBUI.Borders.empty(0, 2)
            preferredSize = Dimension(MIN_ROUTE_TEXT_COLUMN_WIDTH, HEADER_HEIGHT)
            isOpaque = true
            add(nameHeader)
            add(pathHeader)
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    resizing = isOnResizeHandle(e.x)
                }

                override fun mouseReleased(e: MouseEvent) {
                    resizing = false
                }
            })
            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    cursor = Cursor.getPredefinedCursor(
                        if (isOnResizeHandle(e.x)) Cursor.E_RESIZE_CURSOR else Cursor.DEFAULT_CURSOR,
                    )
                }

                override fun mouseDragged(e: MouseEvent) {
                    if (!resizing) return
                    val pathLeft = JBUI.scale(LEAF_LEFT_INDENT) + METHOD_TEXT_WIDTH + ROUTE_TEXT_GAP
                    val available = (width - pathLeft - NAV_ICON_HIT_WIDTH)
                        .coerceAtLeast(DEFAULT_NAME_COLUMN_WIDTH + DEFAULT_PATH_COLUMN_WIDTH)
                    val nextNameWidth = (e.x - pathLeft)
                        .coerceIn(MIN_NAME_COLUMN_WIDTH, available - MIN_PATH_COLUMN_WIDTH)
                    nameColumnWidth = nextNameWidth
                    pathColumnWidth = (available - nameColumnWidth).coerceAtLeast(MIN_PATH_COLUMN_WIDTH)
                    userResizedColumns = true
                    updateRouteTextColumnWidth()
                    routeTree.revalidate()
                    routeTree.repaint()
                    revalidate()
                    repaint()
                }
            })
        }

        override fun doLayout() {
            val nameStart = JBUI.scale(HEADER_LEFT_PADDING)
            nameHeader.setBounds(nameStart, 0, nameColumnWidth, height)
            val pathStart = JBUI.scale(LEAF_LEFT_INDENT) + METHOD_TEXT_WIDTH +
                ROUTE_TEXT_GAP + nameColumnWidth + ROUTE_TEXT_GAP
            pathHeader.setBounds(pathStart, 0, pathColumnWidth, height)
        }

        fun applyI18n() {
            nameHeader.text = MyMessageBundle.message("route.list.column.name")
            pathHeader.text = MyMessageBundle.message("route.list.column.path")
            invalidate()
            doLayout()
            revalidate()
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as Graphics2D
            try {
                g2.color = UIManager.getColor("Separator.foreground") ?: Color(0x55, 0x55, 0x55)
                val separatorX = JBUI.scale(LEAF_LEFT_INDENT) + METHOD_TEXT_WIDTH +
                    ROUTE_TEXT_GAP + nameColumnWidth + ROUTE_TEXT_GAP / 2
                g2.drawLine(separatorX, JBUI.scale(4), separatorX, height - JBUI.scale(4))
                g2.drawLine(0, height - 1, width, height - 1)
            } finally {
                g2.dispose()
            }
        }

        private fun isOnResizeHandle(x: Int): Boolean {
            val separatorX = JBUI.scale(LEAF_LEFT_INDENT) + METHOD_TEXT_WIDTH +
                ROUTE_TEXT_GAP + nameColumnWidth + ROUTE_TEXT_GAP / 2
            return kotlin.math.abs(x - separatorX) <= JBUI.scale(RESIZE_HANDLE_WIDTH)
        }
    }

    private fun RouteInfo.methodLabel(): String =
        if (method.displayName.equals("DELETE", ignoreCase = true)) "DEL" else method.displayName

    companion object {
        private const val METHOD_TEXT_WIDTH = 40
        private const val METHOD_BADGE_WIDTH = 40
        private const val METHOD_BADGE_HEIGHT = 20
        private const val MIN_NAME_COLUMN_WIDTH = 80
        private const val DEFAULT_NAME_COLUMN_WIDTH = 160
        private const val MAX_NAME_COLUMN_WIDTH = 240
        private const val MIN_PATH_COLUMN_WIDTH = 120
        private const val DEFAULT_PATH_COLUMN_WIDTH = 280
        private const val MAX_PATH_COLUMN_WIDTH = 420
        private const val MIN_ROUTE_TEXT_COLUMN_WIDTH = 240
        private const val NAV_ICON_HIT_WIDTH = 28
        private const val ROUTE_TEXT_GAP = 8
        private const val ROW_CONTENT_HEIGHT = 24
        private const val HEADER_HEIGHT = 24
        private const val HEADER_LEFT_PADDING = 6
        private const val LEAF_LEFT_INDENT = 56  // 4 × (TREE_LEFT_CHILD_INDENT + TREE_RIGHT_CHILD_INDENT)
        private const val HEADER_TREE_INDENT = 34
        private const val TREE_LEFT_CHILD_INDENT = 6
        private const val TREE_RIGHT_CHILD_INDENT = 8
        private const val RESIZE_HANDLE_WIDTH = 4

        private fun htmlEscape(value: String): String =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }
}
