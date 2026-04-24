package io.github.movebrickschi.restfulall.theme

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.awt.Color
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * 主题服务：内存缓存当前激活的 [Theme]；持久化走 [PluginSettingsState] 现有字段。
 *
 * 为什么应用级：RouteCellRenderer 等 UI 组件在 Application 生命周期内频繁调用，
 * 放应用级能避免每次渲染都去 project service 查询。
 *
 * 配置来源优先级：节日（若启用）→ 用户选择 → 默认。
 * 每次 [syncFrom] 都会根据以上优先级重算并缓存。
 */
@Service(Service.Level.APP)
class ThemeService {

    private val cache = AtomicReference(Theme.DEFAULT)

    fun current(): Theme = cache.get()

    /**
     * 从项目配置同步到内存缓存。应在 Popup / ToolWindow 打开时调用。
     */
    fun syncFrom(project: Project) {
        val settings = PluginSettingsState.getInstance(project)
        val preset = settings.getThemePreset()
        val base = resolveBase(preset)
        val overrides = parseCustomColors(settings.getCustomMethodColors())
        cache.set(base.withOverrides(overrides))
    }

    /**
     * 修改当前主题（preset id）。会立即同步缓存。
     */
    fun setPreset(project: Project, presetId: String) {
        val settings = PluginSettingsState.getInstance(project)
        settings.setThemePreset(presetId)
        syncFrom(project)
    }

    /**
     * 覆盖单个 HTTP Method 颜色（用户自定义）。
     */
    fun setCustomMethodColor(project: Project, method: HttpMethod, color: Color) {
        val hex = String.format("#%02X%02X%02X", color.red, color.green, color.blue)
        PluginSettingsState.getInstance(project).setCustomMethodColor(method.name, hex)
        syncFrom(project)
    }

    fun clearCustomColors(project: Project) {
        PluginSettingsState.getInstance(project).clearCustomMethodColors()
        syncFrom(project)
    }

    /** 根据 preset id 解析出基础主题，带节日覆盖。 */
    private fun resolveBase(presetId: String): Theme {
        val today = LocalDate.now()
        val festiveId = Theme.festiveIdFor(today.monthValue, today.dayOfMonth)
        // 节日只覆盖默认主题，用户如果主动选了其他主题则尊重用户选择
        val effectiveId = if (presetId == "default" && festiveId != null) festiveId else presetId
        return Theme.BUILTIN.firstOrNull { it.id == effectiveId } ?: Theme.DEFAULT
    }

    private fun parseCustomColors(raw: Map<String, String>): Map<HttpMethod, Color> {
        if (raw.isEmpty()) return emptyMap()
        val result = mutableMapOf<HttpMethod, Color>()
        for ((methodName, hex) in raw) {
            val method = runCatching { HttpMethod.valueOf(methodName) }.getOrNull() ?: continue
            val color = parseHex(hex) ?: continue
            result[method] = color
        }
        return result
    }

    private fun parseHex(hex: String): Color? {
        val cleaned = hex.trim().removePrefix("#")
        if (cleaned.length != 6) return null
        return try {
            val r = cleaned.substring(0, 2).toInt(16)
            val g = cleaned.substring(2, 4).toInt(16)
            val b = cleaned.substring(4, 6).toInt(16)
            Color(r, g, b)
        } catch (_: NumberFormatException) {
            null
        }
    }

    companion object {
        fun getInstance(): ThemeService =
            ApplicationManager.getApplication().getService(ThemeService::class.java)
    }
}
