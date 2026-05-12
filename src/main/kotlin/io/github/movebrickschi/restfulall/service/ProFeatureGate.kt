package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.license.LicenseManager

/**
 * v1.3 - Pro 功能授权统一入口。
 *
 * 设计要点：
 * - 类型安全封装：所有 v1.3 新增 Pro / Team 功能通过 [ProFeature] enum 统一标识，
 *   避免散落字符串 key 拼写错误。
 * - 委托现有 [LicenseManager]：本类不直接判断订阅状态，转发到 LicenseManager
 *   的反射式 LicensingFacade 检查，由 JetBrains Marketplace 统一管理试用 / 购买。
 * - 兼容旧 String key：v1.2 已有的 6 个 Pro 入口（favorite / pin / note / theme /
 *   year_report / gutter_debug）继续使用原 [LicenseManager.requirePro] 字符串接口，
 *   本网关只负责 v1.3 新功能。
 *
 * 使用示例：
 * ```
 * if (!ProFeatureGate.requirePro(project, ProFeature.AI_PARAM_FILL)) return
 * // ...继续执行 Pro 功能逻辑
 * ```
 */
object ProFeatureGate {

    /**
     * 当前项目是否具备 Pro 权限。
     *
     * 仅查询，不弹任何提示。需要弹气泡引导升级时调用 [requirePro] / [showUpsell]。
     */
    fun isPro(project: Project?): Boolean = LicenseManager.isPro(project)

    /**
     * 入口守卫：要求 Pro 权限。
     *
     * @return true 表示已授权可继续；false 表示已弹升级气泡，调用方应立即 early-return。
     */
    fun requirePro(project: Project, feature: ProFeature): Boolean =
        LicenseManager.requirePro(project, feature.key)

    /**
     * 主动弹升级气泡（不阻塞、不返回结果）。
     *
     * 适用于需要在 UI 渲染时（如 Pro 入口置灰旁挂提示）主动引导升级的场景。
     */
    fun showUpsell(project: Project, feature: ProFeature) =
        LicenseManager.showUpsellBubble(project, feature.key)

    /**
     * 获取当前 License 状态文本（用于设置页 / 工具栏展示）。
     */
    fun statusText(project: Project): String = LicenseManager.statusText(project)
}

/**
 * v1.3 - Pro / Team 档位功能枚举。
 *
 * @param key 用于查 i18n / 日志的稳定字符串标识，**禁止修改已发布的 key**。
 * @param tier 该功能所属档位（影响升级气泡文案 + 计量策略）。
 */
enum class ProFeature(val key: String, val tier: Tier) {
    AI_PARAM_FILL("ai_param_fill", Tier.PRO),
    AI_DIAGNOSE("ai_diagnose", Tier.PRO),
    AI_TEST_CASE("ai_test_case", Tier.PRO),
    WORKSPACE_SYNC("workspace_sync", Tier.TEAM),
    TEAM_SHARE("team_share", Tier.TEAM),
    MOCK_SERVER("mock_server", Tier.TEAM),
    BIDIR_SYNC("bidir_sync", Tier.TEAM),
    PRESS_TEST("press_test", Tier.PRO),
    CASE_CHAIN("case_chain", Tier.PRO),
    ENV_DIFF("env_diff", Tier.PRO),
    SIGN_PLUGIN("sign_plugin", Tier.PRO),
    ;

    enum class Tier { PRO, TEAM }

    companion object {
        fun fromKey(key: String): ProFeature? = entries.firstOrNull { it.key == key }
    }
}
