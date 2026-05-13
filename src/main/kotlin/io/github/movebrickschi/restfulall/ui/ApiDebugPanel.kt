package io.github.movebrickschi.restfulall.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
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
import io.github.movebrickschi.restfulall.model.AuthConfig
import io.github.movebrickschi.restfulall.model.CollectionItem
import io.github.movebrickschi.restfulall.service.AiException
import io.github.movebrickschi.restfulall.service.AiService
import io.github.movebrickschi.restfulall.service.AiUsageQuota
import io.github.movebrickschi.restfulall.service.AssertionEngine
import io.github.movebrickschi.restfulall.service.AuthService
import io.github.movebrickschi.restfulall.service.CurlConverter
import io.github.movebrickschi.restfulall.service.ExpressParamExtractor
import io.github.movebrickschi.restfulall.service.LanguageChangeListener
import io.github.movebrickschi.restfulall.service.NestJsParamExtractor
import io.github.movebrickschi.restfulall.service.OpenApiParamExtractor
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import io.github.movebrickschi.restfulall.service.PythonParamExtractor
import io.github.movebrickschi.restfulall.service.MultipartFormParam
import io.github.movebrickschi.restfulall.service.RequestExecutor
import io.github.movebrickschi.restfulall.service.RequestSpec
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
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ApiDebugPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val methodCombo = JComboBox(arrayOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"))
    private val urlField = JBTextField()
    private val sendButton = JButton()

    /** F10: URL 行下方的接口注释展示区。description 为 null/blank 时整块隐藏，避免占视觉。 */
    private val descriptionArea = JTextArea().apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(2, 4, 4, 4)
        font = font.deriveFont(font.size2D - 1f)
        foreground = JBColor(Color(0x70, 0x70, 0x70), Color(0x9D, 0x9D, 0x9D))
    }
    private val descriptionPanel = JPanel(BorderLayout()).apply {
        isVisible = false
        add(descriptionArea, BorderLayout.CENTER)
    }

    private val curlImportButton = JButton(AllIcons.ToolbarDecorator.Import).apply {
        isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        toolTipText = "Import cURL"
    }
    private val curlExportButton = JButton(AllIcons.ToolbarDecorator.Export).apply {
        isBorderPainted = false; isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        toolTipText = "Copy as cURL"
    }

    private var currentAuthConfig = AuthConfig()
    private val authTypeCombo = JComboBox(AuthConfig.AuthType.entries.toTypedArray())
    private val authBearerField = JBTextField()
    private val authBasicUserField = JBTextField()
    private val authBasicPassField = JPasswordField()
    private val authApiKeyNameField = JBTextField()
    private val authApiKeyValueField = JBTextField()
    private val authApiKeyLocationCombo = JComboBox(AuthConfig.ApiKeyLocation.entries.toTypedArray())
    private val authCardLayout = CardLayout()
    private val authCardPanel = JPanel(authCardLayout)

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

    /** v1.3.1 P0-1 - AI 智能参数填充按钮（Pro 功能，Free 用户置灰）。*/
    private val aiFillButton = JButton(AllIcons.Actions.Lightning).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        toolTipText = "AI Fill"
        addActionListener { onAiFillClicked() }
    }

    /** v1.3.1 P0-1 - AI 配置入口按钮（baseUrl / model / apiKey）。*/
    private val aiConfigButton = JButton(AllIcons.General.Settings).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        toolTipText = "AI Settings"
        addActionListener { showAiConfigDialog() }
    }

    /** v1.3.2 P0-3 - AI 生成测试用例按钮（Pro）。*/
    private val aiTestCaseButton = JButton(AllIcons.Actions.RunAll).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        toolTipText = "AI Generate Test Cases"
        addActionListener { onAiGenerateCasesClicked() }
    }

    /** v1.3.2 P0-2 - AI 接口诊断按钮（Pro）。挂在 response statusLabel 旁边。*/
    private val aiDiagnoseButton = JButton(AllIcons.General.BalloonError).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        toolTipText = "AI Diagnose"
        addActionListener { onAiDiagnoseClicked() }
    }

    /** F7: 断言编辑/结果面板；持有最近一次响应快照供「重跑」按钮使用。 */
    private val assertionPanel = AssertionTablePanel()

    /** F7: 最近一次响应快照（body / statusCode / elapsed / headers），用于重跑断言。 */
    private var lastResponseSnapshot: ResponseSnapshot? = null

    /** v1.3.2 F6: 响应高级查看面板（Pretty/Raw/Tree/Table/Image/Download）。*/
    private val responseViewPanel = ResponseViewPanel(project)

    /**
     * 流式 / 历史回放复用旧 API：[responseBodyArea] 现在是 [responseViewPanel] 内部 prettyArea 的别名。
     * SSE / NDJSON 增量 append 仍按行写入；非流式响应通过 [responseViewPanel.setResponse] 触发视图分发。
     */
    private val responseBodyArea: JsonSyntaxTextPane get() = responseViewPanel.rawTextPane()
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

        assertionPanel.onRerunRequested = {
            val snap = lastResponseSnapshot
            if (snap != null) {
                runAssertionsWithSnapshot(snap.statusCode, snap.body, snap.headers, snap.elapsed)
            }
        }

        ApplicationManager.getApplication().messageBus
            .connect(this)
            .subscribe(LanguageChangeListener.TOPIC, LanguageChangeListener { applyI18n() })
    }

    override fun dispose() {
        stopSseStream()
        disconnectWebSocket()
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
            curlImportButton.addActionListener { importFromCurl() }
            curlExportButton.addActionListener { exportAsCurl() }
            val layoutButton = JButton(AllIcons.Actions.SplitVertically).apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                toolTipText = "Cycle layout (vertical / horizontal / request-only / response-only)"
                addActionListener { cycleLayout() }
            }
            // v1.3.3 P2-8 - 接口压测按钮（Pro）
            val loadTestButton = JButton(AllIcons.Nodes.Plugin).apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                toolTipText = "Load Test (Pro)"
                addActionListener { openLoadTestDialog() }
            }
            // v1.3.3 P2-11 - 签名插件按钮（Pro）
            val signatureButton = JButton(AllIcons.Actions.SetDefault).apply {
                isBorderPainted = false
                isContentAreaFilled = false
                preferredSize = Dimension(28, 28)
                toolTipText = "Apply AWS V4 / OAuth1 Signature (Pro)"
                addActionListener { applySignaturePopup() }
            }
            val rightBar = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0))
            rightBar.add(curlImportButton)
            rightBar.add(curlExportButton)
            rightBar.add(layoutButton)
            rightBar.add(signatureButton)
            rightBar.add(loadTestButton)
            rightBar.add(sendButton)
            add(rightBar, BorderLayout.EAST)
        }
        val northStack = JPanel(BorderLayout()).apply {
            add(urlBar, BorderLayout.NORTH)
            add(descriptionPanel, BorderLayout.CENTER)
        }
        add(northStack, BorderLayout.NORTH)

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

        // F6: 把原 raw text area 替换为支持 6 种视图的 ResponseViewPanel；
        // responseFormatCombo (JSON/Text/HTML/XML) 仍在工具栏外层保留，但其语义已并入 viewMode 选择，
        // 暂作为高级响应解析格式 hint 占位，后续 patch 释放或重组。
        val responseBodyPanel = JPanel(BorderLayout()).apply {
            add(responseViewPanel, BorderLayout.CENTER)
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
            addTab("Assertions", assertionPanel)
        }

        val responsePanel = JPanel(BorderLayout()).apply {
            val header = JPanel(BorderLayout()).apply {
                responseStatusLabel.border = JBUI.Borders.empty(4, 0)
                add(responseStatusLabel, BorderLayout.WEST)
                val rightTools = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
                rightTools.add(aiDiagnoseButton)
                add(rightTools, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)
            add(responseTabs, BorderLayout.CENTER)
        }

        // F13: 把 splitter 提到字段层，配合「Layout」按钮实时切换上下 / 左右 / 全屏请求 / 全屏响应。
        mainSplitter = JBSplitter(true, 0.45f).apply {
            firstComponent = requestPanel
            secondComponent = responsePanel
        }
        cachedRequestPanel = requestPanel
        cachedResponsePanel = responsePanel
        add(mainSplitter, BorderLayout.CENTER)
    }

    /** v1.3.3 F13 - 主体上下 / 左右 / 全屏切换器。*/
    private lateinit var mainSplitter: JBSplitter
    private var cachedRequestPanel: JPanel? = null
    private var cachedResponsePanel: JPanel? = null
    private var currentLayout: LayoutMode = LayoutMode.VERTICAL

    /** v1.3.3 F13 - 切换布局；按钮挂在 toolbar 上由 [setupTopBar] 注入。*/
    private fun applyLayout(mode: LayoutMode) {
        currentLayout = mode
        val request = cachedRequestPanel ?: return
        val response = cachedResponsePanel ?: return
        when (mode) {
            LayoutMode.VERTICAL -> {
                mainSplitter.orientation = true
                mainSplitter.proportion = 0.45f
                mainSplitter.firstComponent = request
                mainSplitter.secondComponent = response
            }
            LayoutMode.HORIZONTAL -> {
                mainSplitter.orientation = false
                mainSplitter.proportion = 0.50f
                mainSplitter.firstComponent = request
                mainSplitter.secondComponent = response
            }
            LayoutMode.REQUEST_ONLY -> {
                mainSplitter.orientation = true
                mainSplitter.proportion = 1.0f
                mainSplitter.firstComponent = request
                mainSplitter.secondComponent = null
            }
            LayoutMode.RESPONSE_ONLY -> {
                mainSplitter.orientation = true
                mainSplitter.proportion = 0.0f
                mainSplitter.firstComponent = null
                mainSplitter.secondComponent = response
            }
        }
        mainSplitter.revalidate()
        mainSplitter.repaint()
    }

    enum class LayoutMode { VERTICAL, HORIZONTAL, REQUEST_ONLY, RESPONSE_ONLY }

    /** v1.3.3 F13 - 切到下一种布局（按 toolbar 按钮触发）。*/
    fun cycleLayout() {
        val all = LayoutMode.entries
        val nextIdx = (all.indexOf(currentLayout) + 1) % all.size
        applyLayout(all[nextIdx])
    }

    /**
     * v1.3.3 P2-11 - 应用 AWS V4 / OAuth1 签名（Pro）。
     *
     * 用户在 Headers tab 中点击不会自动签名；此入口走 toolbar action（先 Pro gate）→
     * 弹小对话框输入 accessKey/secret（PasswordSafe 不入库；本次输入仅内存） → 计算签名 →
     * 直接 setParams 到 [headersPanel]。
     */
    private fun applySignaturePopup() {
        val gateOk = io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(
            project,
            io.github.movebrickschi.restfulall.service.ProFeature.SIGN_PLUGIN,
        )
        if (!gateOk) return
        val choices = arrayOf("AWS Signature V4", "OAuth 1.0a")
        val pick = javax.swing.JOptionPane.showOptionDialog(
            this, "Choose signature type", "Apply Signature",
            javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE,
            null, choices, choices[0],
        )
        if (pick < 0) return
        if (pick == 0) applyAwsV4() else applyOAuth1()
    }

    private fun applyAwsV4() {
        val ak = javax.swing.JOptionPane.showInputDialog(this, "AWS Access Key ID")?.trim().orEmpty()
        if (ak.isEmpty()) return
        val sk = promptPassword("AWS Secret Access Key") ?: return
        val region = javax.swing.JOptionPane.showInputDialog(this, "AWS Region (e.g. us-east-1)", "us-east-1")?.trim().orEmpty()
        if (region.isEmpty()) return
        val service = javax.swing.JOptionPane.showInputDialog(this, "AWS Service (e.g. s3, execute-api)", "execute-api")?.trim().orEmpty()
        if (service.isEmpty()) return

        try {
            val signed = io.github.movebrickschi.restfulall.service.SignaturePlugin.awsV4(
                io.github.movebrickschi.restfulall.service.SignaturePlugin.AwsV4Input(
                    accessKey = ak,
                    secretKey = sk,
                    region = region,
                    service = service,
                    method = methodCombo.selectedItem?.toString() ?: "GET",
                    url = urlField.text.trim(),
                    headers = headersPanel.getParams().toMap(),
                    body = bodyTextArea.text.toByteArray(Charsets.UTF_8),
                ),
            )
            val merged = headersPanel.getParams().toMutableList()
            mergeOrReplace(merged, "Authorization", signed.authorization)
            mergeOrReplace(merged, "x-amz-date", signed.amzDate)
            mergeOrReplace(merged, "x-amz-content-sha256", signed.contentSha256)
            headersPanel.setParams(merged)
            javax.swing.JOptionPane.showMessageDialog(this, "AWS V4 signature applied to Headers tab.")
        } catch (e: Exception) {
            javax.swing.JOptionPane.showMessageDialog(this, e.message ?: "Sign failed", "AWS V4 failed",
                javax.swing.JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun applyOAuth1() {
        val ck = javax.swing.JOptionPane.showInputDialog(this, "OAuth Consumer Key")?.trim().orEmpty()
        if (ck.isEmpty()) return
        val cs = promptPassword("OAuth Consumer Secret") ?: return
        val tk = javax.swing.JOptionPane.showInputDialog(this, "OAuth Token (optional)", "")?.trim().orEmpty()
        val ts = promptPassword("OAuth Token Secret (optional, leave blank if none)") ?: ""
        try {
            val header = io.github.movebrickschi.restfulall.service.SignaturePlugin.oauth1(
                io.github.movebrickschi.restfulall.service.SignaturePlugin.OAuth1Input(
                    consumerKey = ck,
                    consumerSecret = cs,
                    token = tk,
                    tokenSecret = ts,
                    method = methodCombo.selectedItem?.toString() ?: "GET",
                    url = urlField.text.trim(),
                ),
            )
            val merged = headersPanel.getParams().toMutableList()
            mergeOrReplace(merged, "Authorization", header)
            headersPanel.setParams(merged)
            javax.swing.JOptionPane.showMessageDialog(this, "OAuth 1.0a signature applied to Headers tab.")
        } catch (e: Exception) {
            javax.swing.JOptionPane.showMessageDialog(this, e.message ?: "Sign failed", "OAuth1 failed",
                javax.swing.JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun promptPassword(label: String): String? {
        val pw = javax.swing.JPasswordField()
        val panel = javax.swing.JPanel(BorderLayout(4, 4))
        panel.add(javax.swing.JLabel(label), BorderLayout.NORTH)
        panel.add(pw, BorderLayout.CENTER)
        val ok = javax.swing.JOptionPane.showConfirmDialog(
            this, panel, label, javax.swing.JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok != javax.swing.JOptionPane.OK_OPTION) return null
        return String(pw.password)
    }

    private fun mergeOrReplace(list: MutableList<Pair<String, String>>, name: String, value: String) {
        val idx = list.indexOfFirst { it.first.equals(name, ignoreCase = true) }
        if (idx >= 0) list[idx] = name to value else list.add(name to value)
    }

    /** v1.3.3 P2-8 - 打开接口压测对话框（Pro）。 */
    private fun openLoadTestDialog() {
        val gateOk = io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(
            project,
            io.github.movebrickschi.restfulall.service.ProFeature.PRESS_TEST,
        )
        if (!gateOk) return
        if (urlField.text.isBlank()) {
            javax.swing.JOptionPane.showMessageDialog(this, "Enter a URL first", "Load test",
                javax.swing.JOptionPane.WARNING_MESSAGE)
            return
        }
        val dialog = LoadTestDialog(
            project = project,
            initialUrl = urlField.text.trim(),
            initialMethod = methodCombo.selectedItem?.toString() ?: "GET",
            initialHeaders = headersPanel.getParams(),
            initialBody = bodyTextArea.text,
        )
        dialog.show()
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
            add(aiFillButton)
            add(aiTestCaseButton)
            add(aiConfigButton)
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

        updateDescription(routeInfo.description)

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
            Framework.OPENAPI -> OpenApiParamExtractor.extract(project, routeInfo)
        }
    }

    /**
     * F10: 刷新 URL 行下方的接口注释展示区。
     *
     * - `null` 或空白 → 隐藏整个 [descriptionPanel]
     * - 非空 → 显示并触发父容器重排
     *
     * 调用方应在主线程触发；当前所有触发点（[loadRoute] / [loadHistoryEntry] / cURL 导入）
     * 都在 EDT 上。
     */
    private fun updateDescription(text: String?) {
        val cleaned = text?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            if (descriptionPanel.isVisible) {
                descriptionPanel.isVisible = false
                descriptionPanel.revalidate()
                descriptionPanel.repaint()
            }
            descriptionArea.text = ""
            return
        }
        descriptionArea.text = cleaned
        descriptionArea.caretPosition = 0
        if (!descriptionPanel.isVisible) {
            descriptionPanel.isVisible = true
        }
        descriptionPanel.revalidate()
        descriptionPanel.repaint()
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
        updateDescription(null)
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

    /**
     * v1.3.1 F3 - 把 Collection 内保存的请求 spec 加载到调试面板。
     *
     * 与 [loadHistoryEntry] 的差异：
     * - 来源是 [CollectionItem.spec]（XML 持久化的 RequestSpecData，含 enabled 标记）
     * - 不带响应数据，因此清空响应区
     * - 注释展示区清空（Collection 不携带源码注释）
     */
    fun loadCollectionItem(item: CollectionItem) {
        val spec = item.spec
        methodCombo.selectedItem = spec.method
        urlField.text = spec.url
        updateDescription(null)

        pathParamPanel.clear()
        for (p in spec.pathParams.filter { it.name.isNotBlank() }) {
            pathParamPanel.addParam(p.name, p.value)
        }
        queryParamPanel.setParams(spec.queryParams.map { it.name to it.value })
        headersPanel.setParams(spec.headers.map { it.name to it.value })
        cookiesPanel.setParams(spec.cookies.map { it.name to it.value })

        selectBodyType(spec.bodyType)
        bodyTextArea.text = spec.bodyContent
        if (spec.formParams.isNotEmpty()) {
            val pairs = spec.formParams.map { it.name to it.value }
            when (spec.bodyType) {
                "form-data" -> formDataPanel.setParams(pairs)
                "x-www-form-urlencoded" -> urlEncodedPanel.setParams(pairs)
            }
        }

        responseBodyArea.text = ""
        responseStatusLabel.text = ""

        when {
            spec.bodyType == "form-data" || spec.bodyType == "x-www-form-urlencoded" ->
                requestTabs.selectedIndex = 1
            spec.bodyContent.isNotBlank() -> requestTabs.selectedIndex = 1
            spec.pathParams.any { it.name.isNotBlank() } -> requestTabs.selectedIndex = 2
            else -> requestTabs.selectedIndex = 0
        }
    }

    /**
     * v1.3.1 P0-1 - 点击 AI Fill 按钮后的处理。
     *
     * 流程：
     * 1. 走 [io.github.movebrickschi.restfulall.service.ProFeatureGate]：Pro 用户直接通过；
     *    Free 用户检查 [AiUsageQuota]，超限弹升级气泡 + 走 require Pro。
     * 2. [AiService.isConfigured] = false → 提示走 AI Settings 弹窗先配置
     * 3. 在 pooled thread 上调 LLM；UI 上把按钮短暂置灰
     * 4. 成功回写到 [bodyTextArea]；失败弹错误（区分 Quota / Network / Http / Malformed）
     */
    private fun onAiFillClicked() {
        val ai = AiService.getInstance(project)
        ai.ensureKeyLoaded()
        if (!ai.getConfig().isConfigured) {
            javax.swing.JOptionPane.showMessageDialog(
                this,
                "Please configure AI baseUrl / model / apiKey via the Settings gear first.",
                "AI not configured",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
            showAiConfigDialog()
            return
        }

        val isPro = io.github.movebrickschi.restfulall.service.ProFeatureGate.isPro(project)
        if (!isPro) {
            val remaining = AiUsageQuota.getInstance().remaining()
            if (remaining <= 0) {
                io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(
                    project,
                    io.github.movebrickschi.restfulall.service.ProFeature.AI_PARAM_FILL,
                )
                return
            }
        }

        val description = updateDescriptionText() ?: ""
        val urlForHint = urlField.text
        val schemaHint = buildSchemaHint()

        aiFillButton.isEnabled = false
        val original = aiFillButton.toolTipText
        aiFillButton.toolTipText = "Calling LLM..."

        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                ai.fillParameters(
                    interfaceDescription = "$description\nURL hint: $urlForHint",
                    schemaHint = schemaHint,
                    existingValues = emptyMap(),
                )
            } catch (e: AiException.NotConfigured) {
                javax.swing.SwingUtilities.invokeLater {
                    javax.swing.JOptionPane.showMessageDialog(this, e.message, "AI not configured",
                        javax.swing.JOptionPane.WARNING_MESSAGE)
                }
                null
            } catch (e: AiException.QuotaExceeded) {
                javax.swing.SwingUtilities.invokeLater {
                    io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(
                        project,
                        io.github.movebrickschi.restfulall.service.ProFeature.AI_PARAM_FILL,
                    )
                }
                null
            } catch (e: AiException) {
                javax.swing.SwingUtilities.invokeLater {
                    javax.swing.JOptionPane.showMessageDialog(this, e.message, "AI call failed",
                        javax.swing.JOptionPane.ERROR_MESSAGE)
                }
                null
            } catch (e: Exception) {
                javax.swing.SwingUtilities.invokeLater {
                    javax.swing.JOptionPane.showMessageDialog(this, e.message ?: "I/O error", "AI call failed",
                        javax.swing.JOptionPane.ERROR_MESSAGE)
                }
                null
            }

            javax.swing.SwingUtilities.invokeLater {
                aiFillButton.isEnabled = true
                aiFillButton.toolTipText = original
                if (text != null) {
                    selectBodyType("json")
                    bodyTextArea.text = text
                    bodyTextArea.caretPosition = 0
                }
            }
        }
    }

    /**
     * v1.3.2 P0-2 - AI 接口诊断。需要 [lastResponseSnapshot] 已被填充；否则提示先发请求。
     *
     * 弹一个非模态 dialog 展示分析结果（用户可继续在调试面板操作）。
     */
    private fun onAiDiagnoseClicked() {
        val snapshot = lastResponseSnapshot
        if (snapshot == null) {
            javax.swing.JOptionPane.showMessageDialog(
                this,
                "Send a request first; diagnostics needs the last response.",
                "Nothing to diagnose",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
            return
        }
        if (!ensureAiReadyOrUpsell(io.github.movebrickschi.restfulall.service.ProFeature.AI_DIAGNOSE)) return

        val requestSummary = buildString {
            appendLine(methodCombo.selectedItem.toString() + " " + urlField.text)
            val headers = headersPanel.getParams()
            if (headers.isNotEmpty()) {
                appendLine("Headers:")
                headers.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            val body = bodyTextArea.text
            if (body.isNotBlank()) appendLine("Body:\n$body")
        }
        runAiAsync("Diagnosing...") {
            io.github.movebrickschi.restfulall.service.AiService.getInstance(project).diagnose(
                requestSummary,
                snapshot.statusCode,
                snapshot.body,
            )
        }
    }

    /**
     * v1.3.2 P0-3 - AI 生成测试用例。
     *
     * 取当前 method+url+body 作为正向样例，加 description 作为说明，让 LLM 产 5~10 用例 JSON 数组。
     * 输出弹窗展示，便于用户复制 / 后续接入 P2-9 用例编排。
     */
    private fun onAiGenerateCasesClicked() {
        if (!ensureAiReadyOrUpsell(io.github.movebrickschi.restfulall.service.ProFeature.AI_TEST_CASE)) return
        val sample = buildString {
            appendLine(methodCombo.selectedItem.toString() + " " + urlField.text)
            val body = bodyTextArea.text
            if (body.isNotBlank()) appendLine("Body:\n$body")
        }
        val description = updateDescriptionText().orEmpty()
        runAiAsync("Generating test cases...") {
            io.github.movebrickschi.restfulall.service.AiService.getInstance(project)
                .generateTestCases(description, sample)
        }
    }

    /**
     * 通用 AI 异步执行 + 结果弹窗。
     * - Pro/quota 校验已在 [ensureAiReadyOrUpsell] 完成
     * - 失败弹错误，成功弹一个可滚动只读 dialog 展示 markdown / JSON
     */
    private fun runAiAsync(progressTooltip: String, call: () -> String) {
        val backup = aiDiagnoseButton.toolTipText
        aiDiagnoseButton.toolTipText = progressTooltip
        aiDiagnoseButton.isEnabled = false
        aiTestCaseButton.isEnabled = false
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val (ok, text) = try {
                true to call()
            } catch (e: io.github.movebrickschi.restfulall.service.AiException.QuotaExceeded) {
                javax.swing.SwingUtilities.invokeLater {
                    io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(
                        project,
                        io.github.movebrickschi.restfulall.service.ProFeature.AI_DIAGNOSE,
                    )
                }
                false to (e.message ?: "Quota exceeded")
            } catch (e: io.github.movebrickschi.restfulall.service.AiException) {
                false to (e.message ?: "AI failure")
            } catch (e: Exception) {
                false to (e.message ?: "Unknown failure")
            }
            javax.swing.SwingUtilities.invokeLater {
                aiDiagnoseButton.toolTipText = backup
                aiDiagnoseButton.isEnabled = true
                aiTestCaseButton.isEnabled = true
                if (!ok) {
                    javax.swing.JOptionPane.showMessageDialog(this, text, "AI failed",
                        javax.swing.JOptionPane.ERROR_MESSAGE)
                    return@invokeLater
                }
                showAiResultDialog(text)
            }
        }
    }

    private fun showAiResultDialog(text: String) {
        val area = javax.swing.JTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8)
        }
        val scroll = JBScrollPane(area).apply {
            preferredSize = Dimension(640, 400)
        }
        javax.swing.JOptionPane.showMessageDialog(
            this, scroll, "AI result", javax.swing.JOptionPane.INFORMATION_MESSAGE,
        )
    }

    /**
     * 共用前置校验：AI 是否已配置 + Pro/Quota gate。
     * @return true 表示通过可继续；false 表示已弹相应窗口，调用方应 early-return。
     */
    private fun ensureAiReadyOrUpsell(feature: io.github.movebrickschi.restfulall.service.ProFeature): Boolean {
        val ai = AiService.getInstance(project)
        ai.ensureKeyLoaded()
        if (!ai.getConfig().isConfigured) {
            javax.swing.JOptionPane.showMessageDialog(
                this,
                "Please configure AI baseUrl / model / apiKey via the AI Settings gear first.",
                "AI not configured",
                javax.swing.JOptionPane.INFORMATION_MESSAGE,
            )
            showAiConfigDialog()
            return false
        }
        val isPro = io.github.movebrickschi.restfulall.service.ProFeatureGate.isPro(project)
        if (!isPro && AiUsageQuota.getInstance().remaining() <= 0) {
            io.github.movebrickschi.restfulall.service.ProFeatureGate.requirePro(project, feature)
            return false
        }
        return true
    }

    /**
     * v1.3.1 P0-1 - AI 配置弹窗（baseUrl / model / apiKey）。
     *
     * 3 次 showInputDialog 拼成一个对话流；apiKey 走 PasswordSafe，不落盘。
     * 后续可重构为正式 Settings 页面，但本版选择最快上线。
     */
    private fun showAiConfigDialog() {
        val ai = AiService.getInstance(project)
        val current = ai.getConfig()
        ai.ensureKeyLoaded()

        val newBase = javax.swing.JOptionPane.showInputDialog(
            this, "AI base URL (OpenAI-compatible)", current.baseUrl,
        )?.trim() ?: return
        val newModel = javax.swing.JOptionPane.showInputDialog(
            this, "AI model id", current.model,
        )?.trim() ?: return

        val keyPanel = javax.swing.JPanel(java.awt.BorderLayout())
        val keyField = javax.swing.JPasswordField()
        if (current.apiKeyInMemory.isNotBlank()) keyField.text = current.apiKeyInMemory
        keyPanel.add(javax.swing.JLabel("Bearer API key (stored in PasswordSafe)"), java.awt.BorderLayout.NORTH)
        keyPanel.add(keyField, java.awt.BorderLayout.CENTER)
        val choice = javax.swing.JOptionPane.showConfirmDialog(
            this, keyPanel, "AI API Key", javax.swing.JOptionPane.OK_CANCEL_OPTION,
        )
        if (choice != javax.swing.JOptionPane.OK_OPTION) return
        val newKey = String(keyField.password)

        ai.updateConfig(current.copy(baseUrl = newBase, model = newModel))
        ai.updateApiKey(newKey)

        javax.swing.JOptionPane.showMessageDialog(this, "AI settings saved.")
    }

    /** 抽取当前 Description 文本（F10 注释展示区），AI prompt 使用。*/
    private fun updateDescriptionText(): String? =
        descriptionArea.text?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 从当前 UI 已有字段拼一个最小 schema hint：
     * - body 文本（原样）
     * - query keys
     * - path keys
     * 用 newline 串起来交给 LLM
     */
    private fun buildSchemaHint(): String = buildString {
        val q = queryParamPanel.getParams()
        if (q.isNotEmpty()) {
            appendLine("Query params: " + q.joinToString(", ") { it.first })
        }
        val p = pathParamPanel.getParams()
        if (p.isNotEmpty()) {
            appendLine("Path params: " + p.joinToString(", ") { it.first })
        }
        val existing = bodyTextArea.text.trim()
        if (existing.isNotEmpty()) {
            appendLine("Current body (partial):")
            appendLine(existing.take(1500))
        }
    }

    /**
     * v1.3.1 F3 - 获取调试面板当前请求的快照，用于 "加入 Collection"。
     *
     * 仅拷贝 UI 当前可见的状态，不应用 path-template 替换；与发送时 [sendRequest]
     * 走的 mergeParams 流程区分（不在保存时把全局参数硬合并进去，避免污染 collection）。
     */
    fun snapshotCurrentSpec(): io.github.movebrickschi.restfulall.model.RequestSpecData {
        val method = methodCombo.selectedItem as? String ?: "GET"
        val url = urlField.text.trim()
        val bodyType = getSelectedBodyType()
        val body = when (bodyType) {
            "json", "xml", "raw" -> bodyTextArea.text
            else -> ""
        }
        return io.github.movebrickschi.restfulall.model.RequestSpecData(
            method = method,
            url = url,
            queryParams = queryParamPanel.getParams()
                .map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            headers = headersPanel.getParams()
                .map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            cookies = cookiesPanel.getParams()
                .map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            pathParams = pathParamPanel.getParams()
                .map { ParamEntry(true, it.first, it.second) }.toMutableList(),
            bodyType = bodyType,
            bodyContent = body,
            formParams = getFormParamEntries(),
        )
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

        val requestSpec = RequestSpec(
            method = method,
            url = finalUrl,
            headers = mergedHeaders,
            cookies = mergedCookies,
            bodyType = bodyType,
            bodyContent = bodyContent,
            formParams = getFormParamEntries(),
        )
        val multipartParams = if (bodyType == "form-data") {
            formDataPanel.getParams().map { (name, value, type) ->
                MultipartFormParam(name = name, value = value, type = type)
            }
        } else {
            emptyList()
        }
        val requestExecutor = RequestExecutor.getInstance(project)

        // 命名 daemon 线程：方便 jstack / Async Profiler 中识别 SSE/NDJSON 流读取线程，
        // 且 isDaemon=true 避免 IDE 退出时还在等流读完。
        val thread = Thread({
            var reconnectCount = 0
            var lastEventId: String? = null

            loop@ while (true) {
                try {
                    val startTime = System.currentTimeMillis()
                    val response = requestExecutor.send(
                        requestSpec,
                        extraHeaders = lastEventId?.let { listOf("Last-Event-ID" to it) }.orEmpty(),
                        multipartParams = multipartParams,
                    )

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
                                val retryMs = result.retryMs.coerceIn(SSE_MIN_RETRY_MS, SSE_MAX_RETRY_MS)
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
                            val responseBody = requestExecutor.readResponseBody(response)
                            val bodyString = if (responseBody.truncated) {
                                responseBody.text + "\n\n[Response truncated: exceeded " +
                                    "${RequestExecutor.MAX_RESPONSE_BYTES / 1024 / 1024} MB cap]"
                            } else {
                                responseBody.text
                            }
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
        }, "Restful-SSE-${Integer.toHexString(finalUrl.hashCode())}")
        thread.isDaemon = true
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

        webSocket?.let { stale ->
            try {
                stale.sendClose(WebSocket.NORMAL_CLOSURE, "switching url")
            } catch (_: Exception) {
                // best-effort; if the stale ws is already closing this is fine
            }
            webSocket = null
            isWsConnected = false
        }

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
                if (ws !== webSocket) {
                    ws.request(1)
                    return null
                }
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
                if (ws !== webSocket) {
                    return CompletableFuture.completedFuture(null)
                }
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
                if (ws !== webSocket) return
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

        // F6: 把响应交给 ResponseViewPanel，按 content-type 自动选 Pretty / Tree / Image / Download 等。
        val contentType = headers.firstValue("content-type").orElse("")
        responseViewPanel.setResponse(body = body, contentType = contentType, bytes = null)

        responseHeadersModel.setData(headers.map().entries.map { it.key to it.value.joinToString(", ") })

        val setCookies = headers.allValues("set-cookie")
        val cookieEntries = setCookies.map { cookie ->
            val parts = cookie.split(";")[0].split("=", limit = 2)
            parts[0].trim() to (parts.getOrNull(1)?.trim() ?: "")
        }
        responseCookiesModel.setData(cookieEntries)

        runAssertionsWithSnapshot(statusCode, body, headers.map(), elapsed)

        responseTabs.selectedIndex = 0
    }

    /**
     * F7: 用最近一次响应跑一遍当前编辑中的断言，把结果展示到 [assertionPanel]。
     * 也供 [AssertionTablePanel.onRerunRequested] 回调复用。
     */
    private fun runAssertionsWithSnapshot(
        statusCode: Int,
        body: String,
        headers: Map<String, List<String>>,
        elapsed: Long,
    ) {
        lastResponseSnapshot = ResponseSnapshot(statusCode, body, headers, elapsed)
        val assertions = assertionPanel.getAssertions()
        if (assertions.isEmpty()) {
            assertionPanel.showResults(emptyList())
            return
        }
        val results = AssertionEngine.evaluateAll(assertions, body, statusCode, elapsed, headers)
        assertionPanel.showResults(results)
    }

    private data class ResponseSnapshot(
        val statusCode: Int,
        val body: String,
        val headers: Map<String, List<String>>,
        val elapsed: Long,
    )

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

        // F6: 历史回放也走 ResponseViewPanel；content-type 走 header 字段，缺失则默认 json
        val ct = entry.responseHeaders.firstOrNull { it.name.equals("content-type", ignoreCase = true) }
            ?.value.orEmpty().ifBlank { "application/json" }
        responseViewPanel.setResponse(body = body, contentType = ct, bytes = null)

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

    // ── F2: cURL import / export ──────────────────────────────────────────────

    private fun importFromCurl() {
        val input = JOptionPane.showInputDialog(this, "Paste cURL command:", "Import cURL", JOptionPane.PLAIN_MESSAGE)
        if (input.isNullOrBlank()) return
        try {
            val spec = CurlConverter.parse(input)
            methodCombo.selectedItem = spec.method
            urlField.text = spec.url
            updateDescription(null)
            if (spec.bodyContent.isNotBlank()) {
                bodyTextArea.text = spec.bodyContent
                when (spec.bodyType) {
                    "json" -> bodyTypeJson.isSelected = true
                    "xml" -> bodyTypeXml.isSelected = true
                    "raw" -> bodyTypeRaw.isSelected = true
                }
            }
            headersPanel.setParams(spec.headers)
            cookiesPanel.setParams(spec.cookies)
        } catch (e: CurlConverter.CurlParseException) {
            JOptionPane.showMessageDialog(this, "cURL parse error: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun exportAsCurl() {
        val spec = buildCurrentRequestSpec()
        val curl = CurlConverter.export(spec)
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(java.awt.datatransfer.StringSelection(curl), null)
        responseStatusLabel.text = "cURL copied to clipboard"
    }

    private fun buildCurrentRequestSpec(): RequestSpec {
        val localQueryParams = queryParamPanel.getParams()
        val localHeaders = headersPanel.getParams()
        val localCookies = cookiesPanel.getParams()
        return RequestSpec(
            method = methodCombo.selectedItem as String,
            url = urlField.text.trim(),
            queryParams = localQueryParams,
            headers = localHeaders,
            cookies = localCookies,
            bodyType = getSelectedBodyType(),
            bodyContent = getBodyContent(),
        )
    }

    companion object {
        private val PATH_PARAM_REGEX = Regex("\\{(\\w+)}|:(\\w+)")
        private val METHODS_WITH_BODY = listOf("POST", "PUT", "PATCH")
        private val SUCCESS_COLOR = JBColor(Color(0x00, 0x80, 0x00), Color(0x98, 0xC3, 0x79))
        private val WARN_COLOR = JBColor(Color(0xCC, 0x80, 0x00), Color(0xE5, 0xC0, 0x7B))
        private val ERROR_COLOR = JBColor(Color(0xCC, 0x00, 0x00), Color(0xE0, 0x6C, 0x75))
        private val WS_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        private const val SSE_MIN_RETRY_MS: Long = 1_000L
        private const val SSE_MAX_RETRY_MS: Long = 60_000L

        private const val CARD_NONE = "none"
        private const val CARD_FORM_DATA = "form-data"
        private const val CARD_URL_ENCODED = "x-www-form-urlencoded"
        private const val CARD_TEXT = "text"
        private const val SSE_MAX_RECONNECTS = 5
    }
}
