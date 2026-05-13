package io.github.movebrickschi.restfulall.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.CollectionEntry
import io.github.movebrickschi.restfulall.model.EnvironmentEntry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * v1.3.3 P1-4 / P1-5 - Workspace 云同步 + 团队共享（Team）。
 *
 * ## 设计要点
 *
 * - **客户端接口完整**：push / pull / share 三组 API；走标准 REST 后端契约（详见 PRD §8.4）。
 * - **不绑定特定后端**：默认实现走配置的 baseUrl + /v1/sync/ 路径；用户/团队可自行部署 sync
 *   server（参考 `docs/v1.3-roadmap.md` §六回滚预案），也可以接入 SaaS endpoint。
 * - **凭据安全**：accountToken 走 [SecretStorageService.syncKey]，不落明文 XML。
 * - **未配置时优雅降级**：调 push/pull 抛 [SyncException.NotConfigured]，UI 层引导用户进入设置。
 *
 * ## REST 契约（与 PRD §8.4 对齐）
 *
 * ```
 * POST /v1/sync/push      { workspaceId, snapshot, baseRev } -> { newRev, conflicts? }
 * GET  /v1/sync/pull?wId=&sinceRev=                                    -> { snapshot, currentRev }
 * POST /v1/sync/share     { workspaceId, collectionId, memberIds[] }   -> { sharedAt }
 * POST /v1/sync/unshare   { workspaceId, collectionId, memberIds[] }   -> { unsharedAt }
 * ```
 */
@Service(Service.Level.PROJECT)
@State(name = "RestfulAll.WorkspaceSync", storages = [Storage("restful-all-sync.xml")])
class WorkspaceSyncService(private val project: Project) : PersistentStateComponent<WorkspaceSyncService.Config> {

    data class Config(
        var baseUrl: String = "",
        var workspaceId: String = "",
        var accountId: String = "",
        var lastSyncedRev: Long = 0,
        var syncCollections: Boolean = true,
        var syncEnvironments: Boolean = true,
        var syncHistory: Boolean = false,
    )

    @Volatile
    private var config: Config = Config()

    private val mapper: ObjectMapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    companion object {
        private val LOG = Logger.getInstance(WorkspaceSyncService::class.java)

        fun getInstance(project: Project): WorkspaceSyncService =
            project.getService(WorkspaceSyncService::class.java)
    }

    override fun getState(): Config = config

    override fun loadState(state: Config) { config = state }

    fun getConfig(): Config = config

    fun updateConfig(newConfig: Config) { config = newConfig }

    fun setAccountToken(plain: String) {
        val secrets = SecretStorageService.getInstance(project)
        secrets.setSecret(SecretStorageService.syncKey(config.accountId.ifBlank { "default" }), plain)
    }

    fun getAccountToken(): String? = SecretStorageService.getInstance(project)
        .getSecret(SecretStorageService.syncKey(config.accountId.ifBlank { "default" }))

    fun isConfigured(): Boolean = config.baseUrl.isNotBlank() && getAccountToken()?.isNotBlank() == true

    /**
     * 推送本地快照到云端。**调用方应在 pooled thread 上调用**。
     *
     * 失败抛 [SyncException]；成功更新 [Config.lastSyncedRev]。
     */
    fun push(): PushResult {
        requireConfigured()
        val snapshot = SyncSnapshot(
            collections = if (config.syncCollections) CollectionService.getInstance(project).list() else emptyList(),
            environments = if (config.syncEnvironments) EnvironmentService.getInstance(project).listEnvironments() else emptyList(),
        )
        val payload = mapper.writeValueAsString(
            mapOf(
                "workspaceId" to config.workspaceId,
                "snapshot" to snapshot,
                "baseRev" to config.lastSyncedRev,
            )
        )
        val resp = doRequest("POST", "/v1/sync/push", payload)
        if (resp.statusCode() == 409) throw SyncException.RevisionConflict("Server rev > local; pull first")
        if (resp.statusCode() !in 200..299) throw SyncException.HttpFailure(resp.statusCode(), resp.body())
        val json = mapper.readTree(resp.body())
        val newRev = json.path("newRev").asLong(0)
        if (newRev > 0) config.lastSyncedRev = newRev
        return PushResult(newRev = newRev)
    }

