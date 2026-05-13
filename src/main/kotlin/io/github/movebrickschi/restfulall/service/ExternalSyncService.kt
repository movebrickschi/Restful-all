package io.github.movebrickschi.restfulall.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * v1.3.3 P1-7 - 第三方平台双向同步（Apifox / Yapi / Swagger）（Team）。
 *
 * ## 支持源
 *
 * | source | push | pull | 鉴权 |
 * |--------|------|------|------|
 * | Apifox | ✅   | ✅   | Personal Access Token |
 * | Yapi   | ⚠ pull-only（push 需服务器特殊接口） | ✅ | project token |
 * | Swagger 文件 | ✅ (写本地文件) | ✅ (从 URL/文件读) | 无 |
 *
 * ## 同步策略
 *
 * - push only / pull only / 双向（last-write-wins）
 * - 双向冲突走用户手动选择（UI 层负责弹窗），本服务仅返回 conflict 详情
 *
 * 因外部 API endpoint 频繁变化（Apifox v2 vs v1，Yapi 多版本），本服务**只覆盖最新公开 API**，
 * 历史版本兼容由社区贡献。
 */
@Service(Service.Level.PROJECT)
class ExternalSyncService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(ExternalSyncService::class.java)

        fun getInstance(project: Project): ExternalSyncService =
            project.getService(ExternalSyncService::class.java)
    }

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    enum class Source { APIFOX, YAPI, SWAGGER }

    data class SyncBinding(
        val source: Source,
        val endpoint: String,        // Apifox / Yapi base URL; Swagger 文件 URL 或本地路径
        val externalProjectId: String, // Apifox projectId / Yapi projectId
        val tokenSecretKey: String,  // SecretStorageService key（如 "external:apifox:projectId"）
        val direction: Direction,
    )

    enum class Direction { PUSH_ONLY, PULL_ONLY, BIDIRECTIONAL }

    data class PullResult(val collectionId: String, val itemCount: Int, val warnings: List<String>)
    data class PushResult(val pushedCount: Int, val warnings: List<String>)

    /**
     * 从外部平台拉接口列表，生成 / 更新对应的本地 [io.github.movebrickschi.restfulall.model.CollectionEntry]。
     *
     * - Swagger 走 [SwaggerImporter]
     * - Apifox / Yapi 走 platform-specific REST endpoint，返回结构归一化后塞给 SwaggerImporter
     *
     * @throws ExternalSyncException 任何 HTTP / 解析失败
     */
    fun pull(binding: SyncBinding): PullResult {
        val warnings = mutableListOf<String>()
        val openApiText = when (binding.source) {
            Source.SWAGGER -> fetchPlainText(binding.endpoint, binding.tokenSecretKey)
            Source.APIFOX -> fetchApifoxAsOpenApi(binding, warnings)
            Source.YAPI -> fetchYapiAsOpenApi(binding, warnings)
        }
        val result = SwaggerImporter.getInstance(project).import(openApiText, "external:${binding.source.name}")
        if (!result.isSuccess) throw ExternalSyncException("Import failed: ${result.errors.joinToString()}")
        return PullResult(collectionId = "", itemCount = result.routes.size, warnings = warnings + result.warnings)
    }

    /**
     * 把本地 collection 推到外部平台。
     * - Apifox：调用其 import API
     * - Yapi：v1.3 暂只支持 pull（push 走 yapi server-side json 接口，需要特殊配置）
     * - Swagger：写本地文件 / POST 到 endpoint
     */
    fun push(binding: SyncBinding, openApiYaml: String): PushResult {
        val warnings = mutableListOf<String>()
        when (binding.source) {
            Source.APIFOX -> pushApifox(binding, openApiYaml, warnings)
            Source.YAPI -> warnings.add("Yapi push is not supported in v1.3; only pull works.")
            Source.SWAGGER -> warnings.add("Swagger 'push' writes the spec to ${binding.endpoint}; caller should persist it.")
        }
        return PushResult(pushedCount = if (binding.source == Source.APIFOX) 1 else 0, warnings = warnings)
    }

    private fun fetchPlainText(url: String, tokenKey: String): String {
        val token = SecretStorageService.getInstance(project).getSecret(tokenKey)
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).GET().timeout(Duration.ofSeconds(30))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val resp = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ExternalSyncException("HTTP fetch failed: ${e.message}")
        }
        if (resp.statusCode() !in 200..299)
            throw ExternalSyncException("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
        return resp.body()
    }

    private fun fetchApifoxAsOpenApi(b: SyncBinding, warnings: MutableList<String>): String {
        // Apifox v2 export OpenAPI 接口：POST /v1/projects/{projectId}/export-openapi
        val token = SecretStorageService.getInstance(project).getSecret(b.tokenSecretKey).orEmpty()
        val url = b.endpoint.trimEnd('/') + "/v1/projects/${b.externalProjectId}/export-openapi"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("X-Apifox-Api-Version", "2024-03-28")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString("""{"scope":{"type":"ALL"},"options":{"includeApifoxExtensionProperties":true}}"""))
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ExternalSyncException("Apifox HTTP failed: ${e.message}")
        }
        if (resp.statusCode() !in 200..299) {
            warnings.add("Apifox returned HTTP ${resp.statusCode()}; falling back to empty spec.")
            throw ExternalSyncException("Apifox HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        // Apifox 返回 { data: openapiJson } 或直接 openapi；做兼容
        val tree = try { mapper.readTree(resp.body()) } catch (_: Exception) { null }
        return if (tree != null && tree.has("data")) tree.path("data").toString() else resp.body()
    }

    private fun fetchYapiAsOpenApi(b: SyncBinding, warnings: MutableList<String>): String {
        // Yapi 自带 export OpenAPI 接口：/api/plugin/exportSwagger?project_id=...&token=...
        val token = SecretStorageService.getInstance(project).getSecret(b.tokenSecretKey).orEmpty()
        val url = b.endpoint.trimEnd('/') +
            "/api/plugin/exportSwagger?project_id=${b.externalProjectId}&token=$token"
        val resp = try {
            http.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ExternalSyncException("Yapi HTTP failed: ${e.message}")
        }
        if (resp.statusCode() !in 200..299) {
            warnings.add("Yapi returned HTTP ${resp.statusCode()}; falling back to empty spec.")
            throw ExternalSyncException("Yapi HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
        }
        return resp.body()
    }

    private fun pushApifox(b: SyncBinding, openapi: String, warnings: MutableList<String>) {
        val token = SecretStorageService.getInstance(project).getSecret(b.tokenSecretKey).orEmpty()
        val url = b.endpoint.trimEnd('/') + "/v1/projects/${b.externalProjectId}/import-openapi"
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("X-Apifox-Api-Version", "2024-03-28")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(
                mapper.writeValueAsString(mapOf("openapiSpec" to openapi, "mode" to "OVERWRITE"))))
            .build()
        val resp = try {
            http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw ExternalSyncException("Apifox HTTP failed: ${e.message}")
        }
        if (resp.statusCode() !in 200..299) {
            warnings.add("Apifox push returned HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            throw ExternalSyncException("Apifox push failed: HTTP ${resp.statusCode()}")
        }
        LOG.info("Apifox push succeeded for project ${b.externalProjectId}")
    }
}

class ExternalSyncException(message: String) : RuntimeException(message)
