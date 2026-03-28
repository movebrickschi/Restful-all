package io.github.movebrickschi.restfulall.model

data class ParamEntry(
    var enabled: Boolean = true,
    var name: String = "",
    var value: String = "",
)

data class GlobalParamsData(
    var queryParams: MutableList<ParamEntry> = mutableListOf(),
    var headerParams: MutableList<ParamEntry> = mutableListOf(),
    var cookieParams: MutableList<ParamEntry> = mutableListOf(),
    var bodyContent: String = "",
) {
    fun getActiveQueryParams(): List<Pair<String, String>> =
        queryParams.filter { it.enabled && it.name.isNotBlank() }.map { it.name to it.value }

    fun getActiveHeaderParams(): List<Pair<String, String>> =
        headerParams.filter { it.enabled && it.name.isNotBlank() }.map { it.name to it.value }

    fun getActiveCookieParams(): List<Pair<String, String>> =
        cookieParams.filter { it.enabled && it.name.isNotBlank() }.map { it.name to it.value }
}
