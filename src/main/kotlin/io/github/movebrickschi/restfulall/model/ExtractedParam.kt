package io.github.movebrickschi.restfulall.model

enum class ParamLocation {
    QUERY,
    PATH,
    BODY,
    HEADER,
    COOKIE,
}

enum class FormFieldType {
    TEXT,
    FILE,
}

data class ExtractedParam(
    val name: String,
    val location: ParamLocation,
    val testValue: String,
)

data class ExtractedFormParam(
    val name: String,
    val type: FormFieldType,
    val testValue: String = "",
)

data class ExtractedMethodParams(
    val queryParams: List<ExtractedParam> = emptyList(),
    val pathParams: List<ExtractedParam> = emptyList(),
    val headerParams: List<ExtractedParam> = emptyList(),
    val cookieParams: List<ExtractedParam> = emptyList(),
    val formParams: List<ExtractedFormParam> = emptyList(),
    val bodyJson: String? = null,
    val responseJson: String? = null,
)