    /**
     * 从云端拉取自 [Config.lastSyncedRev] 起的最新快照，并应用到本地服务。
     *
     * 本地未保存的修改会**被云端覆盖**；UI 层应在调用前提示用户确认。
     */
    fun pull(): PullResult {
        requireConfigured()
        val url = "/v1/sync/pull?workspaceId=${config.workspaceId}&sinceRev=${config.lastSyncedRev}"
        val resp = doRequest("GET", url, null)
        if (resp.statusCode() !in 200..299) throw SyncException.HttpFailure(resp.statusCode(), resp.body())
        val json = mapper.readTree(resp.body())
        val snapshot = mapper.treeToValue(json.path("snapshot"), SyncSnapshot::class.java)
        val rev = json.path("currentRev").asLong(0)
        if (rev > 0) config.lastSyncedRev = rev

        // 应用到本地（覆盖式，简化版；正式版需 3-way merge）
        val collSvc = CollectionService.getInstance(project)
        for (c in snapshot.collections) collSvc.upsert(c)
        val envSvc = EnvironmentService.getInstance(project)
        for (e in snapshot.environments) envSvc.upsert(e)
        return PullResult(currentRev = rev, applied = snapshot.collections.size + snapshot.environments.size)
    }

    /**
     * v1.3.3 P1-5 - 分享 collection 给团队成员。
     *
     * v1.3 仅做"读权限分享"（成员可见但不可编辑，与 PRD §6.4 一致）；具体策略由 sync server 实现。
     */
    fun share(collectionId: String, memberIds: List<String>): ShareResult {
        requireConfigured()
        val payload = mapper.writeValueAsString(
            mapOf(
                "workspaceId" to config.workspaceId,
                "collectionId" to collectionId,
                "memberIds" to memberIds,
            )
        )
        val resp = doRequest("POST", "/v1/sync/share", payload)
        if (resp.statusCode() !in 200..299) throw SyncException.HttpFailure(resp.statusCode(), resp.body())
        val json = mapper.readTree(resp.body())
        return ShareResult(sharedAt = json.path("sharedAt").asLong(System.currentTimeMillis()))
    }

    fun unshare(collectionId: String, memberIds: List<String>): ShareResult {
        requireConfigured()
        val payload = mapper.writeValueAsString(
            mapOf(
                "workspaceId" to config.workspaceId,
                "collectionId" to collectionId,
                "memberIds" to memberIds,
            )
        )
        val resp = doRequest("POST", "/v1/sync/unshare", payload)
        if (resp.statusCode() !in 200..299) throw SyncException.HttpFailure(resp.statusCode(), resp.body())
        return ShareResult(sharedAt = System.currentTimeMillis())
    }

    private fun requireConfigured() {
        if (!isConfigured()) {
            throw SyncException.NotConfigured(
                "Sync is not configured. Set baseUrl + workspaceId + accountToken first.",
            )
        }
    }

    private fun doRequest(method: String, path: String, jsonBody: String?): HttpResponse<String> {
        val token = getAccountToken().orEmpty()
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(config.baseUrl.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
        val req = when (method.uppercase()) {
            "GET" -> builder.GET().build()
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody ?: "", Charsets.UTF_8)).build()
            else -> builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody ?: "", Charsets.UTF_8)).build()
        }
        return try {
            http.send(req, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        } catch (e: Exception) {
            LOG.warn("WorkspaceSync HTTP failed: ${e.message}", e)
            throw SyncException.HttpFailure(-1, e.message ?: "I/O error")
        }
    }

    data class SyncSnapshot(
        val collections: List<CollectionEntry> = emptyList(),
        val environments: List<EnvironmentEntry> = emptyList(),
    )

    data class PushResult(val newRev: Long)
    data class PullResult(val currentRev: Long, val applied: Int)
    data class ShareResult(val sharedAt: Long)
}

/**
 * v1.3.3 - Workspace sync 抛出的错误类型。
 */
sealed class SyncException(message: String) : RuntimeException(message) {
    class NotConfigured(message: String) : SyncException(message)
    class RevisionConflict(message: String) : SyncException(message)
    class HttpFailure(val status: Int, val responseBody: String) :
        SyncException("HTTP $status: ${responseBody.take(300)}")
}
