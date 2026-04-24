package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.humanize.FunMessageProvider
import io.github.movebrickschi.restfulall.license.LicenseManager
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.model.UserRouteMeta
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import io.github.movebrickschi.restfulall.service.RouteService
import io.github.movebrickschi.restfulall.sort.RouteSortStrategy
import io.github.movebrickschi.restfulall.stats.RouteStatsService
import io.github.movebrickschi.restfulall.theme.ThemeService
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RouteSearchPopup(
    private val project: Project,
    private var allRoutes: List<RouteInfo>,
    private val initialQuery: String? = null,
) {
    private val listModel = DefaultListModel<RouteInfo>()
    private val routeList = JBList(listModel)
    private val searchField = JBTextField()
    private val statusLabel = JLabel()
    private val emptyPlaceholder = JLabel("", SwingConstants.CENTER).apply {
        isVisible = false
        foreground = java.awt.Color(0x88, 0x88, 0x88)
        font = font.deriveFont(13f)
    }
    private val refreshLabel = JLabel(MyMessageBundle.message("route.search.refresh")).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = java.awt.Color(0x58, 0x9D, 0xF6)
        font = font.deriveFont(11f)
        toolTipText = MyMessageBundle.message("route.search.refresh.tooltip")
    }
    private val favoriteButton = createToolbarButton(AllIcons.Nodes.Favorite, "popup.toolbar.favorite.tip") { toggleFavoriteOnSelected() }
    private val pinButton = createToolbarButton(AllIcons.General.Pin_tab, "popup.toolbar.pin.tip") { togglePinOnSelected() }
    private val noteButton = createToolbarButton(AllIcons.Actions.Edit, "popup.toolbar.note.tip") { editNoteOnSelected() }
    private val filterFavButton = createToolbarButton(AllIcons.General.Filter, "popup.toolbar.filterFav.tip") { toggleFilterFav() }
    private val toolbarHintLabel = JLabel(MyMessageBundle.message("popup.toolbar.hint")).apply {
        foreground = java.awt.Color(0x88, 0x88, 0x88)
        font = font.deriveFont(10.5f)
        border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
    }
    private val trialBannerLabel = JLabel().apply {
        foreground = java.awt.Color(0xE5, 0x8E, 0x26)
        font = font.deriveFont(11f)
        isVisible = false
    }
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
        searchField.emptyText.text = MyMessageBundle.message("route.search.placeholder")

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
                    KeyEvent.VK_F5 -> {
                        doRefresh()
                        e.consume()
                    }
                    KeyEvent.VK_F -> {
                        if (e.isAltDown || e.isControlDown) {
                            toggleFavoriteOnSelected()
                            e.consume()
                        }
                    }
                    KeyEvent.VK_N -> {
                        if (e.isAltDown || e.isControlDown) {
                            editNoteOnSelected()
                            e.consume()
                        }
                    }
                }
            }
        })

        refreshLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = doRefresh()
        })

        routeList.cellRenderer = RouteCellRenderer()
        routeList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        routeList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }

            override fun mousePressed(e: MouseEvent) {
                maybeShowContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                maybeShowContextMenu(e)
            }
        })
        routeList.addListSelectionListener { syncToolbarEnabled() }
        syncToolbarEnabled()
    }

    private fun createToolbarButton(icon: javax.swing.Icon, tipKey: String, onClick: () -> Unit): JButton {
        return JButton(icon).apply {
            toolTipText = MyMessageBundle.message(tipKey)
            isFocusable = false
            margin = java.awt.Insets(2, 2, 2, 2)
            preferredSize = Dimension(24, 22)
            addActionListener { onClick() }
        }
    }

    private fun syncToolbarEnabled() {
        val hasSelection = routeList.selectedValue != null
        favoriteButton.isEnabled = hasSelection
        pinButton.isEnabled = hasSelection
        noteButton.isEnabled = hasSelection
    }

    private fun toggleFilterFav() {
        val current = searchField.text.orEmpty()
        if (current.contains("@fav")) {
            searchField.text = current.replace("@fav", "").trim()
        } else {
            searchField.text = if (current.isBlank()) "@fav" else "$current @fav"
        }
        searchField.requestFocusInWindow()
    }

    private fun buildActionToolbar(): JComponent {
        val left = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(favoriteButton)
            add(Box.createHorizontalStrut(2))
            add(pinButton)
            add(Box.createHorizontalStrut(2))
            add(noteButton)
            add(Box.createHorizontalStrut(8))
            add(filterFavButton)
            add(toolbarHintLabel)
        }
        val right = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(trialBannerLabel)
        }
        val bar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
        return bar
    }

    private fun maybeShowContextMenu(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val index = routeList.locationToIndex(e.point)
        if (index < 0) return
        routeList.selectedIndex = index
        showContextMenu(e.x, e.y)
    }

    private fun showContextMenu(x: Int, y: Int) {
        val route = routeList.selectedValue ?: return
        val settings = PluginSettingsState.getInstance(project)
        val meta = settings.getMeta(UserRouteMeta.keyOf(route))
        val menu = JPopupMenu()

        val navItem = JMenuItem(MyMessageBundle.message("popup.menu.navigate"), AllIcons.Actions.EditSource)
        navItem.addActionListener { navigateToSelected() }
        menu.add(navItem)

        menu.addSeparator()

        val favLabelKey = if (meta?.favorite == true) "popup.menu.unfavorite" else "popup.menu.favorite"
        val favItem = JMenuItem(MyMessageBundle.message(favLabelKey), AllIcons.Nodes.Favorite)
        favItem.addActionListener { toggleFavoriteOnSelected() }
        menu.add(favItem)

        val pinLabelKey = if (meta?.pinned == true) "popup.menu.unpin" else "popup.menu.pin"
        val pinItem = JMenuItem(MyMessageBundle.message(pinLabelKey), AllIcons.General.Pin_tab)
        pinItem.addActionListener { togglePinOnSelected() }
        menu.add(pinItem)

        val noteItem = JMenuItem(MyMessageBundle.message("popup.menu.note"), AllIcons.Actions.Edit)
        noteItem.addActionListener { editNoteOnSelected() }
        menu.add(noteItem)

        menu.show(routeList, x, y)
    }

    private fun toggleFavoriteOnSelected() {
        val route = routeList.selectedValue ?: return
        if (!LicenseManager.requirePro(project, "favorite")) return
        val key = UserRouteMeta.keyOf(route)
        val now = PluginSettingsState.getInstance(project).toggleFavorite(key)
        statusLabel.text = if (now) {
            MyMessageBundle.message("popup.toast.favorited", route.displayPath)
        } else {
            MyMessageBundle.message("popup.toast.unfavorited", route.displayPath)
        }
        routeList.repaint()
    }

    private fun togglePinOnSelected() {
        val route = routeList.selectedValue ?: return
        if (!LicenseManager.requirePro(project, "pin")) return
        val key = UserRouteMeta.keyOf(route)
        val now = PluginSettingsState.getInstance(project).togglePin(key)
        statusLabel.text = if (now) {
            MyMessageBundle.message("popup.toast.pinned", route.displayPath)
        } else {
            MyMessageBundle.message("popup.toast.unpinned", route.displayPath)
        }
        updateList(searchField.text)
    }

    private fun editNoteOnSelected() {
        val route = routeList.selectedValue ?: return
        if (!LicenseManager.requirePro(project, "note")) return
        val settings = PluginSettingsState.getInstance(project)
        val key = UserRouteMeta.keyOf(route)
        val current = settings.getMeta(key)?.note.orEmpty()
        val input = Messages.showMultilineInputDialog(
            project,
            MyMessageBundle.message("popup.note.prompt", route.displayPath),
            MyMessageBundle.message("popup.note.title"),
            current,
            Messages.getQuestionIcon(),
            null,
        ) ?: return
        val meta = settings.getOrCreateMeta(key)
        meta.note = input.trim()
        settings.saveMeta(meta)
        statusLabel.text = MyMessageBundle.message("popup.toast.note_saved", route.displayPath)
        routeList.repaint()
    }

    private fun doRefresh() {
        val routeService = RouteService.getInstance(project)
        if (routeService.isScanning) return

        refreshLabel.text = MyMessageBundle.message("route.search.refresh.scanning")
        refreshLabel.isEnabled = false

        // 扫描时彩蛋文案
        val loadingScene = if (allRoutes.size > 500) "loading.big_project" else "loading.scanning"
        val loadingCopy = FunMessageProvider.pick(loadingScene)
        if (loadingCopy.isNotBlank()) {
            statusLabel.text = loadingCopy
            statusLabel.foreground = java.awt.Color(0x58, 0x9D, 0xF6)
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, MyMessageBundle.message("route.list.task.rescanning"), true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                routeService.scanProject()
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    allRoutes = routeService.getCachedRoutes()
                    updateList(searchField.text)
                    refreshLabel.text = MyMessageBundle.message("route.search.refresh")
                    refreshLabel.isEnabled = true
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    refreshLabel.text = MyMessageBundle.message("route.search.refresh")
                    refreshLabel.isEnabled = true
                }
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
        val settings = PluginSettingsState.getInstance(project)

        // 语法过滤：@fav 只看收藏，@note 只看有备注
        val filterFavorite = lowerQuery.contains("@fav")
        val filterNote = lowerQuery.contains("@note")
        val cleanedQuery = lowerQuery
            .replace("@fav", "")
            .replace("@note", "")
            .trim()

        fun matchesMeta(route: RouteInfo): Boolean {
            if (!filterFavorite && !filterNote) return true
            val meta = settings.getMeta(UserRouteMeta.keyOf(route)) ?: return false
            if (filterFavorite && !(meta.favorite || meta.pinned)) return false
            if (filterNote && meta.note.isBlank()) return false
            return true
        }

        if (cleanedQuery.isBlank() && !filterFavorite && !filterNote) {
            // 空搜索：今日常用置顶（最多 3 条，视觉用分隔符）
            val today = RouteSortStrategy.pickTodayFavorites(allRoutes, settings, TODAY_LIMIT)
            val sorted = RouteSortStrategy.smartSort(allRoutes, settings)
            val shown = LinkedHashSet<RouteInfo>()
            today.forEach { shown.add(it) }
            for (route in sorted) {
                if (shown.size >= MAX_VISIBLE_ITEMS) break
                shown.add(route)
            }
            totalMatched = allRoutes.size
            shown.forEach { listModel.addElement(it) }
        } else {
            val sorted = RouteSortStrategy.smartSort(allRoutes, settings)
            for (route in sorted) {
                val textHit = cleanedQuery.isBlank() || route.searchKey.contains(cleanedQuery)
                if (textHit && matchesMeta(route)) {
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

        // 空列表的彩蛋文案
        emptyPlaceholder.text = when {
            allRoutes.isEmpty() -> "<html><div style='text-align:center;'>" +
                htmlEscape(FunMessageProvider.pick("empty.no_routes")) + "</div></html>"
            totalMatched == 0 -> "<html><div style='text-align:center;'>" +
                htmlEscape(FunMessageProvider.pick("empty.no_match")) + "</div></html>"
            else -> ""
        }
        emptyPlaceholder.isVisible = listModel.size() == 0

        statusLabel.text = if (totalMatched > MAX_VISIBLE_ITEMS) {
            MyMessageBundle.message("route.search.status.matched", MAX_VISIBLE_ITEMS, totalMatched, allRoutes.size)
        } else {
            MyMessageBundle.message("route.search.status.total", totalMatched, allRoutes.size)
        }
    }

    private fun htmlEscape(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun maybeShowDailyGreeting() {
        val settings = PluginSettingsState.getInstance(project)
        val today = java.time.LocalDate.now().toString()
        if (settings.getLastWelcomeShownDate() == today) return
        val greeting = FunMessageProvider.pickGreeting()
        if (greeting.isNotBlank()) {
            statusLabel.text = greeting
            statusLabel.foreground = java.awt.Color(0x58, 0x9D, 0xF6)
        }
        settings.setLastWelcomeShownDate(today)
    }

    private fun navigateToSelected() {
        val selected = routeList.selectedValue ?: return
        popup?.cancel()

        // 记录访问：统计、里程碑、衰减分更新
        try {
            RouteStatsService.getInstance(project).recordOpen(selected)
        } catch (_: Throwable) {
            // 统计失败不影响跳转
        }

        // 跳转后在状态栏闪一句鼓励文案
        try {
            val cheer = FunMessageProvider.pick("nav.cheer")
            if (cheer.isNotBlank()) {
                WindowManager.getInstance().getStatusBar(project)?.info = cheer
            }
        } catch (_: Throwable) {
            // 状态栏未就绪不影响跳转
        }

        val descriptor = OpenFileDescriptor(project, selected.file, selected.lineNumber, 0)
        descriptor.navigate(true)
    }

    private fun updateTrialBanner() {
        try {
            if (LicenseManager.hasValidLicense()) {
                trialBannerLabel.isVisible = false
                return
            }
            val days = LicenseManager.remainingTrialDays(project)
            if (days > 0) {
                trialBannerLabel.text = MyMessageBundle.message("popup.trial.banner", days)
                trialBannerLabel.foreground = java.awt.Color(0xE5, 0x8E, 0x26)
                trialBannerLabel.isVisible = true
            } else {
                trialBannerLabel.text = MyMessageBundle.message("popup.trial.expired")
                trialBannerLabel.foreground = java.awt.Color(0xC9, 0x5A, 0x5A)
                trialBannerLabel.isVisible = true
            }
        } catch (_: Throwable) {
            trialBannerLabel.isVisible = false
        }
    }

    fun show() {
        // 打开前同步主题到应用级缓存
        try {
            ThemeService.getInstance().syncFrom(project)
        } catch (_: Throwable) {
        }

        val panel = JPanel(BorderLayout(0, 4)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            val topStack = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(searchField)
                add(Box.createVerticalStrut(4))
                add(buildActionToolbar())
            }
            add(topStack, BorderLayout.NORTH)

            val listContainer = JLayeredPane().apply {
                layout = null
                preferredSize = Dimension(700, 400)
            }
            val scrollPane = JScrollPane(routeList)
            scrollPane.setBounds(0, 0, 700, 400)
            emptyPlaceholder.setBounds(0, 150, 700, 60)
            listContainer.add(scrollPane, JLayeredPane.DEFAULT_LAYER)
            listContainer.add(emptyPlaceholder, JLayeredPane.PALETTE_LAYER)
            listContainer.addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    scrollPane.setBounds(0, 0, listContainer.width, listContainer.height)
                    emptyPlaceholder.setBounds(0, listContainer.height / 2 - 30, listContainer.width, 60)
                }
            })
            add(listContainer, BorderLayout.CENTER)

            val bottomBar = JPanel(BorderLayout()).apply {
                statusLabel.font = statusLabel.font.deriveFont(11f)
                statusLabel.foreground = java.awt.Color.GRAY
                add(statusLabel, BorderLayout.WEST)
                add(refreshLabel, BorderLayout.EAST)
            }
            add(bottomBar, BorderLayout.SOUTH)
        }

        updateTrialBanner()

        // 第一次打开时展示问候语（每天只显示一次）
        maybeShowDailyGreeting()

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, searchField)
            .setTitle(MyMessageBundle.message("route.search.popup.title"))
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
        private const val TODAY_LIMIT = 3
    }

    private inner class RouteCellRenderer : ColoredListCellRenderer<RouteInfo>() {
        override fun customizeCellRenderer(
            list: JList<out RouteInfo>,
            value: RouteInfo,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val theme = ThemeService.getInstance().current()
            val settings = PluginSettingsState.getInstance(project)
            val meta = settings.getMeta(UserRouteMeta.keyOf(value))

            // 图标：Pinned > Favorite > None
            icon = when {
                meta?.pinned == true -> AllIcons.General.Pin_tab
                meta?.favorite == true -> AllIcons.Nodes.Favorite
                else -> null
            }

            val methodColor = theme.colorFor(value.method)
            val methodAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                if (selected) null else methodColor,
            )
            append(value.method.displayName.padEnd(8), methodAttr)

            append(value.displayPath, SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // 备注标识
            if (!meta?.note.isNullOrBlank()) {
                append("  ")
                val noteAttr = SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_PLAIN,
                    if (selected) null else theme.accentColor,
                )
                append("[${truncate(meta!!.note, 24)}]", noteAttr)
            }

            // 标签
            if (meta?.tags?.isNotEmpty() == true) {
                append("  ")
                val tagAttr = SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_SMALLER,
                    if (selected) null else theme.mutedColor,
                )
                append(meta.tags.joinToString(" ") { "#$it" }, tagAttr)
            }

            append("  ")

            val locationAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_ITALIC,
                if (selected) null else theme.mutedColor,
            )
            append("${value.className}#${value.functionName}", locationAttr)

            append("  ")

            val fileAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_SMALLER,
                if (selected) null else theme.fileColor,
            )
            append("${value.file.name}:${value.lineNumber + 1}", fileAttr)

            val frameworkAttr = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_SMALLER,
                if (selected) null else methodColor,
            )
            append("  [${value.framework.displayName}]", frameworkAttr)

            // 访问次数徽章
            val count = meta?.accessCount ?: 0
            if (count > 0) {
                append("  ")
                val countAttr = SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_SMALLER,
                    if (selected) null else theme.mutedColor,
                )
                append("×$count", countAttr)
            }

            // tooltip 显示完整备注（若存在）
            toolTipText = if (!meta?.note.isNullOrBlank()) meta!!.note else null
        }

        private fun truncate(text: String, max: Int): String =
            if (text.length <= max) text else text.take(max - 1) + "…"
    }
}
