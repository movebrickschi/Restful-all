package io.github.movebrickschi.restfulall.service

import com.intellij.util.messages.Topic

/**
 * 语言变更事件广播接口，UI 面板在构造时通过 application.messageBus 订阅。
 */
fun interface LanguageChangeListener {
    fun languageChanged(language: String)

    companion object {
        @JvmField
        val TOPIC: Topic<LanguageChangeListener> = Topic.create(
            "Restful-all language change",
            LanguageChangeListener::class.java
        )
    }
}
