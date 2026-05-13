package io.github.movebrickschi.restfulall.model

import com.intellij.util.xmlb.annotations.OptionTag
import io.github.movebrickschi.restfulall.service.RequestSpec

/**
 * v1.3.1 F3 - 请求规格的 XML 持久化版本。
 *
 * `service.RequestSpec` 使用 `val` + `Pair<String, String>`，对 IntelliJ XmlSerializer
 * 不友好。本类用 `var` + `MutableList<ParamEntry>` 替换，兼容 PersistentStateComponent
 * 序列化；CollectionItem / 历史快照 / 导入产物都用本类持久化，执行前调用
 * [toExecutable] 转回 `RequestSpec`。
 *
 * ## 设计约定
 *
 * - `ParamEntry.enabled = false` 的条目持久化但执行时跳过（同 GlobalParamsData 行为）
 * - `bodyType` 取值：`none` / `json` / `xml` / `raw` / `x-www-form-urlencoded` / `form-data`
 * - `formParams` 仅当 `bodyType` 为 `x-www-form-urlencoded` / `form-data` 时生效
 * - `timeoutSeconds` 上限 600（10 分钟），下限 1
 */
data class RequestSpecData(
    var method: String = "GET",
    var url: String = "",
    @get:OptionTag(tag = "queryParams")
    var queryParams: MutableList<ParamEntry> = mutableListOf(),
    @get:OptionTag(tag = "headers")
    var headers: MutableList<ParamEntry> = mutableListOf(),
    @get:OptionTag(tag = "cookies")
    var cookies: MutableList<ParamEntry> = mutableListOf(),
    @get:OptionTag(tag = "pathParams")
    var pathParams: MutableList<ParamEntry> = mutableListOf(),
    var bodyType: String = "none",
    var bodyContent: String = "",
    @get:OptionTag(tag = "formParams")
    var formParams: MutableList<ParamEntry> = mutableListOf(),
    var timeoutSeconds: Long = 30,
) {
    fun toExecutable(): RequestSpec = RequestSpec(
        method = method,
        url = url,
        queryParams = queryParams.filter { it.enabled && it.name.isNotBlank() }
            .map { it.name to it.value },
        headers = headers.filter { it.enabled && it.name.isNotBlank() }
            .map { it.name to it.value },
        cookies = cookies.filter { it.enabled && it.name.isNotBlank() }
            .map { it.name to it.value },
        pathParams = pathParams.filter { it.enabled && it.name.isNotBlank() }
            .map { it.name to it.value },
        bodyType = bodyType,
        bodyContent = bodyContent,
        formParams = formParams.filter { it.enabled && it.name.isNotBlank() }.toList(),
        timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT),
    )

    companion object {
        const val MIN_TIMEOUT: Long = 1
        const val MAX_TIMEOUT: Long = 600

        fun fromExecutable(spec: RequestSpec): RequestSpecData = RequestSpecData(
            method = spec.method,
            url = spec.url,
            queryParams = spec.queryParams.toParamEntries(),
            headers = spec.headers.toParamEntries(),
            cookies = spec.cookies.toParamEntries(),
            pathParams = spec.pathParams.toParamEntries(),
            bodyType = spec.bodyType,
            bodyContent = spec.bodyContent,
            formParams = spec.formParams.toMutableList(),
            timeoutSeconds = spec.timeoutSeconds.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT),
        )

        private fun List<Pair<String, String>>.toParamEntries(): MutableList<ParamEntry> =
            mapTo(mutableListOf()) { ParamEntry(enabled = true, name = it.first, value = it.second) }
    }
}
