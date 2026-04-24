package io.github.movebrickschi.restfulall.humanize

import io.github.movebrickschi.restfulall.service.ApplicationLanguageHolder
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap

/**
 * 人性化文案提供器：场景化随机挑选彩蛋文案。
 *
 * 文案资源走独立的 [FunBundle] 而非主 MyMessageBundle，
 * 方便未来批量更换文案风格（严肃/正常/俏皮）。
 *
 * 每个场景（scene）有多条变体，key 形如 `empty.no_match.1` ~ `empty.no_match.5`。
 * 此处通过扫描 bundle 中相同前缀的 key 实现自动合并。
 */
object FunMessageProvider {

    private const val BUNDLE_BASE = "messages.FunBundle"
    private val bundleCache = ConcurrentHashMap<Locale, ResourceBundle>()
    private val variantCache = ConcurrentHashMap<Pair<Locale, String>, List<String>>()
    private val noFallbackControl: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    /** 按场景随机返回一条文案；若场景没有文案，返回空字符串。 */
    fun pick(scene: String): String {
        val variants = variantsFor(scene)
        if (variants.isEmpty()) return ""
        return variants.random()
    }

    /** 根据当前时间挑一个合适的问候语场景（morning / afternoon / evening / late_night）。 */
    fun greetingScene(now: LocalDateTime = LocalDateTime.now()): String {
        // 周五单独处理（周五场景优先级高于时段）
        if (now.dayOfWeek == DayOfWeek.FRIDAY) return "greeting.friday"
        if (now.dayOfWeek == DayOfWeek.MONDAY && now.hour < 12) return "greeting.monday"
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return "greeting.weekend"
        return when (now.hour) {
            in 5..11 -> "greeting.morning"
            in 12..17 -> "greeting.afternoon"
            in 18..22 -> "greeting.evening"
            else -> "greeting.late_night"
        }
    }

    /** 便捷方法：直接返回一条问候语。 */
    fun pickGreeting(now: LocalDateTime = LocalDateTime.now()): String = pick(greetingScene(now))

    private fun variantsFor(scene: String): List<String> {
        val locale = effectiveLocale()
        val cacheKey = locale to scene
        return variantCache.getOrPut(cacheKey) {
            val bundle = bundle(locale)
            val prefix = "$scene."
            bundle.keySet()
                .asSequence()
                .filter { it.startsWith(prefix) }
                .sorted()
                .map { bundle.getString(it) }
                .toList()
        }
    }

    private fun effectiveLocale(): Locale =
        ApplicationLanguageHolder.overrideLocale() ?: Locale.getDefault()

    private fun bundle(locale: Locale): ResourceBundle {
        return bundleCache.computeIfAbsent(locale) {
            ResourceBundle.getBundle(
                BUNDLE_BASE,
                locale,
                FunMessageProvider::class.java.classLoader,
                noFallbackControl,
            )
        }
    }
}
