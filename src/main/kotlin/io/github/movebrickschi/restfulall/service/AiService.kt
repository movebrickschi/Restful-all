package io.github.movebrickschi.restfulall.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.AiConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * v1.3.1 P0-1 / P0-2 / P0-3 - AI 服务统一入口。
 *
 * ## 接口形态
 *
 * - 兼容 **OpenAI Chat Completion API** (`POST /v1/chat/completions`)
 * - 支持自托管：用户在设置中配 baseUrl（如 LM Studio / Ollama 的 OpenAI-compatible endpoint）
 * - 不引入 Anthropic / Gemini 等专属 SDK，依赖 [java.net.http.HttpClient]（IDEA bundled JDK 21+ 自带）
 *
 * ## 三个用例
 *
 * - [fillParameters]（P0-1）：根据接口注释 + 字段类型推断合理的 body / param 值
 * - [diagnose]（P0-2）：根据请求 + 响应分析非 2xx 失败原因 + 修复建议
 * - [generateTestCases]（P0-3）：根据接口元数据生成 5~10 个边界 / 异常用例
 *
 * 实现侧仅暴露**阻塞** API：调用方应在 `executeOnPooledThread` 上调用，
 * 避免 IDE EDT 卡顿。返回的字符串是模型最终回复（content），错误抛 [AiException]。
 *
 * ## quota / Pro 集成
 *
 * - Free 用户：每日 [AiUsageQuota.DAILY_FREE_QUOTA] 次试用，超限 [AiException.QuotaExceeded]
 * - Pro 用户：由 [ProFeatureGate] 在 UI 层拦截 Free 弹窗，AiService 本身不做档位判断
 *   （这层在外侧守卫，避免 service 二次 require Project）
 */
@Service(Service.Level.PROJECT)
@State(name = "RestfulAll.AiConfig", storages = [Storage("restful-all-ai-config.xml")])
class AiService(private val project: Project) : PersistentStateComponent<AiConfig> {

    @Volatile
    private var config: AiConfig = AiConfig()

    private val mapper: ObjectMapper = ObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    companion object {
        private val LOG = Logger.getInstance(AiService::class.java)

        fun getInstance(project: Project): AiService =
            project.getService(AiService::class.java)
    }

    override fun getState(): AiConfig = config

    override fun loadState(state: AiConfig) {
        config = state
    }

    fun getConfig(): AiConfig = config

    /**
     * 更新非密 fields；apiKeyInMemory 由 [updateApiKey] 单独处理（走 PasswordSafe）。
     */
    fun updateConfig(newConfig: AiConfig) {
        val merged = newConfig.copy(apiKeyInMemory = config.apiKeyInMemory)
        config = merged
    }

    /**
     * 更新 API Key，写入 [SecretStorageService]。空字符串等价于 [removeApiKey]。
     */
    fun updateApiKey(plainKey: String) {
        val secrets = SecretStorageService.getInstance(project)
        val nsKey = SecretStorageService.aiKey(config.provider)
        if (plainKey.isBlank()) {
            secrets.removeSecret(nsKey)
        } else {
            secrets.setSecret(nsKey, plainKey)
        }
        config.apiKeyInMemory = plainKey
    }

    /**
     * 启动时 / 第一次调用前装载 Key 到内存。
     *
     * 仅在 [config.apiKeyInMemory] 为空时去读 PasswordSafe，避免每次调 LLM 都过一次 Keychain。
     */
    fun ensureKeyLoaded() {
        if (config.apiKeyInMemory.isNotBlank()) return
        val secrets = SecretStorageService.getInstance(project)
        val nsKey = SecretStorageService.aiKey(config.provider)
        config.apiKeyInMemory = secrets.getSecret(nsKey).orEmpty()
    }

