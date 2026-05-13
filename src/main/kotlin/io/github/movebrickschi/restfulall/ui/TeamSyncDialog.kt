package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.movebrickschi.restfulall.service.SyncException
import io.github.movebrickschi.restfulall.service.WorkspaceSyncService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * v1.3.3 P1-4 / P1-5 - Team Sync 配置 + 操作面板（Team）。
 *
 * - 上半部：配置（baseUrl / workspaceId / accountToken / 同步范围）→ Save
 * - 中部：3 个操作按钮（Push / Pull / Share）；右侧日志区显示结果
 * - 调用前端 [WorkspaceSyncService]，错误分类弹窗（NotConfigured / RevisionConflict / HttpFailure）
 */
class TeamSyncDialog(private val project: Project) : DialogWrapper(project, true) {

    private val baseUrlField = JBTextField()
    private val workspaceField = JBTextField()
    private val accountIdField = JBTextField()
    private val tokenField = JPasswordField()
    private val syncCollChk = JCheckBox("Collections")
    private val syncEnvChk = JCheckBox("Environments")
    private val syncHistChk = JCheckBox("History")
    private val logArea = JTextArea(12, 60).apply { isEditable = false; border = JBUI.Borders.empty(4) }

    init {
        title = "Team Sync (Team)"
        setOKButtonText("Close")
        setCancelButtonText("Hide")
        init()
        loadConfig()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(8, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(720, 520)
        }

        val form = JPanel(GridLayout(0, 2, 6, 4))
        form.add(JBLabel("Sync server base URL"))
        form.add(baseUrlField)
        form.add(JBLabel("Workspace ID"))
        form.add(workspaceField)
        form.add(JBLabel("Account ID (your team email or user id)"))
        form.add(accountIdField)
        form.add(JBLabel("Bearer token (stored in PasswordSafe)"))
        form.add(tokenField)
        form.add(JBLabel("Sync scope"))
        val scopePanel = JPanel().apply {
            add(syncCollChk); add(syncEnvChk); add(syncHistChk)
        }
        form.add(scopePanel)

        val saveBtn = JButton("Save config")
        saveBtn.addActionListener { saveConfig() }
        val pushBtn = JButton("Push to cloud")
        pushBtn.addActionListener { runAsync("push") { it.push().toString() } }
        val pullBtn = JButton("Pull from cloud")
        pullBtn.addActionListener { runAsync("pull") { it.pull().toString() } }
        val shareBtn = JButton("Share collection...")
        shareBtn.addActionListener { promptShare() }

        val buttons = JPanel()
        buttons.add(saveBtn); buttons.add(pushBtn); buttons.add(pullBtn); buttons.add(shareBtn)

        val north = JPanel(BorderLayout(0, 8))
        north.add(form, BorderLayout.NORTH)
        north.add(buttons, BorderLayout.CENTER)

        panel.add(north, BorderLayout.NORTH)
        panel.add(JBScrollPane(logArea), BorderLayout.CENTER)
        return panel
    }

    private fun loadConfig() {
        val svc = WorkspaceSyncService.getInstance(project)
        val cfg = svc.getConfig()
        baseUrlField.text = cfg.baseUrl
        workspaceField.text = cfg.workspaceId
        accountIdField.text = cfg.accountId
        tokenField.text = svc.getAccountToken().orEmpty()
        syncCollChk.isSelected = cfg.syncCollections
        syncEnvChk.isSelected = cfg.syncEnvironments
        syncHistChk.isSelected = cfg.syncHistory
    }

    private fun saveConfig() {
        val svc = WorkspaceSyncService.getInstance(project)
        svc.updateConfig(svc.getConfig().copy(
            baseUrl = baseUrlField.text.trim(),
            workspaceId = workspaceField.text.trim(),
            accountId = accountIdField.text.trim(),
            syncCollections = syncCollChk.isSelected,
            syncEnvironments = syncEnvChk.isSelected,
            syncHistory = syncHistChk.isSelected,
        ))
        svc.setAccountToken(String(tokenField.password))
        logArea.append("[config] saved.\n")
    }

    private fun runAsync(label: String, block: (WorkspaceSyncService) -> String) {
        logArea.append("[$label] starting...\n")
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                block(WorkspaceSyncService.getInstance(project))
            } catch (e: SyncException.NotConfigured) {
                "NOT CONFIGURED: ${e.message}"
            } catch (e: SyncException) {
                "FAILED: ${e.message}"
            } catch (e: Exception) {
                "EXCEPTION: ${e.message}"
            }
            SwingUtilities.invokeLater { logArea.append("[$label] $text\n") }
        }
    }

    private fun promptShare() {
        val collId = Messages.showInputDialog(contentPanel, "Collection ID to share", "Share", null) ?: return
        val members = Messages.showInputDialog(contentPanel,
            "Comma-separated member IDs (email / uuid)", "Share", null)?.split(",")?.map { it.trim() }
            ?.filter { it.isNotBlank() } ?: return
        runAsync("share") {
            it.share(collId, members).toString()
        }
    }
}
