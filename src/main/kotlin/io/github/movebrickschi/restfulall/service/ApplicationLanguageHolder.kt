package io.github.movebrickschi.restfulall.service

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程级的语言覆盖持有者。MyMessageBundle 可能在任意（甚至无 Project）场景被调用，
 * 因此把当前语言选择保存在 Application 级的原子引用中；PluginSettingsState 在变更时同步到这里。
 */
object ApplicationLanguageHolder {

    const val LANG_AUTO = "AUTO"
    const val LANG_ZH_CN = "ZH_CN"
    const val LANG_EN = "EN"

    private val current = AtomicReference(LANG_AUTO)

    fun set(language: String) {
        val normalized = when (language.uppercase()) {
            LANG_ZH_CN -> LANG_ZH_CN
            LANG_EN    -> LANG_EN
            else       -> LANG_AUTO
        }
        current.set(normalized)
    }

    fun get(): String = current.get()

    /**
     * 返回手动覆盖的 Locale；若返回 null 表示走 DynamicBundle 默认（跟随 IDE）。
     */
    fun overrideLocale(): Locale? = when (current.get()) {
        LANG_ZH_CN -> Locale.SIMPLIFIED_CHINESE
        LANG_EN    -> Locale.ENGLISH
        else       -> null
    }
}
