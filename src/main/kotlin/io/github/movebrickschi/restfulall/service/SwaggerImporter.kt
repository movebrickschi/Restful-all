package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import io.github.movebrickschi.restfulall.model.ExtractedFormParam
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.ExtractedParam
import io.github.movebrickschi.restfulall.model.FormFieldType
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.ParamLocation
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.model.stableId
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.parser.OpenAPIV3Parser
import io.swagger.v3.parser.core.models.ParseOptions

/**
 * F4: 把 OpenAPI 3.x YAML / JSON 转成虚拟 [RouteInfo] + 预解析好的 [ExtractedMethodParams]。
 *
 * ## 设计要点
 *
 * - **虚拟文件**：所有 operation 共用一个 [LightVirtualFile]，文件名 = 用户导入的原始文件名，
 *   `lineNumber` 用 operation 在解析序列里的全局递增 index，保证 [RouteInfo.dedupKey] 唯一
 * - **不抛异常**：任何解析失败 / 字段缺失都收进 [ImportResult.warnings] / [ImportResult.errors]，
 *   绝不把异常 throw 到 UI 线程
 * - **不持久化**：导入结果由调用方（通常是 [RouteService]）放进内存 ref；
 *   IDE 重启后丢失，符合本期 F4 的 scope（持久化是后续 PR 范围）
 *
 * ## 调用样例
 *
 * ```kotlin
 * val importer = SwaggerImporter.getInstance(project)
 * val result = importer.import(yamlText, "petstore.yaml")
 * if (result.errors.isNotEmpty()) {
 *     notify(result.errors.joinToString("\n"))
 * } else {
 *     RouteService.getInstance(project).addImportedRoutes(result.routes, result.params)
 * }
 * ```
 */
@Service(Service.Level.PROJECT)
class SwaggerImporter(@Suppress("unused") private val project: Project) {

    /**
     * @param routes 转换出的 [RouteInfo] 列表（按 spec 顺序，未排序）
     * @param params `stableId -> ExtractedMethodParams` 索引；写入 RouteService cache 后由
     *   [OpenApiParamExtractor] 按 routeInfo.stableId 反查
     * @param warnings 非致命问题（如某 operation 缺 summary）
     * @param errors 致命问题（如 spec 整体解析失败 → routes 为空）
     */
    data class ImportResult(
        val routes: List<RouteInfo>,
        val params: Map<String, ExtractedMethodParams>,
        val warnings: List<String>,
        val errors: List<String>,
    ) {
        val isSuccess: Boolean get() = routes.isNotEmpty() && errors.isEmpty()
    }

    /**
     * 解析 [spec]（YAML 或 JSON 文本）并返回 [ImportResult]。
     *
     * [sourceFileName] 用于生成虚拟文件名，并出现在 [RouteInfo.locationText] 中，
     * 方便用户在路由列表上区分多次导入。
     */
    fun import(spec: String, sourceFileName: String): ImportResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val openApi = try {
            parseSpec(spec, warnings, errors)
        } catch (e: Throwable) {
            LOG.warn("SwaggerImporter.parseSpec threw", e)
            errors += "parse failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            null
        }
        if (openApi == null || openApi.paths == null || openApi.paths.isEmpty()) {
            if (errors.isEmpty()) errors += "no paths found in spec"
            return ImportResult(emptyList(), emptyMap(), warnings, errors)
        }

        val virtualFile = createVirtualFile(sourceFileName, spec)
        val routes = mutableListOf<RouteInfo>()
        val paramsByStableId = mutableMapOf<String, ExtractedMethodParams>()
        var opIndex = 0

        for ((path, pathItem) in openApi.paths.entries) {
            if (pathItem == null) continue
            for ((method, operation) in operationsOf(pathItem)) {
                if (operation == null) continue
                val info = buildRouteInfo(method, path, operation, virtualFile, opIndex)
                val params = buildExtractedParams(pathItem, operation, warnings)
                routes += info
                paramsByStableId[info.stableId] = params
                opIndex++
            }
        }

