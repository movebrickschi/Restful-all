package io.github.movebrickschi.restfulall

import com.intellij.DynamicBundle
import io.github.movebrickschi.restfulall.service.ApplicationLanguageHolder
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

private const val BUNDLE = "messages.MyMessageBundle"

internal object MyMessageBundle {
    private val instance = DynamicBundle(MyMessageBundle::class.java, BUNDLE)
    private val overrideCache = ConcurrentHashMap<Locale, ResourceBundle>()

    // 关闭 JVM 默认 Locale 回退：没有 _en 时应回退到 base（英文），而不是系统默认的 _zh_CN。
    private val noFallbackControl: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    @JvmStatic
    fun message(key: @PropertyKey(resourceBundle = BUNDLE) String, vararg params: Any?): @Nls String {
        val override = ApplicationLanguageHolder.overrideLocale()
        return if (override == null) {
            instance.getMessage(key, *params)
        } else {
            formatFromLocale(override, key, params)
        }
    }

    @JvmStatic
    fun lazyMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any?): Supplier<@Nls String> {
        return Supplier { message(key, *params) }
    }

    private fun formatFromLocale(locale: Locale, key: String, params: Array<out Any?>): String {
        val bundle = overrideCache.computeIfAbsent(locale) {
            ResourceBundle.getBundle(
                BUNDLE,
                locale,
                MyMessageBundle::class.java.classLoader,
                noFallbackControl,
            )
        }
        val raw = try {
            bundle.getString(key)
        } catch (_: java.util.MissingResourceException) {
            return instance.getMessage(key, *params)
        }
        return if (params.isEmpty()) raw else MessageFormat.format(raw, *params)
    }
}
