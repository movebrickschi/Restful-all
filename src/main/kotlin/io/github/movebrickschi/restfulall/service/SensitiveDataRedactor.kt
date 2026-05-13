package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.ParamEntry
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry

/**
 * v1.3.1 - 历史请求敏感字段脱敏。
 *
 * 用于在写入持久化的 [RequestHistoryEntry] 之前，去除可被 git track 的明文 token / 密钥。
 *
 * ## 覆盖范围
 *
 * | 字段类别 | 触发条件 | 处理 |
 * |---------|---------|------|
 * | header name | 命中 [SENSITIVE_HEADER_PATTERNS] | value 整体替换为 [REDACTED] |
 * | cookie | 全部 | value 整体替换为 [REDACTED] |
 * | query / form param name | 命中 [SENSITIVE_PARAM_PATTERNS] | value 整体替换为 [REDACTED] |
 * | url query string | `?token=xxx&api_key=yyy` | 命中字段替换 |
 * | body (任意 JSON) | `"(token|password|api_key|...)": "..."` 正则匹配 | 替换为 `"name":"REDACTED"` |
 *
 * ## 设计取舍
 *
 * - **不动原 entry**：内部走 [RequestHistoryEntry.copy] 深拷贝，避免影响 UI 上显示的最近一次请求面板
 * - **保守替换**：宁愿误杀（多脱一些）也不漏掉真 token；用户看历史只能定位"打过哪个接口"
 *   而不能"复用 token 重发"
 * - **不解析复杂结构**：body 用正则匹配最常见的 JSON `"key": "value"` 形态；XML / form-data
 *   等复杂格式按整体保留，由 header 维度拦截兜底
 */
object SensitiveDataRedactor {

    const val REDACTED: String = "***REDACTED***"

    private val SENSITIVE_HEADER_PATTERNS: List<Regex> = listOf(
        Regex("(?i)^authorization$"),
        Regex("(?i)^proxy-authorization$"),
        Regex("(?i)^cookie$"),
        Regex("(?i)^set-cookie$"),
        // 补：CSRF / XSRF 系列也需脱敏
        Regex("(?i).*(token|secret|api[-_]?key|password|passwd|pwd|session|x-auth|bearer|private[-_]?key|otp|pin|csrf|xsrf).*"),
    )

    private val SENSITIVE_PARAM_PATTERNS: List<Regex> = listOf(
        Regex(
            "(?i).*(token|secret|api[-_]?key|password|passwd|pwd|access[-_]?key|sign(ature)?|" +
                "bearer|private[-_]?key|client[-_]?secret|otp|pin|csrf|xsrf).*",
        ),
    )

    private val BODY_JSON_REDACT_PATTERN: Regex = Regex(
        "\"(token|access_token|refresh_token|id_token|bearer_token|bearerToken|password|passwd|pwd|" +
            "api_key|apiKey|secret|client_secret|clientSecret|private_key|privateKey|signature|otp|pin|" +
            "csrf_token|csrfToken|xsrf_token|xsrfToken)\"" +
            "\\s*:\\s*\"([^\"\\\\]|\\\\.)*\"",
        RegexOption.IGNORE_CASE,
    )

    private val FORM_URLENCODED_REDACT_PATTERN: Regex = Regex(
        "(^|[&?])(token|access_token|refresh_token|id_token|bearer_token|password|passwd|pwd|" +
            "api_key|apikey|secret|client_secret|private_key|signature|otp|pin|" +
            "csrf_token|csrftoken|xsrf_token|xsrftoken)=([^&#\\s]*)",
        RegexOption.IGNORE_CASE,
    )

    fun redact(entry: RequestHistoryEntry): RequestHistoryEntry = entry.copy(
        url = redactUrlQuery(entry.url),
        queryParams = entry.queryParams.map(::redactParam).toMutableList(),
        headers = entry.headers.map(::redactHeader).toMutableList(),
        cookies = entry.cookies.map { ParamEntry(it.enabled, it.name, redactedIfNotBlank(it.value)) }.toMutableList(),
        body = redactBody(entry.body),
        formParams = entry.formParams.map(::redactParam).toMutableList(),
        responseBody = redactPlainText(entry.responseBody),
        responseHeaders = entry.responseHeaders.map(::redactHeader).toMutableList(),
    )

    fun redactHeader(entry: ParamEntry): ParamEntry =
        if (entry.name.isNotBlank() && SENSITIVE_HEADER_PATTERNS.any { it.matches(entry.name) }) {
            ParamEntry(entry.enabled, entry.name, redactedIfNotBlank(entry.value))
        } else {
            entry
        }

    fun redactParam(entry: ParamEntry): ParamEntry =
        if (entry.name.isNotBlank() && SENSITIVE_PARAM_PATTERNS.any { it.matches(entry.name) }) {
            ParamEntry(entry.enabled, entry.name, redactedIfNotBlank(entry.value))
        } else {
            entry
        }

    fun redactUrlQuery(url: String): String {
        if (url.isBlank()) return url
        val qsStart = url.indexOf('?')
        if (qsStart < 0 || qsStart == url.length - 1) return url
        val (base, qs) = url.substring(0, qsStart) to url.substring(qsStart + 1)
        val rebuilt = qs.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) {
                pair
            } else {
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1)
                if (SENSITIVE_PARAM_PATTERNS.any { it.matches(k) } && v.isNotBlank()) {
                    "$k=$REDACTED"
                } else {
                    pair
                }
            }
        }
        return "$base?$rebuilt"
    }

    fun redactBody(body: String): String {
        if (body.isBlank()) return body
        val afterJson = BODY_JSON_REDACT_PATTERN.replace(body) { match ->
            val name = match.groupValues[1]
            "\"$name\":\"$REDACTED\""
        }
        return redactFormUrlEncoded(afterJson)
    }

    /**
     * 脱敏纯文本（用于错误消息、异常 stack、自由格式日志等）。
     *
     * 同时处理 URL query / form-urlencoded / JSON 三种内嵌片段，覆盖
     * `RequestExecutor` 与 `ApiDebugPanel` catch 路径里把 `e.message` 直接拼回
     * `responseBody` / `error` 字段的场景：消息形如
     * `Could not resolve host: api.example.com/?api_key=xxx&token=yyy` 时，
     * 通过本方法可一并把内嵌的密文换成占位符。
     */
    fun redactPlainText(text: String): String {
        if (text.isBlank()) return text
        val afterUrl = redactUrlQuery(text)
        val afterForm = redactFormUrlEncoded(afterUrl)
        return BODY_JSON_REDACT_PATTERN.replace(afterForm) { match ->
            val name = match.groupValues[1]
            "\"$name\":\"$REDACTED\""
        }
    }

    private fun redactFormUrlEncoded(text: String): String =
        FORM_URLENCODED_REDACT_PATTERN.replace(text) { match ->
            val prefix = match.groupValues[1]
            val name = match.groupValues[2]
            val value = match.groupValues[3]
            if (value.isBlank()) match.value else "$prefix$name=$REDACTED"
        }

    private fun redactedIfNotBlank(value: String): String =
        if (value.isBlank()) value else REDACTED
}
