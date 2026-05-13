package io.github.movebrickschi.restfulall.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.CollectionEntry
import io.github.movebrickschi.restfulall.model.CollectionItem
import io.github.movebrickschi.restfulall.model.EnvVariable
import io.github.movebrickschi.restfulall.model.EnvironmentEntry
import io.github.movebrickschi.restfulall.model.ParamEntry
import io.github.movebrickschi.restfulall.model.RequestSpecData

/**
 * v1.3.1 F12 - Postman / Apifox Collection 导入器。
 *
 * ## 支持的格式
 *
 * - **Postman Collection v2.1**：`info.schema` 含 `v2.1.0`
 * - **Postman Collection v2.0**：`info.schema` 含 `v2.0.0`
 * - **Apifox 导出**：Apifox 默认导出符合 Postman v2.1，本类按 v2.1 兜底
 *
 * ## 转换规则
 *
 * - 顶层 `info.name` → [CollectionEntry.name]
 * - `item[]` 中带 `request` 的条目 → [CollectionItem]
 * - `item[]` 中只带 `item[]`（嵌套 folder） → 子 [CollectionEntry]（CollectionService 已支持
 *   5 层深度，超过则降级为同级 prefix-name）
 * - `request.method` → [RequestSpecData.method]
 * - `request.url`：优先 `raw`，缺失则按 `host + path + query` 拼装
 * - `request.header[]` → [RequestSpecData.headers]
 * - `request.body.mode`：
 *   - `raw` → `bodyType = json|xml|raw`（按 `options.raw.language` 推断），`bodyContent = raw`
 *   - `formdata` → `bodyType = form-data`，每条转 ParamEntry
 *   - `urlencoded` → `bodyType = x-www-form-urlencoded`
 *   - `file` / `graphql` → 落到 raw 文本，warning 中标注（v1.3 不解析 GraphQL）
 * - `request.auth`：仅记录到 warnings，提示用户走 F5 Auth 标签页配置
 * - `event[]`（pre-request / test scripts）：丢弃但 warning 标注 "scripts not executed in v1.3"
 *
 * ## Environment 导入
 *
 * Postman Environment JSON 形如：
 * ```
 * { "name": "Dev", "values": [ {"key": "x", "value": "y", "type": "secret"} ] }
 * ```
 * 转为 F1 [EnvironmentEntry]；`type=secret` 标 [EnvVariable.secret] = true，
 * value 会被 [EnvironmentService.upsert] 接管走 [SecretStorageService] 加密存储。
 */
