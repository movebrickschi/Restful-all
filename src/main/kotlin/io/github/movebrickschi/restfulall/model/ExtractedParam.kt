package io.github.movebrickschi.restfulall.model

enum class ParamLocation {
    QUERY,
    PATH,
    BODY,
    HEADER,
    COOKIE,
}

data class ExtractedParam(
    val name: String,
    val location: ParamLocation,
    val testValue: String,
)

data class ExtractedMethodParams(
    val queryParams: List<ExtractedParam>,
    val pathParams: List<ExtractedParam>,
    val headerParams: List<ExtractedParam>,
    val cookieParams: List<ExtractedParam>,
    val bodyJson: String?,
)
