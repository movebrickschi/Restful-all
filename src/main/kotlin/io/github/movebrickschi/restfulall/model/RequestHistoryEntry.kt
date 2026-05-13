package io.github.movebrickschi.restfulall.model

import com.intellij.util.xmlb.annotations.OptionTag

data class RequestHistoryEntry(
    var timestamp: Long = 0L,
    var method: String = "GET",
    var url: String = "",
    var queryParams: MutableList<ParamEntry> = mutableListOf(),
    var headers: MutableList<ParamEntry> = mutableListOf(),
    var cookies: MutableList<ParamEntry> = mutableListOf(),
    var body: String = "",
    var bodyType: String = "json",
    var formParams: MutableList<ParamEntry> = mutableListOf(),
    var responseStatus: Int = 0,
    var responseBody: String = "",
    var responseHeaders: MutableList<ParamEntry> = mutableListOf(),
    var elapsed: Long = 0L,

    /**
     * v1.3.2 F9 - 用户为该历史条目打的自定义标签（如 "线上 bug"、"待回归"）。
     *
     * - 上限 5 个；UI 在添加时校验。
     * - 在搜索框中可用 `tag:xxx` 语法过滤。
     */
    @get:OptionTag(tag = "tags")
    var tags: MutableList<String> = mutableListOf(),

    /**
     * v1.3.2 F9 - 用户对该条目的备注。
     *
     * - 上限 500 字；超出会被 UI 截断保存。
     * - 在搜索框中可被 free-text 匹配。
     */
    var note: String = "",
) {
    fun displayTime(): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss")
        return sdf.format(java.util.Date(timestamp))
    }

    fun displayDate(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd")
        return sdf.format(java.util.Date(timestamp))
    }

    fun displayUrl(): String {
        val maxLen = 80
        return if (url.length > maxLen) url.substring(0, maxLen) + "..." else url
    }
}
