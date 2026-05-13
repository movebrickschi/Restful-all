package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.ParamEntry
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * v1.3 - 请求规格。
 *
 * 从 UI 层（ApiDebugPanel）和 API 层（cURL 导入 / 压测 / 编排）
 * 共同使用的请求描述。
 */
data class RequestSpec(
    val method: String = "GET",
    val url: String = "",
    val queryParams: List<Pair<String, String>> = emptyList(),
    val headers: List<Pair<String, String>> = emptyList(),
    val cookies: List<Pair<String, String>> = emptyList(),
    val pathParams: List<Pair<String, String>> = emptyList(),
    val bodyType: String = "none",
    val bodyContent: String = "",
    val formParams: List<ParamEntry> = emptyList(),
    val timeoutSeconds: Long = 30,
)

/**
 * v1.3 - 请求结果。
 */
data class RequestResult(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
    val elapsed: Long,
    val contentType: String = "",
    val isSSE: Boolean = false,
    val isNdjson: Boolean = false,
    val error: String? = null,
)

/**
 * v1.3 W1-3 - 请求调度器。
 *
 * 从 `ApiDebugPanel.sendRequest()` 抽取的请求执行核心逻辑。
 * 处理链路：
 * 1. 变量替换（EnvironmentService.resolve）
 * 2. Path 参数注入
 * 3. Query 参数拼接
 * 4. HTTP 构建 + 发送
 * 5. 响应解压 + 包装
 *
 * **不含 UI 交互**——UI 更新由调用方（ApiDebugPanel）在 EDT 上处理。
 *
 * 后续接入点：F2 cURL / F6 响应视图 / F7 断言 / P0-2 AI 诊断 /
 * P1-6 Mock / P2-8 压测 / P2-9 编排。
 */
@Service(Service.Level.PROJECT)
class RequestExecutor(private val project: Project) {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    companion object {
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")

        /**
         * 响应体硬上限，防止恶意 / 误配后端返回 GB 级响应导致 OOM。
         * 超出后截断至上限并把 [RequestResult.error] 标记为 "response truncated"，
         * 调用方仍能拿到部分数据排查问题。
         */
        const val MAX_RESPONSE_BYTES: Int = 50 * 1024 * 1024

        fun getInstance(project: Project): RequestExecutor =
            project.getService(RequestExecutor::class.java)
    }

