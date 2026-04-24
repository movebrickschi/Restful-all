package io.github.movebrickschi.restfulall.theme

import io.github.movebrickschi.restfulall.model.HttpMethod
import java.awt.Color

/**
 * 路由展示用的主题。包含 HTTP Method 颜色、辅助色和语义色。
 *
 * Theme 仅负责颜色定义，不涉及持久化；持久化和覆盖走
 * [io.github.movebrickschi.restfulall.service.PluginSettingsState]。
 */
data class Theme(
    val id: String,
    val displayName: String,
    val methodColors: Map<HttpMethod, Color>,
    val accentColor: Color,
    val mutedColor: Color,
    val fileColor: Color,
) {
    fun colorFor(method: HttpMethod): Color =
        methodColors[method] ?: methodColors[HttpMethod.ALL] ?: mutedColor

    fun withOverrides(overrides: Map<HttpMethod, Color>): Theme {
        if (overrides.isEmpty()) return this
        val merged = methodColors.toMutableMap().apply { putAll(overrides) }
        return copy(methodColors = merged)
    }

    companion object {
        fun c(hex: Int): Color = Color((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)

        private fun methodPalette(
            get: Int, post: Int, put: Int, delete: Int, patch: Int,
            head: Int, options: Int, all: Int,
        ): Map<HttpMethod, Color> = mapOf(
            HttpMethod.GET to c(get),
            HttpMethod.POST to c(post),
            HttpMethod.PUT to c(put),
            HttpMethod.DELETE to c(delete),
            HttpMethod.PATCH to c(patch),
            HttpMethod.HEAD to c(head),
            HttpMethod.OPTIONS to c(options),
            HttpMethod.ALL to c(all),
        )

        /** 内置 10 套主题。 */
        val BUILTIN: List<Theme> = listOf(
            Theme(
                id = "default",
                displayName = "Classic Dark",
                methodColors = methodPalette(0x61AFEF, 0x98C379, 0xE5C07B, 0xE06C75, 0xC678DD, 0x56B6C2, 0xABB2BF, 0xD19A66),
                accentColor = c(0x589DF6), mutedColor = c(0x888888), fileColor = c(0x888888),
            ),
            Theme(
                id = "classic-light",
                displayName = "Classic Light",
                methodColors = methodPalette(0x2E7DD7, 0x2E8B57, 0xB8860B, 0xB22222, 0x8A2BE2, 0x008B8B, 0x696969, 0xD2691E),
                accentColor = c(0x2E7DD7), mutedColor = c(0x555555), fileColor = c(0x777777),
            ),
            Theme(
                id = "macaron",
                displayName = "Macaron",
                methodColors = methodPalette(0xA0D2EB, 0xB5EAD7, 0xFFDAC1, 0xFFB7B2, 0xE2C2FF, 0xC7CEEA, 0xF2E2C4, 0xFFC8A2),
                accentColor = c(0xFFB7B2), mutedColor = c(0xA89F91), fileColor = c(0xA89F91),
            ),
            Theme(
                id = "vaporwave",
                displayName = "Vaporwave",
                methodColors = methodPalette(0x00E5FF, 0xFF6EC7, 0xFFE338, 0xFF0066, 0xB15EFF, 0x7DF9FF, 0xFF99DD, 0xFF9933),
                accentColor = c(0xFF6EC7), mutedColor = c(0x8A9EFF), fileColor = c(0x8A9EFF),
            ),
            Theme(
                id = "terminal",
                displayName = "Terminal",
                methodColors = methodPalette(0x00FF87, 0x39FF14, 0xFFFF00, 0xFF073A, 0xBD00FF, 0x00FFFF, 0xC0C0C0, 0xFF8800),
                accentColor = c(0x39FF14), mutedColor = c(0x7F7F7F), fileColor = c(0x7F7F7F),
            ),
            Theme(
                id = "morandi",
                displayName = "Morandi",
                methodColors = methodPalette(0x8A9BA8, 0xA3B5A0, 0xCDBA96, 0xBF8F8F, 0xA699A2, 0x8FA0A0, 0xA0A0A0, 0xBFA382),
                accentColor = c(0xA3B5A0), mutedColor = c(0x9A9A94), fileColor = c(0x9A9A94),
            ),
            Theme(
                id = "dopamine",
                displayName = "Dopamine",
                methodColors = methodPalette(0x00C2FF, 0x00D084, 0xFFC400, 0xFF3B30, 0xAF52DE, 0x32ADE6, 0xFF9500, 0xFF2D55),
                accentColor = c(0x00D084), mutedColor = c(0x888888), fileColor = c(0x888888),
            ),
            Theme(
                id = "nord",
                displayName = "Nord",
                methodColors = methodPalette(0x88C0D0, 0xA3BE8C, 0xEBCB8B, 0xBF616A, 0xB48EAD, 0x81A1C1, 0xD8DEE9, 0xD08770),
                accentColor = c(0x88C0D0), mutedColor = c(0x81879A), fileColor = c(0x81879A),
            ),
            Theme(
                id = "monokai",
                displayName = "Monokai",
                methodColors = methodPalette(0x66D9EF, 0xA6E22E, 0xF4BF75, 0xF92672, 0xAE81FF, 0x4DD0E1, 0xCFCFC2, 0xFD971F),
                accentColor = c(0xF92672), mutedColor = c(0x75715E), fileColor = c(0x75715E),
            ),
            Theme(
                id = "github",
                displayName = "GitHub",
                methodColors = methodPalette(0x0366D6, 0x28A745, 0xDBAB09, 0xD73A49, 0x6F42C1, 0x0598BC, 0x586069, 0xE36209),
                accentColor = c(0x0366D6), mutedColor = c(0x6A737D), fileColor = c(0x6A737D),
            ),
        )

        /** 节日主题：返回 id 覆盖（如果当前节日没有特殊主题，返回 null）。 */
        fun festiveIdFor(month: Int, day: Int): String? = when {
            month == 2 && day in 1..15 -> "macaron"      // 春节前后
            month == 10 && day == 24 -> "terminal"        // 程序员节
            month == 12 && day in 20..31 -> "nord"        // 圣诞 / 年末
            month == 1 && day in 1..3 -> "nord"
            else -> null
        }

        val DEFAULT: Theme = BUILTIN.first()
    }
}
