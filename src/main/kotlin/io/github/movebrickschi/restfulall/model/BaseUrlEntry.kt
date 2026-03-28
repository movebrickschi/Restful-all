package io.github.movebrickschi.restfulall.model

data class BaseUrlEntry(
    var moduleName: String = "",
    var type: String = TYPE_MANUAL,
    var server: String = "127.0.0.1",
    var port: Int = 8080,
    var contextPath: String = "",
) {
    fun buildBaseUrl(): String {
        val host = server.ifBlank { "127.0.0.1" }
        val path = if (contextPath.isNotBlank() && !contextPath.startsWith("/")) "/$contextPath" else contextPath
        return "http://$host:$port$path"
    }

    companion object {
        const val TYPE_AUTO = "auto"
        const val TYPE_MANUAL = "manual"
    }
}
