package io.github.movebrickschi.restfulall.model

/**
 * v1.3.1 P0-1 - AI 配置（应用级 / 项目级）。
 *
 * - `baseUrl`：兼容 OpenAI Chat Completion 的 endpoint 根路径（如 `https://api.openai.com/v1`）。
 *   留空使用默认 `https://api.openai.com/v1`。
 * - `model`：调用使用的模型 ID（`gpt-4o-mini`、`claude-3-haiku`、本地 `llama3` 等）。
 * - `apiKey`：API Key 引用键（真实值走 [io.github.movebrickschi.restfulall.service.SecretStorageService]，
 *   按 `restful-all:ai:<provider>` 命名空间存储，不落明文 XML）。
 * - `provider`：仅用于命名空间区分（openai / anthropic / azure / custom）。
 *
 * 该对象**不存 API Key 明文**；UI 层在写入时通过 SecretStorageService 加密，仅在内存中传递。
 */
data class AiConfig(
    var provider: String = "openai",
    var baseUrl: String = "https://api.openai.com/v1",
    var model: String = "gpt-4o-mini",
    /** 不持久化明文；本字段在内存请求时由 UI 从 SecretStorageService 解密填入。*/
    @Transient
    var apiKeyInMemory: String = "",
    /** 透传到 HTTP 请求的可选超时秒数（默认 60s）。*/
    var timeoutSeconds: Long = 60,
) {
    fun secretRefKey(): String = "ai:$provider"

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKeyInMemory.isNotBlank()
}