        if (routes.isEmpty() && errors.isEmpty()) {
            errors += "spec parsed but contains no HTTP operations"
        }
        return ImportResult(routes, paramsByStableId, warnings, errors)
    }

    private fun parseSpec(
        spec: String,
        warnings: MutableList<String>,
        errors: MutableList<String>,
    ): OpenAPI? {
        val options = ParseOptions().apply {
            isResolve = true
            isResolveFully = false
            isFlatten = false
        }
        val result = OpenAPIV3Parser().readContents(spec, null, options)
        result.messages?.forEach { warnings += it }
        if (result.openAPI == null) {
            errors += "OpenAPI 3.x parser returned null"
        }
        return result.openAPI
    }

    private fun createVirtualFile(name: String, spec: String): VirtualFile {
        val safeName = name.ifBlank { "openapi.yaml" }
        return LightVirtualFile("<openapi-import>/$safeName", spec)
    }

    private fun operationsOf(pathItem: PathItem): List<Pair<HttpMethod, Operation?>> = listOf(
        HttpMethod.GET to pathItem.get,
        HttpMethod.POST to pathItem.post,
        HttpMethod.PUT to pathItem.put,
        HttpMethod.DELETE to pathItem.delete,
        HttpMethod.PATCH to pathItem.patch,
        HttpMethod.HEAD to pathItem.head,
        HttpMethod.OPTIONS to pathItem.options,
    )

    private fun buildRouteInfo(
        method: HttpMethod,
        path: String,
        operation: Operation,
        virtualFile: VirtualFile,
        opIndex: Int,
    ): RouteInfo {
        val operationId = operation.operationId.orEmpty().ifBlank { "${method.displayName.lowercase()}_$opIndex" }
        val tag = operation.tags?.firstOrNull().orEmpty()
        return RouteInfo(
            method = method,
            fullPath = path,
            className = tag.ifBlank { OPENAPI_DEFAULT_TAG },
            functionName = operationId,
            file = virtualFile,
            lineNumber = opIndex,
            framework = Framework.OPENAPI,
            packageName = OPENAPI_PACKAGE,
            routeGroupName = tag,
            routeName = operation.summary.orEmpty(),
            description = operation.description?.takeIf { it.isNotBlank() },
        )
    }

    private fun buildExtractedParams(
        pathItem: PathItem,
        operation: Operation,
        warnings: MutableList<String>,
    ): ExtractedMethodParams {
        val query = mutableListOf<ExtractedParam>()
        val pathP = mutableListOf<ExtractedParam>()
        val header = mutableListOf<ExtractedParam>()
        val cookie = mutableListOf<ExtractedParam>()

        val allParams = (pathItem.parameters.orEmpty() + operation.parameters.orEmpty())
            .distinctBy { "${it.`in`.orEmpty()}:${it.name.orEmpty()}" }

        for (p in allParams) {
            if (p.name.isNullOrBlank() || p.`in`.isNullOrBlank()) continue
            val extracted = ExtractedParam(p.name, mapLocation(p.`in`) ?: continue, sampleValueOf(p))
            when (extracted.location) {
                ParamLocation.QUERY -> query += extracted
                ParamLocation.PATH -> pathP += extracted
                ParamLocation.HEADER -> if (!isStandardHeader(extracted.name)) header += extracted
                ParamLocation.COOKIE -> cookie += extracted
                ParamLocation.BODY -> Unit
            }
        }

        val (bodyJson, formParams) = renderBody(operation.requestBody, warnings)

        return ExtractedMethodParams(
            queryParams = query,
            pathParams = pathP,
            headerParams = header,
            cookieParams = cookie,
            formParams = formParams,
            bodyJson = bodyJson,
            responseJson = null,
        )
    }

    private fun mapLocation(rawIn: String): ParamLocation? = when (rawIn.lowercase()) {
        "query" -> ParamLocation.QUERY
        "path" -> ParamLocation.PATH
        "header" -> ParamLocation.HEADER
        "cookie" -> ParamLocation.COOKIE
        else -> null
    }

    private fun isStandardHeader(name: String): Boolean =
        name.equals("accept", ignoreCase = true) ||
            name.equals("content-type", ignoreCase = true) ||
            name.equals("content-length", ignoreCase = true)

    private fun sampleValueOf(parameter: Parameter): String =
        sampleValueOfSchema(parameter.schema, depth = 0)

    private fun sampleValueOfSchema(schema: Schema<*>?, depth: Int): String {
        if (schema == null || depth > MAX_SCHEMA_DEPTH) return ""
        schema.example?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        schema.default?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val enumValue = schema.enum?.firstOrNull()
        if (enumValue != null) return enumValue.toString()
        return when (schema.type?.lowercase()) {
            "integer", "number" -> "0"
            "boolean" -> "false"
            "array" -> ""
            "object" -> ""
            else -> ""
        }
    }

    private fun renderBody(
        requestBody: RequestBody?,
        warnings: MutableList<String>,
    ): Pair<String?, List<ExtractedFormParam>> {
        if (requestBody == null) return null to emptyList()
        val content = requestBody.content ?: return null to emptyList()

        content["multipart/form-data"]?.let { mediaType ->
            return null to (mediaType.schema?.properties?.entries?.map { (name, sub) ->
                ExtractedFormParam(
                    name = name,
                    type = if (sub.format == "binary") FormFieldType.FILE else FormFieldType.TEXT,
                    testValue = sampleValueOfSchema(sub, depth = 0),
                )
            } ?: emptyList())
        }

        content["application/x-www-form-urlencoded"]?.let { mediaType ->
            return null to (mediaType.schema?.properties?.entries?.map { (name, sub) ->
                ExtractedFormParam(name = name, type = FormFieldType.TEXT, testValue = sampleValueOfSchema(sub, depth = 0))
            } ?: emptyList())
        }

        val json = content["application/json"]
            ?: content.entries.firstOrNull { it.key.contains("json", ignoreCase = true) }?.value
        if (json != null) {
            val rendered = renderJsonSkeleton(json.schema, depth = 0)
            return rendered to emptyList()
        }

        warnings += "requestBody content-type not supported, body left blank"
        return null to emptyList()
    }

    /**
     * 把 schema 渲染成可读 JSON 文本（不严格遵守 spec，只求让用户开箱即用一个示例 body）。
     *
     * - object → `{ "field": <sample> }`
     * - array → `[ <sample> ]`
     * - string/number/boolean → 字面量
     * - 递归深度 > [MAX_SCHEMA_DEPTH] 时退化为 `null` 防御循环引用
     */
    private fun renderJsonSkeleton(schema: Schema<*>?, depth: Int): String {
        if (schema == null) return "{}"
        if (depth > MAX_SCHEMA_DEPTH) return "null"
        schema.example?.let { return it.toString() }

        val type = schema.type?.lowercase()
        return when {
            type == "object" || (type == null && schema.properties != null) -> {
                val props = schema.properties.orEmpty()
                if (props.isEmpty()) "{}"
                else props.entries.joinToString(
                    prefix = "{\n",
                    postfix = "\n}",
                    separator = ",\n",
                ) { (name, sub) ->
                    val inner = renderJsonSkeleton(sub, depth + 1).prependIndent("  ").trimStart()
                    "  \"$name\": $inner"
                }
            }
            type == "array" -> {
                val item = renderJsonSkeleton(schema.items, depth + 1)
                "[$item]"
            }
            type == "string" -> "\"${schema.default?.toString() ?: schema.enum?.firstOrNull()?.toString() ?: "string"}\""
            type == "integer" || type == "number" ->
                (schema.default?.toString() ?: schema.enum?.firstOrNull()?.toString() ?: "0")
            type == "boolean" -> (schema.default?.toString() ?: "false")
            else -> "null"
        }
    }

    companion object {
        private val LOG = Logger.getInstance(SwaggerImporter::class.java)
        private const val OPENAPI_DEFAULT_TAG = "OpenAPI"
        private const val OPENAPI_PACKAGE = "openapi"
        private const val MAX_SCHEMA_DEPTH = 6

        fun getInstance(project: Project): SwaggerImporter =
            project.getService(SwaggerImporter::class.java)
    }
}
