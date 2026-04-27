package io.github.movebrickschi.restfulall.license

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.MyMessageBundle

/**
 * Pro 功能的统一授权入口。
 *
 * 本版本策略：
 * - 通过反射探测 `com.intellij.ui.LicensingFacade`（JetBrains Marketplace 官方接口）。
 * - 试用、购买、续费和离线授权交给 JetBrains Marketplace / IDE 统一管理。
 * - 所有 Pro 功能入口都应先走 [requirePro]，未授权时显示升级气泡。
 *
 * 说明：LicensingFacade 在某些旧版 IDE 上可能不存在，因此用反射调用，
 * 避免编译期硬依赖导致兼容性问题。
 */
object LicenseManager {

    private const val PRODUCT_CODE = "PRESTFULALL"
    private const val NOTIFICATION_GROUP = "Restful-all.Pro"

    enum class LicenseState {
        LICENSED,
        UNLICENSED,
        UNKNOWN,
    }

    /**
     * 是否具备 Pro 权益。
     *
     * Marketplace 的 30 天试用也会由 IDE 写入有效授权戳，因此这里不再维护本地试用期。
     */
    fun isPro(project: Project?): Boolean = hasValidLicense()

    /** 是否已购买正式 License（Marketplace）。 */
    fun hasValidLicense(): Boolean = licenseState() == LicenseState.LICENSED

    fun licenseState(): LicenseState {
        try {
            val facadeClass = Class.forName("com.intellij.ui.LicensingFacade")
            val instance = facadeClass.getMethod("getInstance").invoke(null) ?: return LicenseState.UNKNOWN
            val getConfirmationStamp = facadeClass.getMethod("getConfirmationStamp", String::class.java)
            val raw = getConfirmationStamp.invoke(instance, PRODUCT_CODE)
            val stamp = raw as? String
            return if (stamp.isNullOrBlank()) LicenseState.UNLICENSED else LicenseState.LICENSED
        } catch (_: Throwable) {
            return LicenseState.UNKNOWN
        }
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
        val title = MyMessageBundle.message("license.upsell.title")
        val featureName = featureDisplayName(featureKey)
        val content = when (licenseState()) {
            LicenseState.UNKNOWN -> MyMessageBundle.message("license.upsell.checking", featureName)
            LicenseState.UNLICENSED -> MyMessageBundle.message("license.upsell.marketplace", featureName)
            LicenseState.LICENSED -> return
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
        return when (licenseState()) {
            LicenseState.LICENSED -> MyMessageBundle.message("license.status.paid")
            LicenseState.UNLICENSED -> MyMessageBundle.message("license.status.free")
            LicenseState.UNKNOWN -> MyMessageBundle.message("license.status.checking")
        }
    }

    private fun featureDisplayName(featureKey: String): String {
        return when (featureKey) {
            "favorite" -> MyMessageBundle.message("license.feature.favorite")
            "pin" -> MyMessageBundle.message("license.feature.pin")
            "note" -> MyMessageBundle.message("license.feature.note")
            "theme" -> MyMessageBundle.message("license.feature.theme")
            "year_report" -> MyMessageBundle.message("license.feature.year_report")
            "gutter_debug" -> MyMessageBundle.message("license.feature.gutter_debug")
            else -> featureKey
        }
    }
}