    /**
     * 执行同步 HTTP 请求。
     *
     * 调用方可在后台线程调用（不会触碰 EDT / Swing）。
     * SSE / NDJSON 流式请求暂不走本方法（仍在 ApiDebugPanel 原逻辑），
     * 待 v1.3.1 迁移流式处理。
     */
    fun execute(spec: RequestSpec): RequestResult {
        val startTime = System.currentTimeMillis()
        try {
            var url = resolveVariables(spec.url)

            for ((name, value) in spec.pathParams) {
                val encoded = URLEncoder.encode(value, Charsets.UTF_8)
                url = url.replace("{$name}", encoded).replace(":$name", encoded)
            }

            val resolvedQuery = spec.queryParams.map { (k, v) ->
                resolveVariables(k) to resolveVariables(v)
            }
            if (resolvedQuery.isNotEmpty()) {
                val qs = resolvedQuery.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
                }
                url = if ("?" in url) "$url&$qs" else "$url?$qs"
            }

            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }

            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(spec.timeoutSeconds))

            val resolvedHeaders = spec.headers.map { (k, v) ->
                resolveVariables(k) to resolveVariables(v)
            }
            for ((name, value) in resolvedHeaders) {
                if (name.isNotBlank()) builder.header(name, value)
            }

            val resolvedCookies = spec.cookies.map { (k, v) ->
                resolveVariables(k) to resolveVariables(v)
            }
            if (resolvedCookies.isNotEmpty()) {
                builder.header("Cookie", resolvedCookies.joinToString("; ") { "${it.first}=${it.second}" })
            }

            val bodyContent = resolveVariables(spec.bodyContent)
            val bodyPublisher: HttpRequest.BodyPublisher
            var contentType: String? = null

            if (spec.method.uppercase() in METHODS_WITH_BODY) {
                when (spec.bodyType) {
                    "none" -> bodyPublisher = HttpRequest.BodyPublishers.noBody()
                    "json" -> {
                        bodyPublisher = if (bodyContent.isNotBlank())
                            HttpRequest.BodyPublishers.ofString(bodyContent)
                        else HttpRequest.BodyPublishers.noBody()
                        contentType = "application/json"
                    }
                    "xml" -> {
                        bodyPublisher = if (bodyContent.isNotBlank())
                            HttpRequest.BodyPublishers.ofString(bodyContent)
                        else HttpRequest.BodyPublishers.noBody()
                        contentType = "application/xml"
                    }
                    "raw" -> {
                        bodyPublisher = if (bodyContent.isNotBlank())
                            HttpRequest.BodyPublishers.ofString(bodyContent)
                        else HttpRequest.BodyPublishers.noBody()
                    }
                    "x-www-form-urlencoded" -> {
                        val encoded = spec.formParams
                            .filter { it.enabled && it.name.isNotBlank() }
                            .joinToString("&") { p ->
                                "${URLEncoder.encode(p.name, Charsets.UTF_8)}=${URLEncoder.encode(p.value, Charsets.UTF_8)}"
                            }
                        bodyPublisher = HttpRequest.BodyPublishers.ofString(encoded)
                        contentType = "application/x-www-form-urlencoded"
                    }
                    else -> bodyPublisher = HttpRequest.BodyPublishers.noBody()
                }
            } else {
                bodyPublisher = HttpRequest.BodyPublishers.noBody()
            }

            if (contentType != null) {
                val hasContentType = resolvedHeaders.any { it.first.equals("Content-Type", ignoreCase = true) }
                if (!hasContentType) builder.header("Content-Type", contentType)
            }

            builder.method(spec.method.uppercase(), bodyPublisher)

            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            val respContentType = response.headers().firstValue("content-type").orElse("")
            val isSSE = respContentType.contains("text/event-stream", ignoreCase = true)
            val isNdjson = !isSSE && (
                respContentType.contains("x-ndjson", ignoreCase = true) ||
                respContentType.contains("jsonlines", ignoreCase = true)
            )

            val bodyStream = decompressIfNeeded(response.body(), response.headers())
            val (bodyBytes, truncated) = IoSafetyUtil.readBoundedBytes(bodyStream, MAX_RESPONSE_BYTES)
            val charset = parseResponseCharset(respContentType) { name ->
                thisLogger().info("Unknown response charset '$name', falling back to UTF-8")
            }
            val bodyString = String(bodyBytes, charset)
            val elapsed = System.currentTimeMillis() - startTime

            return RequestResult(
                statusCode = response.statusCode(),
                body = bodyString,
                headers = response.headers().map(),
                elapsed = elapsed,
                contentType = respContentType,
                isSSE = isSSE,
                isNdjson = isNdjson,
                error = if (truncated) {
                    "response truncated: exceeded ${MAX_RESPONSE_BYTES / 1024 / 1024} MB cap"
                } else {
                    null
                },
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return RequestResult(0, "", emptyMap(), System.currentTimeMillis() - startTime, error = "Interrupted")
        } catch (e: Exception) {
            thisLogger().warn("RequestExecutor.execute failed", e)
            return RequestResult(
                statusCode = 0,
                body = "",
                headers = emptyMap(),
                elapsed = System.currentTimeMillis() - startTime,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    private fun resolveVariables(input: String): String {
        if (!input.contains("\${")) return input
        val envResolved = try {
            EnvironmentService.getInstance(project).resolve(input)
        } catch (e: Exception) {
            thisLogger().debug("Env variable resolve failed: ${e.message}")
            input
        }
        return BuiltinVariableResolver.resolve(envResolved)
    }

    private fun decompressIfNeeded(stream: InputStream, headers: HttpHeaders): InputStream {
        val encoding = headers.firstValue("content-encoding").orElse("").lowercase()
        return when (encoding) {
            "", "identity" -> stream
            "gzip" -> GZIPInputStream(stream)
            "deflate" -> InflaterInputStream(stream)
            else -> throw java.io.IOException(
                "Unsupported response content-encoding: '$encoding'. " +
                    "Only gzip / deflate are decoded; consider sending Accept-Encoding to negotiate.",
            )
        }
    }

}

private val CHARSET_PATTERN = Regex("(?i)charset\\s*=\\s*([^;\\s]+)")

/**
 * 从 `Content-Type` 头中提取 `charset=` 子参数，缺失或无效时回落到 UTF-8。
 * 解决之前硬编码 UTF-8 导致 GBK / GB2312 等中文 API 响应乱码的问题。
 *
 * @param contentType 完整 `Content-Type` header 值
 * @param onUnknown 解析到 charset 名但 JVM 未支持时的回调（用于调用方记日志），默认 no-op
 */
internal fun parseResponseCharset(
    contentType: String,
    onUnknown: (String) -> Unit = {},
): java.nio.charset.Charset {
    if (contentType.isBlank()) return Charsets.UTF_8
    val match = CHARSET_PATTERN.find(contentType) ?: return Charsets.UTF_8
    val name = match.groupValues[1].trim().trim('"', '\'')
    return try {
        java.nio.charset.Charset.forName(name)
    } catch (_: Exception) {
        onUnknown(name)
        Charsets.UTF_8
    }
}
