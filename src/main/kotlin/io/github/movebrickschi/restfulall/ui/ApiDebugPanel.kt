package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.FormFieldType
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.ParamEntry
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.ExpressParamExtractor
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.NestJsParamExtractor
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import io.github.movebrickschi.restfulall.service.PythonParamExtractor
import io.github.movebrickschi.restfulall.service.SpringPsiParamExtractor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ApiDebugPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val methodCombo = JComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"))
    private val urlField = JBTextField()
    private val sendButton = JButton()

    private val queryParamPanel = ParamTablePanel()
    private val bodyTextArea = JsonSyntaxTextPane(editable = true)
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

    private val jsonFormatButton = JButton(AllIcons.Actions.ReformatCode).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        addActionListener {
            val formatted = GlobalParamsPanel.formatJson(bodyTextArea.text)
            if (formatted != null) {
                bodyTextArea.text = formatted
                bodyTextArea.caretPosition = 0
            }
        }
    }

    private val responseBodyArea = JsonSyntaxTextPane(editable = false)
    private val responseHeadersModel = ResponseTableModel()
    private val responseCookiesModel = ResponseTableModel()
    private val responseTabs = JBTabbedPane()
    private val responseStatusLabel = JBLabel()
    private val responseFormatCombo = JComboBox(arrayOf("JSON", "Text", "HTML", "XML"))

    private val wsMessageLabel = JBLabel().apply { border = JBUI.Borders.emptyRight(4) }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private var currentModuleName: String? = null

    // SSE / streaming state
    @Volatile private var sseCancelled = false
    @Volatile private var isSseStreaming = false
    private var sseThread: Thread? = null

    // WebSocket state
    private val wsMessageField = JBTextField()
    private val wsSendMsgButton = JButton()
    private val wsMessagePanel = JPanel(BorderLayout(4, 0)).apply {
        border = JBUI.Borders.empty(2, 0, 0, 0)
        isVisible = false
        add(wsMessageLabel, BorderLayout.WEST)
        add(wsMessageField, BorderLayout.CENTER)
        wsSendMsgButton.preferredSize = Dimension(60, 26)
        add(wsSendMsgButton, BorderLayout.EAST)
    }
    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var isWsConnected = false

    init {
        border = JBUI.Borders.empty(2, 4, 4, 4)
        setupUI()
        applyI18n()

        ApplicationManager.getApplication().messageBus
            .connect(project)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
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
                preferredSize = Dimension(80, 28)
                addActionListener {
                    when {
                        isSseStreaming          -> stopSseStream()
                        isWsConnected          -> disconnectWebSocket()
                        isWsUrl(urlField.text) -> connectWebSocket()
                        else                   -> sendRequest()
                    }
                }
            }
            add(sendButton, BorderLayout.EAST)
        }
        add(urlBar, BorderLayout.NORTH)

        urlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = syncUrlMode()
            override fun removeUpdate(e: DocumentEvent) = syncUrlMode()
            override fun changedUpdate(e: DocumentEvent) = syncUrlMode()
        })

        wsSendMsgButton.addActionListener { sendWsMessage() }
        wsMessageField.addActionListener { sendWsMessage() }

        val requestPanel = JPanel(BorderLayout()).apply {
            add(requestTabs, BorderLayout.CENTER)
            add(wsMessagePanel, BorderLayout.SOUTH)
        }

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
            firstComponent = requestPanel
            secondComponent = responsePanel
        }
        add(splitter, BorderLayout.CENTER)
    }

    private fun applyI18n() {
        jsonFormatButton.toolTipText = MyMessageBundle.message("debug.json.format.tooltip")
        sendButton.toolTipText = MyMessageBundle.message("debug.send.tooltip")
        wsMessageField.toolTipText = MyMessageBundle.message("debug.ws.input.tooltip")
        wsMessageLabel.text = MyMessageBundle.message("debug.ws.message.label")
        wsSendMsgButton.text = MyMessageBundle.message("debug.send.button")

        updateSendButtonForUrl()
        responseHeadersModel.fireTableStructureChanged()
        responseCookiesModel.fireTableStructureChanged()

        queryParamPanel.refreshColumnHeaders()
        pathParamPanel.refreshColumnHeaders()
        headersPanel.refreshColumnHeaders()
        cookiesPanel.refreshColumnHeaders()
        urlEncodedPanel.refreshColumnHeaders()
        formDataPanel.refreshColumnHeaders()
    }

    // ── URL mode sync ─────────────────────────────────────────────────────────

    private fun isWsUrl(url: String): Boolean {
        val t = url.trim()
        return t.startsWith("ws://", ignoreCase = true) || t.startsWith("wss://", ignoreCase = true)
    }

    private fun syncUrlMode() {
        if (isSseStreaming || isWsConnected) return
        val isWs = isWsUrl(urlField.text)
        methodCombo.isVisible = !isWs
        sendButton.text = if (isWs) MyMessageBundle.message("debug.connect.button")
                          else MyMessageBundle.message("debug.send.button")
        sendButton.icon = AllIcons.Actions.Execute
    }

    private fun updateSendButtonForUrl() {
        val isWs = isWsUrl(urlField.text)
        methodCombo.isVisible = !isWs && !isWsConnected
        sendButton.icon = AllIcons.Actions.Execute
        sendButton.text = when {
            isWsConnected -> MyMessageBundle.message("debug.disconnect.button")
            isWs          -> MyMessageBundle.message("debug.connect.button")
            else          -> MyMessageBundle.message("debug.send.button")
        }
    }

    // ── Body panel ────────────────────────────────────────────────────────────

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
        val textBodyToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(jsonFormatButton)
        }
        val textBodyPanel = JPanel(BorderLayout()).apply {
            add(textBodyToolbar, BorderLayout.NORTH)
            add(JBScrollPane(bodyTextArea), BorderLayout.CENTER)
        }
        bodyCardPanel.add(textBodyPanel, CARD_TEXT)

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
        jsonFormatButton.isVisible = bodyTypeJson.isSelected
        if (bodyTypeJson.isSelected) bodyTextArea.applyHighlighting()
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

    // ── Route / history loading ───────────────────────────────────────────────

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
        queryParamPanel.clear()
        headersPanel.clear()
        cookiesPanel.clear()
        bodyTextArea.text = ""
        selectBodyType("none")

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

        ReadAction.nonBlocking<ExtractedMethodParams?> {
            extractParams(routeInfo)
        }
        .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
            if (result != null) populateFromExtraction(result)
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun extractParams(routeInfo: RouteInfo): ExtractedMethodParams? {
        return when (routeInfo.framework) {
            Framework.SPRING -> SpringPsiParamExtractor.extract(project, routeInfo)
            Framework.NESTJS -> NestJsParamExtractor.extract(routeInfo)
            Framework.EXPRESS -> ExpressParamExtractor.extract(routeInfo)
            Framework.PYTHON -> PythonParamExtractor.extract(routeInfo)
        }
    }

    private fun populateFromExtraction(params: ExtractedMethodParams) {
        if (params.pathParams.isNotEmpty()) {
            pathParamPanel.clear()
            for (p in params.pathParams) {
                pathParamPanel.addParam(p.name, p.testValue)
            }
        }
        if (params.queryParams.isNotEmpty()) {
            queryParamPanel.setParams(params.queryParams.map { it.name to it.testValue })
        }
        if (params.headerParams.isNotEmpty()) {
            headersPanel.setParams(params.headerParams.map { it.name to it.testValue })
        }
        if (params.cookieParams.isNotEmpty()) {
            cookiesPanel.setParams(params.cookieParams.map { it.name to it.testValue })
        }

        // form-data 优先：multipart 端点不能再带 JSON 主 body
        if (params.formParams.isNotEmpty()) {
            selectBodyType("form-data")
            formDataPanel.setRows(params.formParams.map {
                Triple(it.name, it.testValue, if (it.type == FormFieldType.FILE) "file" else "text")
            })
        } else if (params.bodyJson != null) {
            selectBodyType("json")
            bodyTextArea.text = params.bodyJson
        }

        when {
            params.formParams.isNotEmpty() ->
                requestTabs.selectedIndex = 1
            params.bodyJson != null && params.queryParams.isEmpty() && params.pathParams.isEmpty() ->
                requestTabs.selectedIndex = 1
            params.pathParams.isNotEmpty() ->
                requestTabs.selectedIndex = 2
            params.queryParams.isNotEmpty() ->
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

        restoreResponseFromHistory(entry)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
            if (param.first !in localNames) merged.add(param)
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

    // ── gzip / deflate decompression ──────────────────────────────────────────

    private fun decompressIfNeeded(stream: InputStream, headers: HttpHeaders): InputStream {
        return when (headers.firstValue("content-encoding").orElse("").lowercase()) {
            "gzip"    -> java.util.zip.GZIPInputStream(stream)
            "deflate" -> java.util.zip.InflaterInputStream(stream)
            else      -> stream
        }
    }

    // ── HTTP request dispatch ─────────────────────────────────────────────────

    private fun sendRequest() {
        val method = methodCombo.selectedItem as String
        var url = urlField.text.trim()
        if (url.isBlank()) {
            responseBodyArea.text = MyMessageBundle.message("debug.url.empty")
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
        sendButton.text = MyMessageBundle.message("debug.sending.button")
        responseBodyArea.text = ""
        responseStatusLabel.text = MyMessageBundle.message("debug.status.requesting")
        responseStatusLabel.foreground = JBColor.foreground()
        sseCancelled = false

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

        val thread = Thread {
            var reconnectCount = 0
            var lastEventId: String? = null

            loop@ while (true) {
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

                    if (lastEventId != null) {
                        builder.header("Last-Event-ID", lastEventId)
                    }

                    val bodyPublisher: HttpRequest.BodyPublisher
                    var contentType: String? = null

                    if (method in METHODS_WITH_BODY) {
                        when (bodyType) {
                            "none" -> bodyPublisher = HttpRequest.BodyPublishers.noBody()
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
                                bodyPublisher = if (bodyContent.isNotBlank())
                                    HttpRequest.BodyPublishers.ofString(bodyContent)
                                else
                                    HttpRequest.BodyPublishers.noBody()
                                contentType = "application/json"
                            }
                            "xml" -> {
                                bodyPublisher = if (bodyContent.isNotBlank())
                                    HttpRequest.BodyPublishers.ofString(bodyContent)
                                else
                                    HttpRequest.BodyPublishers.noBody()
                                contentType = "application/xml"
                            }
                            "raw" -> {
                                bodyPublisher = if (bodyContent.isNotBlank())
                                    HttpRequest.BodyPublishers.ofString(bodyContent)
                                else
                                    HttpRequest.BodyPublishers.noBody()
                            }
                            else -> bodyPublisher = HttpRequest.BodyPublishers.noBody()
                        }
                    } else {
                        bodyPublisher = HttpRequest.BodyPublishers.noBody()
                    }

                    if (contentType != null) {
                        val hasContentType = mergedHeaders.any { it.first.equals("Content-Type", ignoreCase = true) }
                        if (!hasContentType) builder.header("Content-Type", contentType)
                    }

                    builder.method(method, bodyPublisher)

                    val startTime = System.currentTimeMillis()
                    val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())

                    val respContentType = response.headers().firstValue("content-type").orElse("")
                    val isSSE = respContentType.contains("text/event-stream", ignoreCase = true)
                    val isNdjson = !isSSE && (
                        respContentType.contains("x-ndjson", ignoreCase = true) ||
                        respContentType.contains("jsonlines", ignoreCase = true)
                    )

                    historyEntry.responseStatus = response.statusCode()
                    historyEntry.responseHeaders = response.headers().map().entries.map {
                        ParamEntry(true, it.key, it.value.joinToString(", "))
                    }.toMutableList()

                    when {
                        isSSE -> {
                            val result = handleSseStream(response, historyEntry, startTime, state)
                            if (result.shouldReconnect && reconnectCount < SSE_MAX_RECONNECTS && !sseCancelled) {
                                reconnectCount++
                                lastEventId = result.lastEventId
                                val retryMs = result.retryMs
                                SwingUtilities.invokeLater {
                                    responseStatusLabel.text = MyMessageBundle.message(
                                        "debug.sse.reconnecting", reconnectCount, SSE_MAX_RECONNECTS
                                    )
                                    responseStatusLabel.foreground = WARN_COLOR
                                }
                                Thread.sleep(retryMs)
                                continue@loop
                            }
                            SwingUtilities.invokeLater {
                                val bodySize = historyEntry.responseBody.length
                                val sizeText = if (bodySize > 1024) "${bodySize / 1024} KB" else "$bodySize B"
                                val statusColor = if (historyEntry.responseStatus in 200..299) SUCCESS_COLOR else ERROR_COLOR
                                val endLabel = if (sseCancelled)
                                    MyMessageBundle.message("debug.stream.stopped")
                                else
                                    MyMessageBundle.message("debug.stream.end")
                                responseStatusLabel.text = MyMessageBundle.message(
                                    "debug.status.done.stream",
                                    historyEntry.responseStatus, historyEntry.elapsed, sizeText, endLabel
                                )
                                responseStatusLabel.foreground = statusColor
                                resetSendButton()
                                state.addHistoryEntry(historyEntry)
                            }
                        }
                        isNdjson -> handleNdjsonStream(response, historyEntry, startTime, state)
                        else -> {
                            val bodyStream = decompressIfNeeded(response.body(), response.headers())
                            val bodyBytes = bodyStream.readBytes()
                            val bodyString = String(bodyBytes, Charsets.UTF_8)
                            val elapsed = System.currentTimeMillis() - startTime

                            historyEntry.responseBody = bodyString
                            historyEntry.elapsed = elapsed

                            SwingUtilities.invokeLater {
                                displayResponse(response.statusCode(), bodyString, response.headers(), elapsed)
                                resetSendButton()
                                state.addHistoryEntry(historyEntry)
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    historyEntry.responseStatus = 0
                    historyEntry.responseBody = MyMessageBundle.message("debug.request.failed", e.message ?: "")
                    historyEntry.elapsed = 0

                    SwingUtilities.invokeLater {
                        responseBodyArea.text = MyMessageBundle.message("debug.request.failed", e.message ?: "")
                        responseStatusLabel.text = MyMessageBundle.message("debug.status.error")
                        responseStatusLabel.foreground = ERROR_COLOR
                        resetSendButton()
                        state.addHistoryEntry(historyEntry)
                    }
                }
                break@loop
            }
        }
        sseThread = thread
        thread.start()
    }

    // ── SSE stream handler ────────────────────────────────────────────────────

    private data class SseResult(
        val shouldReconnect: Boolean,
        val lastEventId: String?,
        val retryMs: Long
    )

    private fun stopSseStream() {
        sseCancelled = true
        sseThread?.interrupt()
    }

    private fun handleSseStream(
        response: HttpResponse<InputStream>,
        historyEntry: RequestHistoryEntry,
        startTime: Long,
        @Suppress("UNUSED_PARAMETER") state: PluginSettingsState
    ): SseResult {
        val statusCode = response.statusCode()
        val headers = response.headers()

        SwingUtilities.invokeLater {
            isSseStreaming = true
            sendButton.text = MyMessageBundle.message("debug.stop.button")
            sendButton.icon = AllIcons.Actions.Suspend
            sendButton.isEnabled = true

            val statusColor = if (statusCode in 200..299) SUCCESS_COLOR else ERROR_COLOR
            responseStatusLabel.text = MyMessageBundle.message("debug.sse.streaming", statusCode)
            responseStatusLabel.foreground = statusColor
            responseBodyArea.text = ""
            responseTabs.selectedIndex = 0

            responseHeadersModel.setData(headers.map().entries.map { it.key to it.value.joinToString(", ") })
            val setCookies = headers.allValues("set-cookie")
            responseCookiesModel.setData(setCookies.map { cookie ->
                val parts = cookie.split(";")[0].split("=", limit = 2)
                parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
            })
        }

        val accumulator = StringBuilder()
        val dataBuffer = StringBuilder()
        var currentEventType = "message"
        var currentEventId: String? = null
        var localLastEventId: String? = null
        var localRetryMs = 3000L
        var eventCount = 0
        var shouldReconnect = false

        try {
            decompressIfNeeded(response.body(), headers).bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (!sseCancelled && !Thread.currentThread().isInterrupted) {
                    line = reader.readLine() ?: break
                    when {
                        line.startsWith("data:") -> {
                            val value = line.removePrefix("data:").let {
                                if (it.startsWith(" ")) it.substring(1) else it
                            }
                            dataBuffer.append(value).append("\n")
                        }
                        line.startsWith("event:") -> {
                            currentEventType = line.removePrefix("event:").trim()
                        }
                        line.startsWith("id:") -> {
                            currentEventId = line.removePrefix("id:").trim()
                            localLastEventId = currentEventId
                        }
                        line.startsWith("retry:") -> {
                            line.removePrefix("retry:").trim().toLongOrNull()?.let { localRetryMs = it }
                        }
                        line.startsWith(":") -> {
                            // comment / heartbeat keep-alive — intentionally ignored
                        }
                        line.isEmpty() -> {
                            if (dataBuffer.isNotEmpty()) {
                                eventCount++
                                val data = dataBuffer.toString().trimEnd('\n')
                                dataBuffer.clear()
                                val idPart = if (currentEventId != null) " id:$currentEventId" else ""
                                val block = "[#$eventCount event:$currentEventType$idPart]\n${tryFormatJson(data)}\n\n"
                                accumulator.append(block)
                                val received = accumulator.length
                                val count = eventCount
                                SwingUtilities.invokeLater {
                                    responseBodyArea.appendPlain(block)
                                    responseBodyArea.caretPosition = responseBodyArea.document.length
                                    val sizeText = if (received > 1024) "${received / 1024} KB" else "$received B"
                                    val statusColor = if (statusCode in 200..299) SUCCESS_COLOR else ERROR_COLOR
                                    responseStatusLabel.text = MyMessageBundle.message(
                                        "debug.sse.progress", statusCode, count, sizeText
                                    )
                                    responseStatusLabel.foreground = statusColor
                                }
                                currentEventType = "message"
                                currentEventId = null
                            }
                        }
                    }
                }
                if (dataBuffer.isNotEmpty() && !sseCancelled) {
                    eventCount++
                    val data = dataBuffer.toString().trimEnd('\n')
                    val block = "[#$eventCount event:$currentEventType]\n${tryFormatJson(data)}\n\n"
                    accumulator.append(block)
                    SwingUtilities.invokeLater {
                        responseBodyArea.appendPlain(block)
                        responseBodyArea.caretPosition = responseBodyArea.document.length
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: java.io.IOException) {
            if (!sseCancelled) {
                shouldReconnect = true
                val msg = e.message ?: MyMessageBundle.message("debug.sse.disconnected")
                SwingUtilities.invokeLater {
                    responseBodyArea.appendPlain(MyMessageBundle.message("debug.sse.disconnected.line", msg))
                }
            }
        }

        val finalBody = accumulator.toString()
        val elapsed = System.currentTimeMillis() - startTime
        historyEntry.responseBody = finalBody
        historyEntry.elapsed = elapsed

        return SseResult(
            shouldReconnect = shouldReconnect && !sseCancelled,
            lastEventId = localLastEventId,
            retryMs = localRetryMs
        )
    }

    // ── NDJSON stream handler ─────────────────────────────────────────────────

    private fun handleNdjsonStream(
        response: HttpResponse<InputStream>,
        historyEntry: RequestHistoryEntry,
        startTime: Long,
        state: PluginSettingsState
    ) {
        val statusCode = response.statusCode()
        val headers = response.headers()

        SwingUtilities.invokeLater {
            isSseStreaming = true
            sendButton.text = MyMessageBundle.message("debug.stop.button")
            sendButton.icon = AllIcons.Actions.Suspend
            sendButton.isEnabled = true

            val statusColor = if (statusCode in 200..299) SUCCESS_COLOR else ERROR_COLOR
            responseStatusLabel.text = MyMessageBundle.message("debug.ndjson.streaming", statusCode)
            responseStatusLabel.foreground = statusColor
            responseBodyArea.text = ""
            responseTabs.selectedIndex = 0

            responseHeadersModel.setData(headers.map().entries.map { it.key to it.value.joinToString(", ") })
        }

        val accumulator = StringBuilder()
        var lineCount = 0

        try {
            decompressIfNeeded(response.body(), headers).bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (!sseCancelled && !Thread.currentThread().isInterrupted) {
                    line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    lineCount++
                    val formatted = tryFormatJson(line) + "\n"
                    accumulator.append(formatted)
                    val received = accumulator.length
                    val count = lineCount
                    SwingUtilities.invokeLater {
                        responseBodyArea.appendPlain(formatted)
                        responseBodyArea.caretPosition = responseBodyArea.document.length
                        val sizeText = if (received > 1024) "${received / 1024} KB" else "$received B"
                        val statusColor = if (statusCode in 200..299) SUCCESS_COLOR else ERROR_COLOR
                        responseStatusLabel.text = MyMessageBundle.message(
                            "debug.ndjson.progress", statusCode, count, sizeText
                        )
                        responseStatusLabel.foreground = statusColor
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: java.io.IOException) {
            if (!sseCancelled) {
                val msg = e.message ?: MyMessageBundle.message("debug.stream.read.failed")
                SwingUtilities.invokeLater {
                    responseBodyArea.appendPlain(MyMessageBundle.message("debug.stream.error.line", msg))
                }
            }
        }

        val finalBody = accumulator.toString()
        val elapsed = System.currentTimeMillis() - startTime
        historyEntry.responseBody = finalBody
        historyEntry.elapsed = elapsed

        SwingUtilities.invokeLater {
            val bodySize = finalBody.length
            val sizeText = if (bodySize > 1024) "${bodySize / 1024} KB" else "$bodySize B"
            val endLabel = if (sseCancelled)
                MyMessageBundle.message("debug.stream.stopped")
            else
                MyMessageBundle.message("debug.stream.end")
            val statusColor = if (statusCode in 200..299) SUCCESS_COLOR else ERROR_COLOR
            responseStatusLabel.text = MyMessageBundle.message(
                "debug.status.done.ndjson",
                statusCode, elapsed, sizeText, endLabel, lineCount
            )
            responseStatusLabel.foreground = statusColor
            resetSendButton()
            state.addHistoryEntry(historyEntry)
        }
    }

    // ── WebSocket ─────────────────────────────────────────────────────────────

    private fun connectWebSocket() {
        val url = urlField.text.trim()
        if (!isWsUrl(url)) return

        sendButton.isEnabled = false
        sendButton.text = MyMessageBundle.message("debug.connecting.button")
        responseBodyArea.text = ""
        responseStatusLabel.text = MyMessageBundle.message("debug.ws.connecting")
        responseStatusLabel.foreground = JBColor.foreground()

        val listener = object : WebSocket.Listener {
            private val textBuffer = StringBuilder()

            override fun onOpen(ws: WebSocket) {
                webSocket = ws
                SwingUtilities.invokeLater {
                    isWsConnected = true
                    sendButton.text = MyMessageBundle.message("debug.disconnect.button")
                    sendButton.icon = AllIcons.Actions.Suspend
                    sendButton.isEnabled = true
                    wsMessagePanel.isVisible = true
                    responseStatusLabel.text = MyMessageBundle.message("debug.ws.connected")
                    responseStatusLabel.foreground = SUCCESS_COLOR
                }
                appendWsEvent(MyMessageBundle.message("debug.ws.event.connected"), url)
                ws.request(1)
            }

            override fun onText(
                ws: WebSocket,
                data: CharSequence,
                last: Boolean
            ): CompletionStage<*>? {
                textBuffer.append(data)
                if (last) {
                    val msg = textBuffer.toString()
                    textBuffer.clear()
                    appendWsEvent(MyMessageBundle.message("debug.ws.event.received"), msg)
                }
                ws.request(1)
                return null
            }

            override fun onClose(ws: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                val r = reason.ifEmpty { "Normal" }
                appendWsEvent(MyMessageBundle.message("debug.ws.event.disconnected"), "code=$statusCode reason=$r")
                SwingUtilities.invokeLater {
                    isWsConnected = false
                    webSocket = null
                    wsMessagePanel.isVisible = false
                    updateSendButtonForUrl()
                    responseStatusLabel.text = MyMessageBundle.message("debug.ws.disconnected", statusCode)
                    responseStatusLabel.foreground = WARN_COLOR
                }
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(ws: WebSocket, error: Throwable) {
                appendWsEvent(
                    MyMessageBundle.message("debug.ws.event.error"),
                    error.message ?: MyMessageBundle.message("debug.ws.unknown.error")
                )
                SwingUtilities.invokeLater {
                    isWsConnected = false
                    webSocket = null
                    wsMessagePanel.isVisible = false
                    updateSendButtonForUrl()
                    responseStatusLabel.text = MyMessageBundle.message("debug.ws.error", error.message ?: "")
                    responseStatusLabel.foreground = ERROR_COLOR
                }
            }
        }

        httpClient.newWebSocketBuilder()
            .buildAsync(URI.create(url), listener)
            .exceptionally { ex ->
                SwingUtilities.invokeLater {
                    responseBodyArea.text = MyMessageBundle.message(
                        "debug.ws.connect.failed.body",
                        ex.cause?.message ?: ex.message ?: ""
                    )
                    responseStatusLabel.text = MyMessageBundle.message("debug.ws.connect.failed.label")
                    responseStatusLabel.foreground = ERROR_COLOR
                    updateSendButtonForUrl()
                }
                null
            }
    }

    private fun disconnectWebSocket() {
        webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, MyMessageBundle.message("debug.ws.user.close"))
        webSocket = null
        isWsConnected = false
        wsMessagePanel.isVisible = false
        updateSendButtonForUrl()
    }

    private fun sendWsMessage() {
        val msg = wsMessageField.text.trim()
        if (msg.isEmpty() || webSocket == null) return
        webSocket?.sendText(msg, true)?.thenRun {
            appendWsEvent(MyMessageBundle.message("debug.ws.event.sent"), msg)
            SwingUtilities.invokeLater { wsMessageField.text = "" }
        }
    }

    private fun appendWsEvent(type: String, content: String) {
        val time = LocalTime.now().format(WS_TIME_FMT)
        val line = "[$time $type] $content\n"
        SwingUtilities.invokeLater {
            responseBodyArea.appendPlain(line)
            responseBodyArea.caretPosition = responseBodyArea.document.length
        }
    }

    // ── Button state ──────────────────────────────────────────────────────────

    private fun resetSendButton() {
        isSseStreaming = false
        sendButton.isEnabled = true
        updateSendButtonForUrl()
    }

    // ── Response display ──────────────────────────────────────────────────────

    private fun displayResponse(statusCode: Int, body: String, headers: HttpHeaders, elapsed: Long) {
        val statusColor = when {
            statusCode in 200..299 -> SUCCESS_COLOR
            statusCode in 300..399 -> WARN_COLOR
            else -> ERROR_COLOR
        }
        val bodySize = body.length
        val sizeText = if (bodySize > 1024) "${bodySize / 1024} KB" else "$bodySize B"

        responseStatusLabel.text = MyMessageBundle.message("debug.status.done", statusCode, elapsed, sizeText)
        responseStatusLabel.foreground = statusColor

        responseBodyArea.text = tryFormatJson(body)
        responseBodyArea.caretPosition = 0

        responseHeadersModel.setData(headers.map().entries.map { it.key to it.value.joinToString(", ") })

        val setCookies = headers.allValues("set-cookie")
        val cookieEntries = setCookies.map { cookie ->
            val parts = cookie.split(";")[0].split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }
        responseCookiesModel.setData(cookieEntries)

        responseTabs.selectedIndex = 0
    }

    private fun restoreResponseFromHistory(entry: RequestHistoryEntry) {
        val body = entry.responseBody
        val bodySize = body.length
        val sizeText = if (bodySize > 1024) "${bodySize / 1024} KB" else "$bodySize B"

        if (entry.responseStatus > 0) {
            val statusColor = when {
                entry.responseStatus in 200..299 -> SUCCESS_COLOR
                entry.responseStatus in 300..399 -> WARN_COLOR
                else -> ERROR_COLOR
            }
            responseStatusLabel.text = MyMessageBundle.message(
                "debug.history.status", entry.responseStatus, entry.elapsed, sizeText
            )
            responseStatusLabel.foreground = statusColor
        } else {
            responseStatusLabel.text = MyMessageBundle.message("debug.history.incomplete")
            responseStatusLabel.foreground = ERROR_COLOR
        }

        responseBodyArea.text = tryFormatJson(body)
        responseBodyArea.caretPosition = 0

        responseHeadersModel.setData(entry.responseHeaders.map { it.name to it.value })

        val setCookieValues = entry.responseHeaders
            .filter { it.name.equals("set-cookie", ignoreCase = true) }
            .flatMap { it.value.split(Regex("(?i),\\s*(?=[A-Za-z0-9_-]+=)")) }
        val cookieEntries = setCookieValues.map { cookie ->
            val parts = cookie.split(";")[0].split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }
        responseCookiesModel.setData(cookieEntries)

        responseTabs.selectedIndex = 0
    }

    private fun tryFormatJson(text: String): String {
        return GlobalParamsPanel.formatJson(text) ?: text
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    class ResponseTableModel : javax.swing.table.AbstractTableModel() {
        private val data = mutableListOf<Pair<String, String>>()

        fun setData(entries: List<Pair<String, String>>) {
            data.clear()
            data.addAll(entries)
            fireTableDataChanged()
        }

        override fun getRowCount() = data.size
        override fun getColumnCount() = 2
        override fun getColumnName(column: Int): String = if (column == 0)
            MyMessageBundle.message("debug.response.column.name")
        else
            MyMessageBundle.message("debug.response.column.value")
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
            if (columnIndex == 0) data[rowIndex].first else data[rowIndex].second
    }

    companion object {
        private val PATH_PARAM_REGEX = Regex("\\{(\\w+)}|:(\\w+)")
        private val METHODS_WITH_BODY = listOf("POST", "PUT", "PATCH")
        private val SUCCESS_COLOR = JBColor(Color(0x00, 0x80, 0x00), Color(0x98, 0xC3, 0x79))
        private val WARN_COLOR = JBColor(Color(0xCC, 0x80, 0x00), Color(0xE5, 0xC0, 0x7B))
        private val ERROR_COLOR = JBColor(Color(0xCC, 0x00, 0x00), Color(0xE0, 0x6C, 0x75))
        private val WS_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        private const val CARD_NONE = "none"
        private const val CARD_FORM_DATA = "form-data"
        private const val CARD_URL_ENCODED = "x-www-form-urlencoded"
        private const val CARD_TEXT = "text"
        private const val SSE_MAX_RECONNECTS = 5
    }
}
