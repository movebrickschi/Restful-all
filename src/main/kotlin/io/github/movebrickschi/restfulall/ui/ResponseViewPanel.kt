package io.github.movebrickschi.restfulall.ui

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Image
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.swing.DefaultComboBoxModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * v1.3.2 F6 - 响应高级查看面板。
 *
 * 6 种视图：Pretty / Raw / Tree / Table / Image / Download。
 *
 * - **Pretty**：复用 [JsonSyntaxTextPane]，按响应 content-type 自动 JSON 格式化
 * - **Raw**：纯文本，不做任何处理
 * - **Tree**：把 JSON 解析为 [JTree]，叶子节点显示 `key: value`；点击节点复制 JSONPath 到剪贴板
 * - **Table**：仅在响应是 JSON 数组 of objects 时启用；列名取首项 keys 的并集
 * - **Image**：响应 content-type 为 image / 子类型时渲染
 * - **Download**：所有响应都可点击保存到磁盘；二进制 / 文件类响应默认进入此视图
 *
 * 视图切换不重新发请求，本地缓存 body + bytes。
 * 默认视图按 content-type 推断：image→Image / json→Pretty / 其它→Pretty 但回退 Raw。
 */
class ResponseViewPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val viewModeCombo = JComboBox<String>()

    private val prettyArea = JsonSyntaxTextPane(editable = false)
    private val rawArea = JTextArea().apply { isEditable = false; lineWrap = false; tabSize = 2 }
    private val treeRootNode = DefaultMutableTreeNode("response")
    private val treeModel = DefaultTreeModel(treeRootNode)
    private val tree = JTree(treeModel).apply {
        isRootVisible = true
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    private val tableModel = DynamicTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(true); rowHeight = 24
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
    }
    private val imageLabel = JBLabel("(no image)").apply {
        horizontalAlignment = JBLabel.CENTER
    }
    private val downloadButton = JButton("Download response").apply {
        addActionListener { triggerDownload() }
    }
    private val downloadInfoLabel = JBLabel()

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout).apply {
        add(JBScrollPane(prettyArea), CARD_PRETTY)
        add(JBScrollPane(rawArea), CARD_RAW)
        add(JBScrollPane(tree), CARD_TREE)
        add(JBScrollPane(table), CARD_TABLE)
        add(JBScrollPane(imageLabel), CARD_IMAGE)
        add(buildDownloadCard(), CARD_DOWNLOAD)
    }

    private val mapper: ObjectMapper = ObjectMapper()

    @Volatile private var cachedBody: String = ""
    @Volatile private var cachedBytes: ByteArray = ByteArray(0)
    @Volatile private var cachedContentType: String = ""
    @Volatile private var lastJsonNode: JsonNode? = null

    init {
        viewModeCombo.model = DefaultComboBoxModel(arrayOf(
            VIEW_PRETTY, VIEW_RAW, VIEW_TREE, VIEW_TABLE, VIEW_IMAGE, VIEW_DOWNLOAD,
        ))
        viewModeCombo.preferredSize = Dimension(120, 24)
        viewModeCombo.addActionListener { switchView(viewModeCombo.selectedItem as String) }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(viewModeCombo)
        }
        add(toolbar, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                copyJsonPath(path)
            }
        })

        showEmpty()
    }

    private fun buildDownloadCard(): JPanel {
        val p = JPanel(BorderLayout())
        val center = JPanel()
        center.layout = javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS)
        downloadInfoLabel.alignmentX = LEFT_ALIGNMENT
        downloadButton.alignmentX = LEFT_ALIGNMENT
        center.border = JBUI.Borders.empty(20)
        center.add(downloadInfoLabel)
        center.add(downloadButton)
        p.add(center, BorderLayout.NORTH)
        return p
    }

    /**
     * 用新响应刷新视图缓存。
     *
     * @param body 文本形态的响应体（已 decompress / 已 charset 解码）；可空字符串
     * @param contentType `Content-Type` header 原值（区分 `image/png` / `application/json` / `application/octet-stream`）
     * @param bytes 原始字节，二进制 / 图片 / 下载视图需要；可 null（回退用 body.toByteArray(UTF-8)）
     */
    fun setResponse(body: String, contentType: String, bytes: ByteArray? = null) {
        cachedBody = body
        cachedContentType = contentType.lowercase()
        cachedBytes = bytes ?: body.toByteArray(Charsets.UTF_8)
        lastJsonNode = tryParseJson(body)

        val defaultView = decideDefaultView()
        viewModeCombo.selectedItem = defaultView
        switchView(defaultView)
    }

    fun clear() {
        cachedBody = ""
        cachedBytes = ByteArray(0)
        cachedContentType = ""
        lastJsonNode = null
        prettyArea.text = ""
        rawArea.text = ""
        treeRootNode.removeAllChildren()
        treeModel.nodeStructureChanged(treeRootNode)
        tableModel.setData(emptyList(), emptyList())
        imageLabel.icon = null
        imageLabel.text = "(no image)"
        downloadInfoLabel.text = ""
        viewModeCombo.selectedItem = VIEW_PRETTY
        cardLayout.show(cardPanel, CARD_PRETTY)
    }

    private fun showEmpty() {
        prettyArea.text = ""
        rawArea.text = ""
        cardLayout.show(cardPanel, CARD_PRETTY)
    }

    private fun decideDefaultView(): String = when {
        cachedContentType.startsWith("image/") -> VIEW_IMAGE
        cachedContentType.startsWith("application/octet-stream") -> VIEW_DOWNLOAD
        cachedContentType.contains("application/json") -> VIEW_PRETTY
        cachedContentType.contains("text/") || cachedContentType.contains("application/xml") -> VIEW_PRETTY
        else -> VIEW_PRETTY
    }

    private fun switchView(view: String) {
        when (view) {
            VIEW_PRETTY -> renderPretty()
            VIEW_RAW    -> renderRaw()
            VIEW_TREE   -> renderTree()
            VIEW_TABLE  -> renderTable()
            VIEW_IMAGE  -> renderImage()
            VIEW_DOWNLOAD -> renderDownload()
        }
        cardLayout.show(cardPanel, viewToCard(view))
    }

    private fun viewToCard(view: String): String = when (view) {
        VIEW_PRETTY -> CARD_PRETTY
        VIEW_RAW    -> CARD_RAW
        VIEW_TREE   -> CARD_TREE
        VIEW_TABLE  -> CARD_TABLE
        VIEW_IMAGE  -> CARD_IMAGE
        else        -> CARD_DOWNLOAD
    }

    private fun renderPretty() {
        val node = lastJsonNode
        val text = if (node != null) {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
            } catch (e: Exception) {
                cachedBody
            }
        } else cachedBody
        prettyArea.text = text
        prettyArea.caretPosition = 0
        if (node != null) prettyArea.applyHighlighting()
    }

    private fun renderRaw() {
        rawArea.text = cachedBody
        rawArea.caretPosition = 0
    }

    private fun renderTree() {
        treeRootNode.removeAllChildren()
        val node = lastJsonNode
        if (node == null) {
            treeRootNode.userObject = "(not a JSON response)"
            treeModel.nodeStructureChanged(treeRootNode)
            return
        }
        treeRootNode.userObject = "$ (root)"
        buildTreeNode(treeRootNode, node)
        treeModel.nodeStructureChanged(treeRootNode)
        for (i in 0 until tree.rowCount.coerceAtMost(20)) tree.expandRow(i)
    }

    private fun buildTreeNode(parent: DefaultMutableTreeNode, json: JsonNode) {
        when {
            json.isObject -> {
                val fields = json.fields()
                while (fields.hasNext()) {
                    val (key, value) = fields.next()
                    val child = DefaultMutableTreeNode(treeLabel(key, value))
                    parent.add(child)
                    if (value.isContainerNode) buildTreeNode(child, value)
                }
            }
            json.isArray -> {
                for (i in 0 until json.size()) {
                    val value = json[i]
                    val child = DefaultMutableTreeNode(treeLabel("[$i]", value))
                    parent.add(child)
                    if (value.isContainerNode) buildTreeNode(child, value)
                }
            }
            else -> { /* primitive leaf already labeled by parent */ }
        }
    }

    private fun treeLabel(key: String, value: JsonNode): String = when {
        value.isObject -> "$key : {…} (${value.size()})"
        value.isArray  -> "$key : […] (${value.size()})"
        value.isTextual -> "$key : \"${value.asText().take(120)}\""
        else            -> "$key : ${value.asText().take(120)}"
    }

    private fun copyJsonPath(path: TreePath) {
        val segments = mutableListOf<String>()
        for (i in 1 until path.pathCount) {
            val n = path.getPathComponent(i) as? DefaultMutableTreeNode ?: continue
            val label = n.userObject?.toString().orEmpty()
            val keyPart = label.substringBefore(" :").trim()
            segments.add(keyPart)
        }
        val expr = buildString {
            append("$")
            for (s in segments) {
                if (s.startsWith("[")) append(s) else append(".").append(s)
            }
        }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(StringSelection(expr), null)
    }

    private fun renderTable() {
        val node = lastJsonNode
        if (node == null || !node.isArray || node.size() == 0) {
            tableModel.setData(listOf("(not a JSON array)"), listOf(emptyList()))
            return
        }
        val first = node.firstOrNull { it.isObject }
        if (first == null) {
            // 数组里是纯标量 / 数组的混合 → 单列
            val rows = (0 until node.size()).map { listOf(node[it].asText()) }
            tableModel.setData(listOf("value"), rows)
            return
        }
        val columns = LinkedHashSet<String>()
        first.fieldNames().forEachRemaining { columns.add(it) }
        for (i in 0 until node.size()) {
            val row = node[i]
            if (row.isObject) row.fieldNames().forEachRemaining { columns.add(it) }
        }
        val cols = columns.toList()
        val rows = (0 until node.size()).map { idx ->
            val row = node[idx]
            cols.map { col ->
                if (row.isObject) row.path(col).asText() else "(non-object row)"
            }
        }
        tableModel.setData(cols, rows)
    }

    private fun renderImage() {
        try {
            val img: Image? = ImageIO.read(ByteArrayInputStream(cachedBytes))
            if (img == null) {
                imageLabel.icon = null
                imageLabel.text = "(failed to decode image — try Download view)"
                return
            }
            imageLabel.icon = ImageIcon(img)
            imageLabel.text = ""
        } catch (e: Exception) {
            imageLabel.icon = null
            imageLabel.text = "(image decode error: ${e.message})"
        }
    }

    private fun renderDownload() {
        val sizeKb = (cachedBytes.size / 1024.0).let { String.format("%.1f", it) }
        downloadInfoLabel.text =
            "Response size: ${cachedBytes.size} bytes (~$sizeKb KB), content-type: $cachedContentType"
    }

    private fun triggerDownload() {
        val descriptor = FileSaverDescriptor("Save response", "Pick destination for the response payload")
        val ext = guessExtension()
        val defaultName = "response.$ext"
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target: VirtualFileWrapper = saver.save(defaultName) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                target.file.writeBytes(cachedBytes)
                SwingUtilities.invokeLater {
                    Messages.showInfoMessage(this, "Saved to ${target.file.absolutePath}", "Download complete")
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    Messages.showErrorDialog(this, e.message ?: "I/O error", "Download failed")
                }
            }
        }
    }

    private fun guessExtension(): String = when {
        cachedContentType.contains("application/json") -> "json"
        cachedContentType.contains("application/xml") || cachedContentType.contains("text/xml") -> "xml"
        cachedContentType.startsWith("image/png") -> "png"
        cachedContentType.startsWith("image/jpeg") -> "jpg"
        cachedContentType.startsWith("image/gif") -> "gif"
        cachedContentType.startsWith("image/webp") -> "webp"
        cachedContentType.contains("text/html") -> "html"
        cachedContentType.contains("text/plain") -> "txt"
        else -> "bin"
    }

    private fun tryParseJson(text: String): JsonNode? = try {
        if (text.isBlank()) null else mapper.readTree(text)
    } catch (_: Exception) {
        null
    }

    /** 对外暴露的内部 JTextPane，供旧 stream / append 逻辑兼容写入 raw text。*/
    fun rawTextPane(): JsonSyntaxTextPane = prettyArea

    companion object {
        const val VIEW_PRETTY = "Pretty"
        const val VIEW_RAW = "Raw"
        const val VIEW_TREE = "Tree"
        const val VIEW_TABLE = "Table"
        const val VIEW_IMAGE = "Image"
        const val VIEW_DOWNLOAD = "Download"

        private const val CARD_PRETTY = "card_pretty"
        private const val CARD_RAW = "card_raw"
        private const val CARD_TREE = "card_tree"
        private const val CARD_TABLE = "card_table"
        private const val CARD_IMAGE = "card_image"
        private const val CARD_DOWNLOAD = "card_download"
    }

    /** 简化的动态列 TableModel，列与行可同时变化。*/
    private class DynamicTableModel : AbstractTableModel() {
        private var columns: List<String> = emptyList()
        private var rows: List<List<String>> = emptyList()

        fun setData(columns: List<String>, rows: List<List<String>>) {
            this.columns = columns
            this.rows = rows
            fireTableStructureChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns.getOrElse(column) { "" }
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            rows.getOrNull(rowIndex)?.getOrNull(columnIndex) ?: ""
    }
}
