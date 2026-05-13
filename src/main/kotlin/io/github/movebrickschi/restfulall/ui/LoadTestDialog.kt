package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.service.LoadTestService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * v1.3.3 P2-8 - 接口压测对话框（Pro）。
 *
 * 流程：
 * 1. 输入并发数 / 持续秒数 / RPS 上限
 * 2. 点击 Start → 调 [LoadTestService.run]
 * 3. 进度区实时刷新 elapsed / QPS / 成功 / 错误
 * 4. 完成后展示完整报告 + 导出 CSV 按钮
 *
 * 取消逻辑：Start 后按钮变 "Stop"；点击后置 [AtomicBoolean] = true，service 会即时退出。
 */
class LoadTestDialog(
    project: Project,
    private val initialUrl: String,
    private val initialMethod: String,
    private val initialHeaders: List<Pair<String, String>>,
    private val initialBody: String,
) : DialogWrapper(project, true) {

    private val urlField = JBTextField(initialUrl)
    private val concurrencyField = JBTextField("10")
    private val durationField = JBTextField("10")
    private val rpsField = JBTextField("0")
    private val statusArea = JTextArea(8, 40).apply {
        isEditable = false
        border = JBUI.Borders.empty(4)
    }
    private val resultArea = JTextArea(16, 60).apply {
        isEditable = false
        border = JBUI.Borders.empty(4)
    }
    private val startButton = JButton("Start")
    private val exportButton = JButton("Export CSV").apply { isEnabled = false }
    private val cancelToken = AtomicBoolean(false)
    private val projectRef = project
    private var lastReport: LoadTestService.LoadTestReport? = null

    init {
        title = "Load Test (Pro)"
        setOKButtonText("Close")
        setCancelButtonText("Hide")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(720, 580)
        }

        val form = JPanel(GridLayout(0, 2, 6, 4))
        form.add(JBLabel("URL")); form.add(urlField)
        form.add(JBLabel("Concurrency (1-${LoadTestService.MAX_CONCURRENCY})"))
        form.add(concurrencyField)
        form.add(JBLabel("Duration (sec, 1-${LoadTestService.MAX_DURATION_SEC})"))
        form.add(durationField)
        form.add(JBLabel("RPS limit (0 = no cap)"))
        form.add(rpsField)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT))
        buttons.add(startButton)
        buttons.add(exportButton)
        startButton.addActionListener { onStartOrStop() }
        exportButton.addActionListener { exportCsv() }

        val top = JPanel(BorderLayout(0, 8))
        top.add(form, BorderLayout.NORTH)
        top.add(buttons, BorderLayout.CENTER)

        panel.add(top, BorderLayout.NORTH)
        panel.add(JBScrollPane(statusArea), BorderLayout.CENTER)
        panel.add(JBScrollPane(resultArea), BorderLayout.SOUTH)
        return panel
    }

    private fun onStartOrStop() {
        if (startButton.text == "Stop") {
            cancelToken.set(true)
            startButton.isEnabled = false
            return
        }
        val concurrency = concurrencyField.text.toIntOrNull() ?: 10
        val duration = durationField.text.toLongOrNull() ?: 10
        val rps = rpsField.text.toIntOrNull() ?: 0
        if (concurrency !in 1..LoadTestService.MAX_CONCURRENCY) {
            Messages.showWarningDialog(contentPanel, "Invalid concurrency", "Validation"); return
        }
        if (duration !in 1..LoadTestService.MAX_DURATION_SEC) {
            Messages.showWarningDialog(contentPanel, "Invalid duration", "Validation"); return
        }
        val url = urlField.text.trim()
        if (url.isBlank()) {
            Messages.showWarningDialog(contentPanel, "URL is empty", "Validation"); return
        }

        cancelToken.set(false)
        startButton.text = "Stop"
        exportButton.isEnabled = false
        statusArea.text = ""
        resultArea.text = "(running...)"
        lastReport = null

        val config = LoadTestService.LoadTestConfig(
            url = url,
            method = initialMethod,
            headers = initialHeaders,
            body = initialBody,
            concurrency = concurrency,
            durationSec = duration,
            rpsLimit = rps,
        )

        Thread({
            try {
                val report = LoadTestService.getInstance(projectRef).run(config, cancelToken) { p ->
                    val line = String.format(
                        "[%ds] total=%d success=%d error=%d  curQPS=%.1f%n",
                        p.elapsedSec, p.totalRequests, p.successCount, p.errorCount, p.currentQps,
                    )
                    SwingUtilities.invokeLater { statusArea.append(line) }
                }
                SwingUtilities.invokeLater {
                    startButton.text = "Start"
                    startButton.isEnabled = true
                    exportButton.isEnabled = true
                    lastReport = report
                    resultArea.text = formatReport(report)
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater {
                    startButton.text = "Start"
                    startButton.isEnabled = true
                    resultArea.text = "FAILED: ${e.message}"
                }
            }
        }, "RestfulAll-LoadTest-Dialog").apply { isDaemon = true }.start()
    }

    private fun formatReport(r: LoadTestService.LoadTestReport): String = buildString {
        appendLine("=== Result ===")
        appendLine("total=${r.totalRequests}  success=${r.successCount}  error=${r.errorCount}")
        appendLine(String.format("elapsed=%dms  QPS=%.2f  errorRate=%.2f%%",
            r.elapsedMs, r.qps, r.errorRate * 100))
        appendLine("latency  avg=${r.averageLatencyMs}ms  P50=${r.p50LatencyMs}ms  P90=${r.p90LatencyMs}ms  P95=${r.p95LatencyMs}ms  P99=${r.p99LatencyMs}ms")
        appendLine()
        appendLine("status distribution:")
        r.statusDistribution.toSortedMap().forEach { (k, v) -> appendLine("  $k -> $v") }
        if (r.errorMessages.isNotEmpty()) {
            appendLine()
            appendLine("first error samples:")
            r.errorMessages.forEach { appendLine("  - $it") }
        }
    }

    private fun exportCsv() {
        val report = lastReport ?: return
        val descriptor = FileSaverDescriptor("Export load test report", "CSV format")
        val saver = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, projectRef)
        val target = saver.save("load-test-report.csv") ?: return
        try {
            val sb = StringBuilder()
            sb.appendLine("metric,value")
            sb.appendLine("totalRequests,${report.totalRequests}")
            sb.appendLine("successCount,${report.successCount}")
            sb.appendLine("errorCount,${report.errorCount}")
            sb.appendLine("elapsedMs,${report.elapsedMs}")
            sb.appendLine("qps,${report.qps}")
            sb.appendLine("errorRate,${report.errorRate}")
            sb.appendLine("avgLatencyMs,${report.averageLatencyMs}")
            sb.appendLine("p50LatencyMs,${report.p50LatencyMs}")
            sb.appendLine("p90LatencyMs,${report.p90LatencyMs}")
            sb.appendLine("p95LatencyMs,${report.p95LatencyMs}")
            sb.appendLine("p99LatencyMs,${report.p99LatencyMs}")
            sb.appendLine()
            sb.appendLine("statusCode,count")
            for ((k, v) in report.statusDistribution) sb.appendLine("$k,$v")
            target.file.writeText(sb.toString(), Charsets.UTF_8)
            Messages.showInfoMessage(contentPanel, "Saved to ${target.file.absolutePath}", "Export complete")
        } catch (e: Exception) {
            Messages.showErrorDialog(contentPanel, e.message ?: "I/O error", "Export failed")
        }
    }
}
