package io.github.movebrickschi.restfulall.license

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.util.concurrent.TimeUnit

/**
 * Pro 功能的统一授权入口。
 *
 * 本版本策略：
 * - 通过反射探测 `com.intellij.ui.LicensingFacade`（JetBrains Marketplace 官方接口）
 * - 首次使用自动记录 `licenseInstalledAt`，在此基础上给 14 天试用期
 * - 所有 Pro 功能入口都应先走 [requirePro]，未授权时显示升级气泡
 *
 * 说明：LicensingFacade 在某些旧版 IDE 上可能不存在，因此用反射调用，
 * 避免编译期硬依赖导致兼容性问题。
 */
object LicenseManager {

    private const val PRODUCT_CODE = "PRESTFULALL"
    private val TRIAL_DURATION_MS = TimeUnit.DAYS.toMillis(14)
    private const val NOTIFICATION_GROUP = "Restful-all.Pro"

    /**
     * 是否具备 Pro 权益（已购买 或 仍在试用期内）。
     */
    fun isPro(project: Project?): Boolean {
        if (hasValidLicense()) return true
        if (project == null) return false
        return isInTrial(project)
    }

    /** 是否已购买正式 License（Marketplace）。 */
    fun hasValidLicense(): Boolean {
        try {
            val facadeClass = Class.forName("com.intellij.ui.LicensingFacade")
            val instance = facadeClass.getMethod("getInstance").invoke(null) ?: return false
            val getConfirmationStamp = facadeClass.getMethod("getConfirmationStamp", String::class.java)
            val raw = getConfirmationStamp.invoke(instance, PRODUCT_CODE)
            val stamp = raw as? String
            return !stamp.isNullOrBlank()
        } catch (_: Throwable) {
            return false
        }
    }

    /** 当前是否处于免费试用窗口内。 */
    fun isInTrial(project: Project): Boolean {
        val start = PluginSettingsState.getInstance(project).getLicenseInstalledAt()
        return System.currentTimeMillis() - start < TRIAL_DURATION_MS
    }

    /** 剩余试用天数（最小为 0）。 */
    fun remainingTrialDays(project: Project): Int {
        val start = PluginSettingsState.getInstance(project).getLicenseInstalledAt()
        val remaining = TRIAL_DURATION_MS - (System.currentTimeMillis() - start)
        return (remaining / TimeUnit.DAYS.toMillis(1)).toInt().coerceAtLeast(0)
    }

    /**
     * 入口守卫：要求 Pro 权限。
     * @return true 表示放行，false 表示已显示升级提示、调用方应 early-return。
     */
    fun requirePro(project: Project, featureKey: String): Boolean {
        if (isPro(project)) return true
        showUpsellBubble(project, featureKey)
        return false
    }

    /** 显示升级气泡（非阻塞）。 */
    fun showUpsellBubble(project: Project, featureKey: String) {
        val remaining = remainingTrialDays(project)
        val title = MyMessageBundle.message("license.upsell.title")
        val content = if (remaining > 0) {
            MyMessageBundle.message("license.upsell.trial", featureKey, remaining)
        } else {
            MyMessageBundle.message("license.upsell.expired", featureKey)
        }
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }

    /**
     * 简短状态文案，用于设置页展示。
     */
    fun statusText(project: Project): String {
        return when {
            hasValidLicense() -> MyMessageBundle.message("license.status.paid")
            isInTrial(project) -> MyMessageBundle.message(
                "license.status.trial",
                remainingTrialDays(project),
            )
            else -> MyMessageBundle.message("license.status.expired")
        }
    }
}
