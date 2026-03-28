package io.github.movebrickschi.restfulall.model

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