@Service(Service.Level.PROJECT)
class PostmanImporter(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(PostmanImporter::class.java)

        fun getInstance(project: Project): PostmanImporter =
            project.getService(PostmanImporter::class.java)
    }

    private val mapper: ObjectMapper = ObjectMapper()

    /**
     * 解析并导入 Postman Collection JSON。
     *
     * - 成功：把所有 collection / sub-folder / items 一次性写入 [CollectionService]
     * - 失败：返回 [ImportResult.error]，不修改任何持久化状态
     *
     * 调用线程：可在任意线程（写 CollectionService 时若不在 EDT，调用方需自行处理 UI 刷新）。
     */
    fun importCollection(jsonText: String, sourceName: String = "postman.json"): ImportResult {
        val warnings = mutableListOf<String>()
        val root = try {
            mapper.readTree(jsonText)
        } catch (e: Exception) {
            LOG.warn("PostmanImporter: failed to parse JSON from $sourceName", e)
            return ImportResult.error("Invalid JSON: ${e.message}")
        }

        if (!isLikelyPostmanCollection(root)) {
            return ImportResult.error(
                "Not a valid Postman Collection: missing info.schema or item[]",
            )
        }

        val collectionName = root.path("info").path("name").asText()
            .takeIf { it.isNotBlank() } ?: "Imported Collection"

        val service = CollectionService.getInstance(project)
        val rootEntry = CollectionEntry(name = collectionName)
        try {
            service.upsert(rootEntry)
        } catch (e: Exception) {
            return ImportResult.error("Create root collection failed: ${e.message}")
        }

        val items = root.path("item")
        if (!items.isArray) {
            return ImportResult(
                success = true,
                collectionId = rootEntry.id,
                collectionName = collectionName,
                totalItems = 0,
                warnings = listOf("Empty item[] in collection"),
            )
        }

        val total = importItems(service, rootEntry, items, depth = 0, warnings = warnings)
        return ImportResult(
            success = true,
            collectionId = rootEntry.id,
            collectionName = collectionName,
            totalItems = total,
            warnings = warnings.toList(),
        )
    }

    /**
     * 解析并导入 Postman Environment JSON。
     *
     * - 成功：把变量写入 F1 [EnvironmentService]，并把当前环境切换为新导入的
     * - 失败：返回 null（caller 决定如何提示）
     */
    fun importEnvironment(jsonText: String): ImportEnvResult? {
        val root = try {
            mapper.readTree(jsonText)
        } catch (e: Exception) {
            LOG.warn("PostmanImporter.importEnvironment: invalid JSON", e)
            return null
        }
        val name = root.path("name").asText().takeIf { it.isNotBlank() } ?: return null
        val values = root.path("values").takeIf { it.isArray } ?: return null

        val vars = mutableListOf<EnvVariable>()
        for (v in values) {
            val key = v.path("key").asText()
            if (key.isBlank()) continue
            val value = v.path("value").asText()
            val secret = v.path("type").asText() == "secret"
            vars.add(EnvVariable(key = key, value = value, secret = secret))
        }

        val envService = EnvironmentService.getInstance(project)
        val entry = EnvironmentEntry(name = name, variables = vars)
        envService.upsert(entry)
        envService.setActive(entry.id)
        return ImportEnvResult(entry.id, name, vars.size)
    }

    private fun importItems(
        service: CollectionService,
        parent: CollectionEntry,
        itemsNode: JsonNode,
        depth: Int,
        warnings: MutableList<String>,
    ): Int {
        var count = 0
        for (node in itemsNode) {
            val hasRequest = node.has("request")
            val hasNestedItems = node.path("item").isArray
            val itemName = node.path("name").asText().takeIf { it.isNotBlank() } ?: "Untitled"

            when {
                hasRequest -> {
                    val spec = convertRequest(node.path("request"), warnings, itemName)
                    val event = node.path("event")
                    if (event.isArray && event.size() > 0) {
                        warnings.add("Item '$itemName' contains scripts (pre-request/test); v1.3 will not execute them.")
                    }
                    val item = CollectionItem(name = itemName, spec = spec)
                    try {
                        service.addItem(parent.id, item)
                        count++
                    } catch (e: Exception) {
                        warnings.add("Add item '$itemName' failed: ${e.message}")
                    }
                }

                hasNestedItems -> {
                    if (depth + 1 >= CollectionService.MAX_DEPTH) {
                        warnings.add(
                            "Folder '$itemName' nests beyond max depth ${CollectionService.MAX_DEPTH}; " +
                                "flattening its items into '${parent.name}'.",
                        )
                        count += importItems(service, parent, node.path("item"), depth, warnings)
                    } else {
                        val child = CollectionEntry(name = itemName, parentId = parent.id)
                        try {
                            service.upsert(child)
                            count += importItems(service, child, node.path("item"), depth + 1, warnings)
                        } catch (e: Exception) {
                            warnings.add("Create sub collection '$itemName' failed: ${e.message}")
                        }
                    }
                }

                else -> {
                    warnings.add("Item '$itemName' has neither 'request' nor nested 'item'; skipped.")
                }
            }
        }
        return count
    }

    /**
     * 把 Postman request JSON 转为 [RequestSpecData]。
     *
     * Postman 的 URL 既可能是字符串也可能是对象；method 一般是字符串；
     * body 有 mode + 对应内容字段；header 是 [{ key, value, disabled? }]。
     */
    private fun convertRequest(
        req: JsonNode,
        warnings: MutableList<String>,
        itemName: String,
    ): RequestSpecData {
        val method = req.path("method").asText("GET").uppercase()

        val urlNode = req.path("url")
        val url = when {
            urlNode.isMissingNode -> ""
            urlNode.isTextual -> urlNode.asText()
            urlNode.isObject -> {
                val raw = urlNode.path("raw").asText()
                if (raw.isNotBlank()) raw else buildUrlFromParts(urlNode)
            }
            else -> ""
        }

        val headers = mutableListOf<ParamEntry>()
        for (h in req.path("header").orEmptyArray()) {
            val name = h.path("key").asText()
            if (name.isBlank()) continue
            headers.add(
                ParamEntry(
                    enabled = !h.path("disabled").asBoolean(false),
                    name = name,
                    value = h.path("value").asText(),
                )
            )
        }

        val queryParams = mutableListOf<ParamEntry>()
        if (urlNode.isObject) {
            for (q in urlNode.path("query").orEmptyArray()) {
                val name = q.path("key").asText()
                if (name.isBlank()) continue
                queryParams.add(
                    ParamEntry(
                        enabled = !q.path("disabled").asBoolean(false),
                        name = name,
                        value = q.path("value").asText(),
                    )
                )
            }
        }

        val auth = req.path("auth")
        if (!auth.isMissingNode && auth.size() > 0) {
            warnings.add(
                "Item '$itemName' carries an auth block (${auth.path("type").asText("?")}); " +
                    "configure it manually under the Auth tab (F5).",
            )
        }

        val bodyNode = req.path("body")
        val (bodyType, bodyContent, formParams) = when (bodyNode.path("mode").asText()) {
            "raw" -> {
                val language = bodyNode.path("options").path("raw").path("language").asText().lowercase()
                val resolvedType = when (language) {
                    "json"          -> "json"
                    "xml"           -> "xml"
                    "javascript", "html", "text" -> "raw"
                    else            -> "raw"
                }
                Triple(resolvedType, bodyNode.path("raw").asText(), mutableListOf<ParamEntry>())
            }
            "formdata" -> {
                val list = mutableListOf<ParamEntry>()
                for (f in bodyNode.path("formdata").orEmptyArray()) {
                    val name = f.path("key").asText()
                    if (name.isBlank()) continue
                    if (f.path("type").asText() == "file") {
                        warnings.add("Item '$itemName' form-data field '$name' is a file upload; pick a local path manually.")
                        continue
                    }
                    list.add(
                        ParamEntry(
                            enabled = !f.path("disabled").asBoolean(false),
                            name = name,
                            value = f.path("value").asText(),
                        )
                    )
                }
                Triple("form-data", "", list)
            }
            "urlencoded" -> {
                val list = mutableListOf<ParamEntry>()
                for (u in bodyNode.path("urlencoded").orEmptyArray()) {
                    val name = u.path("key").asText()
                    if (name.isBlank()) continue
                    list.add(
                        ParamEntry(
                            enabled = !u.path("disabled").asBoolean(false),
                            name = name,
                            value = u.path("value").asText(),
                        )
                    )
                }
                Triple("x-www-form-urlencoded", "", list)
            }
            "file", "graphql" -> {
                warnings.add(
                    "Item '$itemName' body mode '${bodyNode.path("mode").asText()}' is not fully supported in v1.3.",
                )
                Triple("raw", bodyNode.toString(), mutableListOf())
            }
            else -> Triple("none", "", mutableListOf<ParamEntry>())
        }

        return RequestSpecData(
            method = method,
            url = url,
            queryParams = queryParams,
            headers = headers,
            bodyType = bodyType,
            bodyContent = bodyContent,
            formParams = formParams,
        )
    }

    private fun buildUrlFromParts(urlNode: JsonNode): String {
        val protocol = urlNode.path("protocol").asText("http")
        val host = urlNode.path("host").orEmptyArray()
            .joinToString(".") { it.asText() }
        val port = urlNode.path("port").asText("")
        val path = urlNode.path("path").orEmptyArray()
            .joinToString("/") { it.asText() }
        val portSegment = if (port.isNotBlank()) ":$port" else ""
        val pathSegment = if (path.startsWith("/")) path else "/$path"
        return if (host.isBlank()) pathSegment else "$protocol://$host$portSegment$pathSegment"
    }

    private fun isLikelyPostmanCollection(root: JsonNode): Boolean {
        if (!root.isObject) return false
        val schema = root.path("info").path("schema").asText()
        val hasItems = root.path("item").isArray
        return hasItems && (
            schema.contains("schema.getpostman.com") ||
                schema.contains("schema.apifox.com") ||
                root.path("info").has("name")
            )
    }

    private fun JsonNode.orEmptyArray(): JsonNode = if (this.isArray) this else mapper.createArrayNode()

    data class ImportResult(
        val success: Boolean,
        val collectionId: String = "",
        val collectionName: String = "",
        val totalItems: Int = 0,
        val warnings: List<String> = emptyList(),
        val error: String? = null,
    ) {
        companion object {
            fun error(msg: String): ImportResult = ImportResult(success = false, error = msg)
        }
    }

    data class ImportEnvResult(
        val envId: String,
        val envName: String,
        val varCount: Int,
    )
}
