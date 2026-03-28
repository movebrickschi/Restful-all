package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.model.ParamEntry
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import javax.swing.*

class ApiDebugPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val methodCombo = JComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"))
    private val urlField = JBTextField()
    private val sendButton = JButton("发送")

    private val queryParamPanel = ParamTablePanel()
    private val bodyTextArea = JBTextArea().apply {
        rows = 6
        lineWrap = true
        wrapStyleWord = true
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val pathParamPanel = ParamTablePanel()
    private val headersPanel = ParamTablePanel()
    private val cookiesPanel = ParamTablePanel()
    private val requestTabs = JBTabbedPane()

    private val bodyTypeGroup = ButtonGroup()
    private val bodyTypeNone = JRadioButton("none")
    private val bodyTypeFormData = JRadioButton("form-data")
    private val bodyTypeUrlEncoded = JRadioButton("x-www-form-urlencoded")
    private val bodyTypeJson = JRadioButton("json", true)
    private val bodyTypeXml = JRadioButton("xml")
    private val bodyTypeRaw = JRadioButton("raw")

    private val formDataPanel = FormDataParamPanel()
    private val urlEncodedPanel = ParamTablePanel()
    private val bodyCardLayout = CardLayout()
    private val bodyCardPanel = JPanel(bodyCardLayout)

    private val responseBodyArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font("Monospaced", Font.PLAIN, 12)
    }
    private val responseHeadersModel = ResponseTableModel()
    private val responseCookiesModel = ResponseTableModel()
    private val responseTabs = JBTabbedPane()
    private val responseStatusLabel = JBLabel()
    private val responseFormatCombo = JComboBox(arrayOf("JSON", "Text", "HTML", "XML"))

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private var currentModuleName: String? = null

    init {
        border = JBUI.Borders.empty(2, 4, 4, 4)
        setupUI()
    }

    private fun setupUI() {
        val urlBar = JPanel(BorderLayout(2, 0)).apply {
            border = JBUI.Borders.empty(2, 0)
            methodCombo.preferredSize = Dimension(100, 28)
            methodCombo.maximumSize = Dimension(100, 28)
            add(methodCombo, BorderLayout.WEST)
            add(urlField, BorderLayout.CENTER)

            sendButton.apply {
                icon = AllIcons.Actions.Execute
                toolTipText = "发送请求"
                preferredSize = Dimension(80, 28)
                addActionListener { sendRequest() }
            }
            add(sendButton, BorderLayout.EAST)
        }
        add(urlBar, BorderLayout.NORTH)

        requestTabs.apply {
            addTab("Query", queryParamPanel)
            addTab("Body", createBodyPanel())
            addTab("Path", pathParamPanel)
            addTab("Headers", headersPanel)
            addTab("Cookies", cookiesPanel)
        }

        val responseBodyPanel = JPanel(BorderLayout()).apply {
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                responseFormatCombo.preferredSize = Dimension(80, 24)
                add(responseFormatCombo)
            }
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(responseBodyArea), BorderLayout.CENTER)
        }

        val responseHeadersTable = com.intellij.ui.table.JBTable(responseHeadersModel).apply {
            setShowGrid(true)
            rowHeight = 24
        }
        val responseCookiesTable = com.intellij.ui.table.JBTable(responseCookiesModel).apply {
            setShowGrid(true)
            rowHeight = 24
        }

        responseTabs.apply {
            addTab("Body", responseBodyPanel)
            addTab("Headers", JBScrollPane(responseHeadersTable))
            addTab("Cookies", JBScrollPane(responseCookiesTable))
        }

        val responsePanel = JPanel(BorderLayout()).apply {
            val header = JPanel(BorderLayout()).apply {
                responseStatusLabel.border = JBUI.Borders.empty(4, 0)
                add(responseStatusLabel, BorderLayout.WEST)
            }
            add(header, BorderLayout.NORTH)
            add(responseTabs, BorderLayout.CENTER)
        }

        val splitter = JBSplitter(true, 0.45f).apply {
            firstComponent = requestTabs
            secondComponent = responsePanel
        }
        add(splitter, BorderLayout.CENTER)
    }

    private fun createBodyPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        val radioBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        val radios = listOf(bodyTypeNone, bodyTypeFormData, bodyTypeUrlEncoded, bodyTypeJson, bodyTypeXml, bodyTypeRaw)
        for (radio in radios) {
            bodyTypeGroup.add(radio)
            radioBar.add(radio)
            radio.addActionListener { switchBodyCard() }
        }
        panel.add(radioBar, BorderLayout.NORTH)

        bodyCardPanel.add(JPanel(), CARD_NONE)
        bodyCardPanel.add(formDataPanel, CARD_FORM_DATA)
        bodyCardPanel.add(urlEncodedPanel, CARD_URL_ENCODED)
        bodyCardPanel.add(JBScrollPane(bodyTextArea), CARD_TEXT)

        panel.add(bodyCardPanel, BorderLayout.CENTER)

        switchBodyCard()
        return panel
    }

    private fun switchBodyCard() {
        val card = when {
            bodyTypeNone.isSelected -> CARD_NONE
            bodyTypeFormData.isSelected -> CARD_FORM_DATA
            bodyTypeUrlEncoded.isSelected -> CARD_URL_ENCODED
            else -> CARD_TEXT
        }
        bodyCardLayout.show(bodyCardPanel, card)
    }

    private fun getSelectedBodyType(): String = when {
        bodyTypeNone.isSelected -> "none"
        bodyTypeFormData.isSelected -> "form-data"
        bodyTypeUrlEncoded.isSelected -> "x-www-form-urlencoded"
        bodyTypeJson.isSelected -> "json"
        bodyTypeXml.isSelected -> "xml"
        bodyTypeRaw.isSelected -> "raw"
        else -> "json"
    }

    private fun selectBodyType(type: String) {
        when (type) {
            "none" -> bodyTypeNone.isSelected = true
            "form-data" -> bodyTypeFormData.isSelected = true
            "x-www-form-urlencoded" -> bodyTypeUrlEncoded.isSelected = true
            "json" -> bodyTypeJson.isSelected = true
            "xml" -> bodyTypeXml.isSelected = true
            "raw" -> bodyTypeRaw.isSelected = true
            else -> bodyTypeJson.isSelected = true
        }
        switchBodyCard()
    }

    fun loadRoute(routeInfo: RouteInfo) {
        methodCombo.selectedItem = routeInfo.method.displayName
        currentModuleName = detectModuleName(routeInfo.file.path)

        val state = PluginSettingsState.getInstance(project)
        val baseUrlEntry = state.findBaseUrlForModuleOrDefault(currentModuleName ?: "")

        urlField.text = if (baseUrlEntry != null) {
            val base = baseUrlEntry.buildBaseUrl().trimEnd('/')
            val path = routeInfo.displayPath
            "$base$path"
        } else {
            routeInfo.displayPath
        }

        pathParamPanel.clear()
        val pathParams = PATH_PARAM_REGEX.findAll(routeInfo.displayPath)
        for (match in pathParams) {
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            pathParamPanel.addParam(name, "")
        }

        if (pathParamPanel.table.rowCount > 1) {
            requestTabs.selectedIndex = 2
        } else {
            requestTabs.selectedIndex = 0
        }
    }

    fun loadHistoryEntry(entry: RequestHistoryEntry) {
        methodCombo.selectedItem = entry.method
        urlField.text = entry.url
        queryParamPanel.setParams(entry.queryParams.map { it.name to it.value })
        headersPanel.setParams(entry.headers.map { it.name to it.value })
        cookiesPanel.setParams(entry.cookies.map { it.name to it.value })

        selectBodyType(entry.bodyType)
        bodyTextArea.text = entry.body
        if (entry.formParams.isNotEmpty()) {
            val pairs = entry.formParams.map { it.name to it.value }
            when (entry.bodyType) {
                "form-data" -> formDataPanel.setParams(pairs)
                "x-www-form-urlencoded" -> urlEncodedPanel.setParams(pairs)
            }
        }

        requestTabs.selectedIndex = 0
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

    private fun mergeParams(
        localParams: List<Pair<String, String>>,
        globalParams: List<Pair<String, String>>
    ): List<Pair<String, String>> {
        val localNames = localParams.map { it.first }.toSet()
        val merged = localParams.toMutableList()
        for (param in globalParams) {
            if (param.first !in localNames) {
                merged.add(param)
            }
        }
        return merged
    }

    private fun getBodyContent(): String {
        return when (getSelectedBodyType()) {
            "none" -> ""
            "form-data" -> formDataPanel.getParamPairs()
                .joinToString("&") { (k, v) -> "$k=$v" }
            "x-www-form-urlencoded" -> urlEncodedPanel.getParams()
                .joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
                }
            else -> bodyTextArea.text
        }
    }

    private fun getFormParamEntries(): MutableList<ParamEntry> {
        return when (getSelectedBodyType()) {
            "form-data" -> formDataPanel.getParamPairs()
                .map { ParamEntry(true, it.first, it.second) }.toMutableList()
            "x-www-form-urlencoded" -> urlEncodedPanel.getParams()
                .map { ParamEntry(true, it.first, it.second) }.toMutableList()
            else -> mutableListOf()
        }
    }

    private fun buildUrlencodedBody(params: List<Pair<String, String>>): Pair<HttpRequest.BodyPublisher, String> {
        val encoded = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        return HttpRequest.BodyPublishers.ofString(encoded) to "application/x-www-form-urlencoded"
    }

    private fun buildMultipartBody(
        params: List<Triple<String, String, String>>
    ): Pair<HttpRequest.BodyPublisher, String> {
        val boundary = "----FormBoundary${UUID.randomUUID().toString().replace("-", "")}"
        val byteArrays = mutableListOf<ByteArray>()
        val lineBreak = "\r\n".toByteArray(StandardCharsets.UTF_8)

        for ((name, value, type) in params) {
            byteArrays.add("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
            if (type == "file") {
                val filePath = Path.of(value)
                val fileName = filePath.fileName.toString()
                byteArrays.add(
                    "Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n".toByteArray(StandardCharsets.UTF_8)
                )
                val mimeType = Files.probeContentType(filePath) ?: "application/octet-stream"
                byteArrays.add("Content-Type: $mimeType\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                byteArrays.add(Files.readAllBytes(filePath))
                byteArrays.add(lineBreak)
            } else {
                byteArrays.add(
                    "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8)
                )
                byteArrays.add(value.toByteArray(StandardCharsets.UTF_8))
                byteArrays.add(lineBreak)
            }
        }
        byteArrays.add("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))

        val totalSize = byteArrays.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (arr in byteArrays) {
            System.arraycopy(arr, 0, result, offset, arr.size)
            offset += arr.size
        }

        return HttpRequest.BodyPublishers.ofByteArray(result) to "multipart/form-data; boundary=$boundary"
    }

    private fun sendRequest() {
        val method = methodCombo.selectedItem as String
        var url = urlField.text.trim()
        if (url.isBlank()) {
            responseBodyArea.text = "请输入请求 URL"
            return
        }

        val state = PluginSettingsState.getInstance(project)
        val globalParams = state.getGlobalParams()

        for ((name, value) in pathParamPanel.getParams()) {
            val encoded = URLEncoder.encode(value, Charsets.UTF_8)
            url = url.replace("{$name}", encoded).replace(":$name", encoded)
        }

        val localQueryParams = queryParamPanel.getParams()
        val mergedQueryParams = mergeParams(localQueryParams, globalParams.getActiveQueryParams())
        if (mergedQueryParams.isNotEmpty()) {
            val queryString = mergedQueryParams.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
            }
            url = if ("?" in url) "$url&$queryString" else "$url?$queryString"
        }

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }

        sendButton.isEnabled = false
        sendButton.text = "发送中..."
        responseBodyArea.text = ""
        responseStatusLabel.text = "请求中..."
        responseStatusLabel.foreground = JBColor.foreground()

        val finalUrl = url
        val bodyType = getSelectedBodyType()
        val bodyContent = getBodyContent().ifBlank {
            if (bodyType in listOf("json", "xml", "raw")) globalParams.bodyContent else ""
        }

        val localHeaders = headersPanel.getParams()
        val mergedHeaders = mergeParams(localHeaders, globalParams.getActiveHeaderParams())

        val localCookies = cookiesPanel.getParams()
        val mergedCookies = mergeParams(localCookies, globalParams.getActiveCookieParams())

        val historyEntry = RequestHistoryEntry(
            timestamp = System.currentTimeMillis(),
            method = method,
            url = finalUrl,
            queryParams = mergedQueryParams.map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            headers = mergedHeaders.map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            cookies = mergedCookies.map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            body = if (bodyType in listOf("json", "xml", "raw")) bodyContent else "",
            bodyType = bodyType,
            formParams = getFormParamEntries(),
        )

        Thread {
            try {
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .timeout(Duration.ofSeconds(30))

                for ((name, value) in mergedHeaders) {
                    if (name.isNotBlank()) builder.header(name, value)
                }

                if (mergedCookies.isNotEmpty()) {
                    builder.header("Cookie", mergedCookies.joinToString("; ") { "${it.first}=${it.second}" })
                }

                val bodyPublisher: HttpRequest.BodyPublisher
                var contentType: String? = null

                if (method in METHODS_WITH_BODY) {
                    when (bodyType) {
                        "none" -> {
                            bodyPublisher = HttpRequest.BodyPublishers.noBody()
                        }
                        "form-data" -> {
                            val (pub, ct) = buildMultipartBody(formDataPanel.getParams())
                            bodyPublisher = pub
                            contentType = ct
                        }
                        "x-www-form-urlencoded" -> {
                            val (pub, ct) = buildUrlencodedBody(urlEncodedPanel.getParams())
                            bodyPublisher = pub
                            contentType = ct
                        }
                        "json" -> {
                            bodyPublisher = if (bodyContent.isNotBlank()) {
                                HttpRequest.BodyPublishers.ofString(bodyContent)
                            } else {
                                HttpRequest.BodyPublishers.noBody()
                            }
                            contentType = "application/json"
                        }
                        "xml" -> {
                            bodyPublisher = if (bodyContent.isNotBlank()) {
                                HttpRequest.BodyPublishers.ofString(bodyContent)
                            } else {
                                HttpRequest.BodyPublishers.noBody()
                            }
                            contentType = "application/xml"
                        }
                        "raw" -> {
                            bodyPublisher = if (bodyContent.isNotBlank()) {
                                HttpRequest.BodyPublishers.ofString(bodyContent)
                            } else {
                                HttpRequest.BodyPublishers.noBody()
                            }
                        }
                        else -> {
                            bodyPublisher = HttpRequest.BodyPublishers.noBody()
                        }
                    }
                } else {
                    bodyPublisher = HttpRequest.BodyPublishers.noBody()
                }

                if (contentType != null) {
                    val hasContentType = mergedHeaders.any { it.first.equals("Content-Type", ignoreCase = true) }
                    if (!hasContentType) {
                        builder.header("Content-Type", contentType)
                    }
                }

                builder.method(method, bodyPublisher)

                val startTime = System.currentTimeMillis()
                val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                val elapsed = System.currentTimeMillis() - startTime

                historyEntry.responseStatus = response.statusCode()
                historyEntry.responseBody = response.body() ?: ""
                historyEntry.responseHeaders = response.headers().map().entries.map {
                    ParamEntry(true, it.key, it.value.joinToString(", "))
                }.toMutableList()
                historyEntry.elapsed = elapsed

                SwingUtilities.invokeLater {
                    displayResponse(response, elapsed)
                    resetSendButton()
                    state.addHistoryEntry(historyEntry)
                }
            } catch (e: Exception) {
                historyEntry.responseStatus = 0
                historyEntry.responseBody = "请求失败: ${e.message}"
                historyEntry.elapsed = 0

                SwingUtilities.invokeLater {
                    responseBodyArea.text = "请求失败: ${e.message}"
                    responseStatusLabel.text = "错误"
                    responseStatusLabel.foreground = ERROR_COLOR
                    resetSendButton()
                    state.addHistoryEntry(historyEntry)
                }
            }
        }.start()
    }

    private fun resetSendButton() {
        sendButton.isEnabled = true
        sendButton.text = "发送"
    }

    private fun displayResponse(response: HttpResponse<String>, elapsed: Long) {
        val status = response.statusCode()
        val statusColor = when {
            status in 200..299 -> SUCCESS_COLOR
            status in 300..399 -> WARN_COLOR
            else -> ERROR_COLOR
        }
        val bodySize = response.body().length
        val sizeText = if (bodySize > 1024) "${bodySize / 1024} KB" else "$bodySize B"

        responseStatusLabel.text = "状态: $status  |  耗时: ${elapsed}ms  |  大小: $sizeText"
        responseStatusLabel.foreground = statusColor

        responseBodyArea.text = tryFormatJson(response.body())
        responseBodyArea.caretPosition = 0

        val headers = response.headers().map().entries.map { it.key to it.value.joinToString(", ") }
        responseHeadersModel.setData(headers)

        val setCookies = response.headers().allValues("set-cookie")
        val cookieEntries = setCookies.map { cookie ->
            val parts = cookie.split(";")[0].split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }
        responseCookiesModel.setData(cookieEntries)

        responseTabs.selectedIndex = 0
    }

    private fun tryFormatJson(text: String): String {
        return GlobalParamsPanel.formatJson(text) ?: text
    }

    class ResponseTableModel : javax.swing.table.AbstractTableModel() {
        private val data = mutableListOf<Pair<String, String>>()

        fun setData(entries: List<Pair<String, String>>) {
            data.clear()
            data.addAll(entries)
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int) = if (column == 0) "名称" else "值"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) data[rowIndex].first else data[rowIndex].second
    }

    companion object {
        private val PATH_PARAM_REGEX = Regex("\\{(\\w+)}|:(\\w+)")
        private val METHODS_WITH_BODY = listOf("POST", "PUT", "PATCH")
        private val SUCCESS_COLOR = JBColor(Color(0x00, 0x80, 0x00), Color(0x98, 0xC3, 0x79))
        private val WARN_COLOR = JBColor(Color(0xCC, 0x80, 0x00), Color(0xE5, 0xC0, 0x7B))
        private val ERROR_COLOR = JBColor(Color(0xCC, 0x00, 0x00), Color(0xE0, 0x6C, 0x75))

        private const val CARD_NONE = "none"
        private const val CARD_FORM_DATA = "form-data"
        private const val CARD_URL_ENCODED = "x-www-form-urlencoded"
        private const val CARD_TEXT = "text"
    }
}