    /**
     * P0-1 AI 智能参数填充。
     *
     * @param interfaceDescription 接口说明（来自 javadoc / OpenAPI 描述）；空则用 placeholder
     * @param schemaHint Body / Query 的 JSON Schema 或者 类型描述（如 "name: string, age: int"）
     * @param existingValues 已有变量提示（k → v），用于让 AI 复用现有 token / id
     * @return JSON 字符串（直接可写回 Body 编辑器）
     */
    fun fillParameters(
        interfaceDescription: String,
        schemaHint: String,
        existingValues: Map<String, String> = emptyMap(),
    ): String {
        val sysPrompt = """
            你是 REST API 调试助手。根据用户提供的接口说明和字段信息，生成一个合理的 JSON 示例请求体。
            要求：
            1. 字段类型必须正确（string/number/boolean/array/object）
            2. 字段值应贴近真实业务场景，不要全 0 或空字符串
            3. 如果用户提示了已有变量（如 token），优先复用
            4. 只输出纯 JSON 对象，不要任何解释 / Markdown / code fence
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("【接口说明】")
            appendLine(interfaceDescription.ifBlank { "(无描述)" })
            appendLine()
            appendLine("【字段提示】")
            appendLine(schemaHint.ifBlank { "(无 schema 提示)" })
            if (existingValues.isNotEmpty()) {
                appendLine()
                appendLine("【已有变量】")
                for ((k, v) in existingValues) appendLine("$k = $v")
            }
        }

        return chat(sysPrompt, userPrompt)
    }

    /**
     * P0-2 AI 接口诊断。
     */
    fun diagnose(
        requestSummary: String,
        responseStatus: Int,
        responseBody: String,
    ): String {
        val sysPrompt = """
            你是 REST API 调试助手。用户的接口返回非 2xx，请用简洁中文分析：
            1. 失败可能原因（参数错误 / 鉴权 / 业务限制 / 服务不可用…）
            2. 检查列表（请求侧可立即验证的 3~5 条）
            3. 修复建议（最小改动）
            禁止瞎猜接口业务含义；不确定时明确说"需要查阅接口文档"。
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("【请求摘要】")
            appendLine(requestSummary)
            appendLine()
            appendLine("【响应状态】$responseStatus")
            appendLine("【响应体】")
            appendLine(responseBody.take(4000))
        }

        return chat(sysPrompt, userPrompt)
    }

    /**
     * P0-3 AI 生成测试用例。
     *
     * @return JSON 数组字符串，每项含 `name` + `body` + `expectedStatus`
     */
    fun generateTestCases(
        interfaceDescription: String,
        sampleRequest: String,
    ): String {
        val sysPrompt = """
            你是 REST API 测试用例生成助手。根据接口说明 + 一个正向请求样例，生成 5~10 个测试用例。
            必须覆盖：正向 / 边界值 / 非法参数 / 鉴权失败 / 空 / 超长。
            输出 JSON 数组，每项格式：
              { "name": "...", "body": {...}, "expectedStatus": 200 }
            不要任何解释 / Markdown / code fence。
        """.trimIndent()

        val userPrompt = buildString {
            appendLine("【接口说明】")
            appendLine(interfaceDescription.ifBlank { "(无描述)" })
            appendLine()
            appendLine("【正向样例】")
            appendLine(sampleRequest.take(4000))
        }

        return chat(sysPrompt, userPrompt)
    }

    /**
     * 内部统一 Chat Completion 调用。
     *
     * - 自动加 quota 检查（throws QuotaExceeded）
     * - JSON 序列化使用 Jackson
     * - 失败抛 [AiException]，含 HTTP status / OpenAI error 段
     */
    private fun chat(systemPrompt: String, userPrompt: String): String {
        ensureKeyLoaded()
        if (!config.isConfigured) {
            throw AiException.NotConfigured("AI is not configured: missing baseUrl/model/apiKey")
        }

        val quota = AiUsageQuota.getInstance()
        val isPro = ProFeatureGate.isPro(project)
        if (!isPro && !quota.tryConsume()) {
            throw AiException.QuotaExceeded(
                "Daily free quota (${AiUsageQuota.DAILY_FREE_QUOTA}) used up. Upgrade to Pro for unlimited.",
            )
        }

        val body = mapper.writeValueAsString(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt),
                ),
                "temperature" to 0.3,
                "stream" to false,
            )
        )

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(config.timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${config.apiKeyInMemory}")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()

        val resp = try {
            httpClient.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            LOG.warn("AiService.chat HTTP failed url=$url", e)
            throw AiException.NetworkFailure("HTTP failed: ${e.message}")
        }

        if (resp.statusCode() !in 200..299) {
            LOG.warn("AiService.chat non-2xx: status=${resp.statusCode()} body=${resp.body().take(500)}")
            throw AiException.HttpError(resp.statusCode(), resp.body())
        }

        val json = try {
            mapper.readTree(resp.body())
        } catch (e: Exception) {
            throw AiException.MalformedResponse("Cannot parse LLM response as JSON: ${e.message}")
        }

        val content = json.path("choices").firstOrNull()
            ?.path("message")?.path("content")?.asText()
            ?: throw AiException.MalformedResponse("Missing choices[0].message.content")

        return content.trim().removeSurrounding("```json", "```").trim()
            .removeSurrounding("```", "```").trim()
    }
}

/**
 * v1.3.1 - AI Service 抛出的错误类型。调用方根据子类决定弹窗文案 / 引导。
 */
sealed class AiException(message: String) : RuntimeException(message) {
    class NotConfigured(message: String) : AiException(message)
    class QuotaExceeded(message: String) : AiException(message)
    class NetworkFailure(message: String) : AiException(message)
    class HttpError(val status: Int, val responseBody: String) :
        AiException("HTTP $status: ${responseBody.take(300)}")
    class MalformedResponse(message: String) : AiException(message)
}
