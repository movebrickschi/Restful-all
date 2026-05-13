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
 * v1.3.1 - multipart/form-data 单个字段。
 *
 * [type] 取值为 `"text"` 或 `"file"`；file 时 [value] 是本地文件路径。
 */
data class MultipartFormParam(
    val name: String,
    val value: String,
    val type: String = "text",
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

    /**
     * \[v1.3.1] HttpClient 的 selector / async-callback executor。
     *
     * 默认 `HttpClient.newBuilder()` 走 ForkJoinPool common pool，遇到压测 / 短时间内大量
     * sendAsync 时 NIO selector + reader 线程数会爆增，占满 common pool 影响 IDE 其它任务。
     * 这里限制为 32 并发，对 IDE 内调试场景足够，且与 ForkJoinPool 解耦。
     */
    private val executor = com.intellij.util.concurrency.AppExecutorUtil
        .createBoundedApplicationPoolExecutor("RestfulHttpClient", HTTP_CLIENT_PARALLELISM)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .executor(executor)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    companion object {
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "DELETE")

        private const val MAX_MULTIPART_FILE_BYTES: Long = 50L * 1024 * 1024
        private const val MAX_MULTIPART_TOTAL_BYTES: Long = 100L * 1024 * 1024

        /**
         * 响应体硬上限，防止恶意 / 误配后端返回 GB 级响应导致 OOM。
         * 超出后截断至上限并把 [RequestResult.error] 标记为 "response truncated"，
         * 调用方仍能拿到部分数据排查问题。
         */
        const val MAX_RESPONSE_BYTES: Int = 50 * 1024 * 1024

        /** HttpClient 异步线程池上限；IDE 内调试场景 32 足够。 */
        private const val HTTP_CLIENT_PARALLELISM: Int = 32

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
            val response = send(spec)
            val responseBody = readResponseBody(response)
            val respContentType = responseBody.contentType
            val isSSE = respContentType.contains("text/event-stream", ignoreCase = true)
            val isNdjson = !isSSE && (
                respContentType.contains("x-ndjson", ignoreCase = true) ||
                respContentType.contains("jsonlines", ignoreCase = true)
            )
            val elapsed = System.currentTimeMillis() - startTime

            return RequestResult(
                statusCode = response.statusCode(),
                body = responseBody.text,
                headers = response.headers().map(),
                elapsed = elapsed,
                contentType = respContentType,
                isSSE = isSSE,
                isNdjson = isNdjson,
                error = if (responseBody.truncated) {
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

    fun send(
        spec: RequestSpec,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        multipartParams: List<MultipartFormParam> = emptyList(),
    ): HttpResponse<InputStream> {
        val request = buildRequest(spec, extraHeaders, multipartParams)
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    fun readResponseBody(response: HttpResponse<InputStream>): ResponseBodyData {
        val contentType = response.headers().firstValue("content-type").orElse("")
        val bodyStream = decompressIfNeeded(response.body(), response.headers())
        val (bodyBytes, truncated) = IoSafetyUtil.readBoundedBytes(bodyStream, MAX_RESPONSE_BYTES)
        val charset = parseResponseCharset(contentType) { name ->
            thisLogger().info("Unknown response charset '$name', falling back to UTF-8")
        }
        return ResponseBodyData(
            text = String(bodyBytes, charset),
            contentType = contentType,
            truncated = truncated,
        )
    }

    private fun buildRequest(
        spec: RequestSpec,
        extraHeaders: List<Pair<String, String>>,
        multipartParams: List<MultipartFormParam>,
    ): HttpRequest {
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
        for ((name, value) in extraHeaders) {
            if (name.isNotBlank()) builder.header(name, value)
        }

        val resolvedCookies = spec.cookies.map { (k, v) ->
            resolveVariables(k) to resolveVariables(v)
        }
        if (resolvedCookies.isNotEmpty()) {
            builder.header("Cookie", resolvedCookies.joinToString("; ") { "${it.first}=${it.second}" })
        }

        val (bodyPublisher, contentType) = buildBodyPublisher(spec, multipartParams)
        if (contentType != null) {
            val hasContentType = (resolvedHeaders + extraHeaders).any {
                it.first.equals("Content-Type", ignoreCase = true)
            }
            if (!hasContentType) builder.header("Content-Type", contentType)
        }

        return builder.method(spec.method.uppercase(), bodyPublisher).build()
    }

    private fun buildBodyPublisher(
        spec: RequestSpec,
        multipartParams: List<MultipartFormParam>,
    ): Pair<HttpRequest.BodyPublisher, String?> {
        if (spec.method.uppercase() !in METHODS_WITH_BODY) {
            return HttpRequest.BodyPublishers.noBody() to null
        }

        val bodyContent = resolveVariables(spec.bodyContent)
        return when (spec.bodyType) {
            "none" -> HttpRequest.BodyPublishers.noBody() to null
            "json" -> bodyContent.toBodyPublisher() to "application/json"
            "xml" -> bodyContent.toBodyPublisher() to "application/xml"
            "raw" -> bodyContent.toBodyPublisher() to null
            "x-www-form-urlencoded" -> {
                val encoded = spec.formParams
                    .filter { it.enabled && it.name.isNotBlank() }
                    .joinToString("&") { p ->
                        "${URLEncoder.encode(resolveVariables(p.name), Charsets.UTF_8)}=" +
                            URLEncoder.encode(resolveVariables(p.value), Charsets.UTF_8)
                    }
                HttpRequest.BodyPublishers.ofString(encoded) to "application/x-www-form-urlencoded"
            }
            "form-data" -> buildMultipartBody(multipartParams)
            else -> HttpRequest.BodyPublishers.noBody() to null
        }
    }

    private fun String.toBodyPublisher(): HttpRequest.BodyPublisher =
        if (isNotBlank()) HttpRequest.BodyPublishers.ofString(this) else HttpRequest.BodyPublishers.noBody()

    private fun buildMultipartBody(
        params: List<MultipartFormParam>,
    ): Pair<HttpRequest.BodyPublisher, String> {
        val boundary = "----FormBoundary${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val byteArrays = mutableListOf<ByteArray>()
        val lineBreak = "\r\n".toByteArray(Charsets.UTF_8)
        var runningSize = 0L

        for ((name, value, type) in params.filter { it.name.isNotBlank() }) {
            byteArrays.add("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            if (type == "file") {
                val filePath = java.nio.file.Path.of(resolveVariables(value))
                check(java.nio.file.Files.isRegularFile(filePath)) {
                    "Multipart file not found or not a regular file: $value"
                }
                check(java.nio.file.Files.isReadable(filePath)) {
                    "Multipart file not readable: $value"
                }
                val fileSize = java.nio.file.Files.size(filePath)
                check(fileSize <= MAX_MULTIPART_FILE_BYTES) {
                    "Multipart file too large (${fileSize / 1024 / 1024} MB), max=${MAX_MULTIPART_FILE_BYTES / 1024 / 1024} MB: $value"
                }
                runningSize += fileSize
                check(runningSize <= MAX_MULTIPART_TOTAL_BYTES) {
                    "Multipart total body too large (>${MAX_MULTIPART_TOTAL_BYTES / 1024 / 1024} MB), please reduce file count"
                }

                val fileName = filePath.fileName.toString()
                byteArrays.add(
                    "Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\n"
                        .toByteArray(Charsets.UTF_8),
                )
                val mimeType = java.nio.file.Files.probeContentType(filePath) ?: "application/octet-stream"
                byteArrays.add("Content-Type: $mimeType\r\n\r\n".toByteArray(Charsets.UTF_8))
                byteArrays.add(java.nio.file.Files.readAllBytes(filePath))
                byteArrays.add(lineBreak)
            } else {
                byteArrays.add(
                    "Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8),
                )
                byteArrays.add(resolveVariables(value).toByteArray(Charsets.UTF_8))
                byteArrays.add(lineBreak)
            }
        }
        byteArrays.add("--$boundary--\r\n".toByteArray(Charsets.UTF_8))

        val totalSize = byteArrays.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (arr in byteArrays) {
            System.arraycopy(arr, 0, result, offset, arr.size)
            offset += arr.size
        }

        return HttpRequest.BodyPublishers.ofByteArray(result) to "multipart/form-data; boundary=$boundary"
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

data class ResponseBodyData(
    val text: String,
    val contentType: String,
    val truncated: Boolean,
)

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
