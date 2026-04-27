package io.github.movebrickschi.restfulall.export

import io.github.movebrickschi.restfulall.model.ExtractedFormParam
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.ExtractedParam
import io.github.movebrickschi.restfulall.model.RouteInfo
import java.util.Locale

data class ApiDocumentOptions(
    val title: String,
    val version: String,
    val description: String = "",
)

enum class ApiDocumentFormat(
    val label: String,
    val extension: String,
) {
    OPENAPI_JSON("OpenAPI 3.0 JSON", "json"),
    OPENAPI_YAML("OpenAPI 3.0 YAML", "yaml"),
    SWAGGER_JSON("Swagger 2.0 JSON", "json"),
    SWAGGER_YAML("Swagger 2.0 YAML", "yaml"),
    MARKDOWN("Markdown", "md"),
}

data class ApiExportEndpoint(
    val method: String,
    val path: String,
    val summary: String,
    val group: String,
    val className: String,
    val functionName: String,
    val framework: String,
    val sourceFileName: String,
    val sourcePath: String,
    val sourceLine: Int,
    val queryParams: List<ExtractedParam> = emptyList(),
    val pathParams: List<ExtractedParam> = emptyList(),
    val headerParams: List<ExtractedParam> = emptyList(),
    val cookieParams: List<ExtractedParam> = emptyList(),
    val bodyJson: String? = null,
    val formParams: List<ExtractedFormParam> = emptyList(),
    val responseJson: String? = null,
)

object ApiDocumentExporter {

    fun fromRoutes(
        routes: List<RouteInfo>,
        paramsMap: Map<RouteInfo, ExtractedMethodParams?> = emptyMap(),
    ): List<ApiExportEndpoint> =
        routes.map { route ->
            val params = paramsMap[route]
            ApiExportEndpoint(
                method = route.method.displayName,
                path = route.displayPath,
                summary = route.routeName.ifBlank { route.functionName },
                group = route.routeGroupName.ifBlank { route.className.ifBlank { route.file.nameWithoutExtension } },
                className = route.className,
                functionName = route.functionName,
                framework = route.framework.displayName,
                sourceFileName = route.file.name,
                sourcePath = route.file.path,
                sourceLine = route.lineNumber + 1,
                queryParams = params?.queryParams ?: emptyList(),
                pathParams = params?.pathParams ?: emptyList(),
                headerParams = params?.headerParams ?: emptyList(),
                cookieParams = params?.cookieParams ?: emptyList(),
                bodyJson = params?.bodyJson,
                formParams = params?.formParams ?: emptyList(),
                responseJson = params?.responseJson,
            )
        }

    fun export(
        endpoints: List<ApiExportEndpoint>,
        format: ApiDocumentFormat,
        options: ApiDocumentOptions,
    ): String =
        when (format) {
            ApiDocumentFormat.OPENAPI_JSON -> JsonEmitter.emit(buildOpenApiDocument(endpoints, options))
            ApiDocumentFormat.OPENAPI_YAML -> YamlEmitter.emit(buildOpenApiDocument(endpoints, options))
            ApiDocumentFormat.SWAGGER_JSON -> JsonEmitter.emit(buildSwaggerDocument(endpoints, options))
            ApiDocumentFormat.SWAGGER_YAML -> YamlEmitter.emit(buildSwaggerDocument(endpoints, options))
            ApiDocumentFormat.MARKDOWN -> MarkdownEmitter.emit(endpoints, options)
        }

    private fun buildOpenApiDocument(
        endpoints: List<ApiExportEndpoint>,
        options: ApiDocumentOptions,
    ): Map<String, Any?> =
        linkedMapOf(
            "openapi" to "3.0.3",
            "info" to buildInfo(options),
            "paths" to buildPaths(endpoints, swagger = false),
        )

    private fun buildSwaggerDocument(
        endpoints: List<ApiExportEndpoint>,
        options: ApiDocumentOptions,
    ): Map<String, Any?> =
        linkedMapOf(
            "swagger" to "2.0",
            "info" to buildInfo(options),
            "paths" to buildPaths(endpoints, swagger = true),
        )

    private fun buildInfo(options: ApiDocumentOptions): Map<String, Any?> =
        linkedMapOf(
            "title" to options.title,
            "version" to options.version,
            "description" to options.description,
        )

    private fun buildPaths(
        endpoints: List<ApiExportEndpoint>,
        swagger: Boolean,
    ): Map<String, Any?> {
        val paths = linkedMapOf<String, Any?>()
        endpoints
            .sortedWith(compareBy<ApiExportEndpoint> { normalizePath(it.path) }.thenBy { it.method.lowercase(Locale.ROOT) })
            .forEach { endpoint ->
                val path = normalizePath(endpoint.path)
                @Suppress("UNCHECKED_CAST")
                val operations = paths.getOrPut(path) { linkedMapOf<String, Any?>() } as MutableMap<String, Any?>
                operationMethods(endpoint.method).forEach { method ->
                    operations[method] = buildOperation(endpoint, swagger)
                }
            }
        return paths
    }

    private fun buildOperation(endpoint: ApiExportEndpoint, swagger: Boolean): Map<String, Any?> {
        val allPathParamNames = pathParameterNames(endpoint.path).toSet()

        // Merge: URL-pattern path params + extractor path params (by name, deduplicated)
        val urlPathParams = pathParameterNames(endpoint.path).map { name ->
            val extracted = endpoint.pathParams.find { it.name == name }
            linkedMapOf(
                "name" to name,
                "in" to "path",
                "required" to true,
                "schema" to linkedMapOf("type" to "string"),
                *(if (extracted?.testValue?.isNotBlank() == true) arrayOf("example" to extracted.testValue) else emptyArray()),
            )
        }
        val extraPathParams = endpoint.pathParams
            .filter { it.name !in allPathParamNames }
            .map { param ->
                linkedMapOf(
                    "name" to param.name,
                    "in" to "path",
                    "required" to true,
                    "schema" to linkedMapOf("type" to "string"),
                    *(if (param.testValue.isNotBlank()) arrayOf("example" to param.testValue) else emptyArray()),
                )
            }

        val queryParameters = endpoint.queryParams.map { param ->
            linkedMapOf(
                "name" to param.name,
                "in" to "query",
                "required" to false,
                "schema" to linkedMapOf("type" to "string"),
                *(if (param.testValue.isNotBlank()) arrayOf("example" to param.testValue) else emptyArray()),
            )
        }

        val headerParameters = endpoint.headerParams.map { param ->
            linkedMapOf(
                "name" to param.name,
                "in" to "header",
                "required" to false,
                "schema" to linkedMapOf("type" to "string"),
                *(if (param.testValue.isNotBlank()) arrayOf("example" to param.testValue) else emptyArray()),
            )
        }

        val cookieParameters = endpoint.cookieParams.map { param ->
            linkedMapOf(
                "name" to param.name,
                "in" to "cookie",
                "required" to false,
                "schema" to linkedMapOf("type" to "string"),
                *(if (param.testValue.isNotBlank()) arrayOf("example" to param.testValue) else emptyArray()),
            )
        }

        val parameters = (urlPathParams + extraPathParams + queryParameters + headerParameters + cookieParameters)

        val operation = linkedMapOf<String, Any?>(
            "summary" to endpoint.summary,
            "operationId" to operationId(endpoint),
            "tags" to listOf(endpoint.group).filter { it.isNotBlank() },
            "parameters" to parameters,
        )

        // requestBody (OpenAPI 3.0)
        if (!swagger) {
            buildRequestBody(endpoint)?.let { operation["requestBody"] = it }
        }

        // responses
        operation["responses"] = buildResponses(endpoint, swagger)

        if (swagger) {
            operation["produces"] = listOf("application/json")
            val swaggerExtra = buildSwaggerBodyParams(endpoint)
            if (swaggerExtra != null) {
                operation["consumes"] = swaggerExtra.first
                operation["parameters"] = parameters + swaggerExtra.second
            }
        }

        operation["x-restful-all-source"] = linkedMapOf(
            "file" to endpoint.sourcePath,
            "line" to endpoint.sourceLine,
            "handler" to "${endpoint.className}#${endpoint.functionName}",
            "framework" to endpoint.framework,
        )

        return operation
    }

    private fun buildRequestBody(endpoint: ApiExportEndpoint): Map<String, Any?>? {
        // form-data (file upload or form fields)
        if (endpoint.formParams.isNotEmpty()) {
            val properties = linkedMapOf<String, Any?>()
            val required = mutableListOf<String>()
            for (fp in endpoint.formParams) {
                properties[fp.name] = when (fp.type.name) {
                    "FILE" -> linkedMapOf("type" to "string", "format" to "binary")
                    else -> linkedMapOf("type" to "string").also {
                        if (fp.testValue.isNotBlank()) it["example"] = fp.testValue
                    }
                }
            }
            return linkedMapOf(
                "content" to linkedMapOf(
                    "multipart/form-data" to linkedMapOf(
                        "schema" to linkedMapOf(
                            "type" to "object",
                            "properties" to properties,
                            *(if (required.isNotEmpty()) arrayOf("required" to required) else emptyArray()),
                        ),
                    ),
                ),
            )
        }

        // JSON body
        if (endpoint.bodyJson != null) {
            val content = linkedMapOf<String, Any?>(
                "application/json" to linkedMapOf(
                    "schema" to linkedMapOf("type" to "object"),
                    "example" to endpoint.bodyJson,
                ),
            )
            return linkedMapOf("content" to content)
        }

        return null
    }

    /**
     * Swagger 2.0：返回 (consumes, extraParams) 或 null（无 body）。
     * form-data 时每个字段独立为 in=formData 参数；json body 时为 in=body 参数。
     */
    private fun buildSwaggerBodyParams(endpoint: ApiExportEndpoint): Pair<List<String>, List<Map<String, Any?>>>? {
        if (endpoint.formParams.isNotEmpty()) {
            val formDataParams = endpoint.formParams.map { fp ->
                linkedMapOf<String, Any?>(
                    "name" to fp.name,
                    "in" to "formData",
                    "required" to false,
                    "type" to when (fp.type.name) { "FILE" -> "file"; else -> "string" },
                    *(if (fp.testValue.isNotBlank()) arrayOf("x-example" to fp.testValue) else emptyArray()),
                )
            }
            return Pair(listOf("multipart/form-data"), formDataParams)
        }
        if (endpoint.bodyJson != null) {
            val bodyParam = linkedMapOf<String, Any?>(
                "name" to "body",
                "in" to "body",
                "required" to true,
                "schema" to linkedMapOf("type" to "object"),
                "x-example" to endpoint.bodyJson,
            )
            return Pair(listOf("application/json"), listOf(bodyParam))
        }
        return null
    }

    private fun buildResponses(endpoint: ApiExportEndpoint, swagger: Boolean): Map<String, Any?> {
        val response200 = linkedMapOf<String, Any?>("description" to "Success")
        if (endpoint.responseJson != null) {
            if (swagger) {
                response200["schema"] = linkedMapOf("type" to "object")
                response200["examples"] = linkedMapOf(
                    "application/json" to endpoint.responseJson,
                )
            } else {
                response200["content"] = linkedMapOf(
                    "application/json" to linkedMapOf(
                        "schema" to linkedMapOf("type" to "object"),
                        "example" to endpoint.responseJson,
                    ),
                )
            }
        }
        return linkedMapOf("200" to response200)
    }

    private fun operationMethods(method: String): List<String> {
        val normalized = method.lowercase(Locale.ROOT)
        return if (normalized == "all") {
            listOf("get", "post", "put", "delete", "patch", "head", "options")
        } else {
            listOf(normalized)
        }
    }

    private fun pathParameterNames(path: String): List<String> =
        PATH_PARAMETER_PATTERN.findAll(path).map { it.groupValues[1] }.distinct().toList()

    private fun operationId(endpoint: ApiExportEndpoint): String {
        val raw = "${endpoint.className}_${endpoint.functionName}_${endpoint.method}_${normalizePath(endpoint.path)}"
        return raw
            .replace(Regex("[^A-Za-z0-9_]+"), "_")
            .trim('_')
            .ifBlank { "operation" }
    }

    private fun normalizePath(path: String): String {
        val normalized = path.replace(Regex("/+"), "/")
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }

    private object JsonEmitter {
        fun emit(value: Any?): String = buildString {
            appendValue(value, 0)
            append('\n')
        }

        private fun StringBuilder.appendValue(value: Any?, indent: Int) {
            when (value) {
                null -> append("null")
                is String -> append('"').append(escape(value)).append('"')
                is Number, is Boolean -> append(value)
                is Map<*, *> -> appendObject(value, indent)
                is Iterable<*> -> appendArray(value, indent)
                else -> append('"').append(escape(value.toString())).append('"')
            }
        }

        private fun StringBuilder.appendObject(value: Map<*, *>, indent: Int) {
            if (value.isEmpty()) {
                append("{}")
                return
            }
            append("{\n")
            value.entries.forEachIndexed { index, entry ->
                append(" ".repeat(indent + 2))
                append('"').append(escape(entry.key.toString())).append("\": ")
                appendValue(entry.value, indent + 2)
                if (index < value.size - 1) append(',')
                append('\n')
            }
            append(" ".repeat(indent)).append('}')
        }

        private fun StringBuilder.appendArray(value: Iterable<*>, indent: Int) {
            val items = value.toList()
            if (items.isEmpty()) {
                append("[]")
                return
            }
            val simple = items.all { it !is Map<*, *> && it !is Iterable<*> }
            if (simple) {
                append('[')
                items.forEachIndexed { index, item ->
                    appendValue(item, indent)
                    if (index < items.size - 1) append(", ")
                }
                append(']')
                return
            }
            append("[\n")
            items.forEachIndexed { index, item ->
                append(" ".repeat(indent + 2))
                appendValue(item, indent + 2)
                if (index < items.size - 1) append(',')
                append('\n')
            }
            append(" ".repeat(indent)).append(']')
        }

        private fun escape(value: String): String =
            buildString {
                value.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> {
                            if (char.code < 0x20) {
                                append("\\u").append(char.code.toString(16).padStart(4, '0'))
                            } else {
                                append(char)
                            }
                        }
                    }
                }
            }
    }

    private object YamlEmitter {
        fun emit(value: Map<String, Any?>): String = buildString {
            appendMap(value, 0)
        }

        private fun StringBuilder.appendMap(value: Map<*, *>, indent: Int) {
            value.forEach { (key, nestedValue) ->
                append(" ".repeat(indent)).append(key).append(':')
                when (nestedValue) {
                    is Map<*, *> -> {
                        if (nestedValue.isEmpty()) append(" {}\n") else {
                            append('\n')
                            appendMap(nestedValue, indent + 2)
                        }
                    }
                    is Iterable<*> -> appendList(nestedValue.toList(), indent)
                    else -> append(' ').appendScalar(key.toString(), nestedValue).append('\n')
                }
            }
        }

        private fun StringBuilder.appendList(value: List<*>, indent: Int) {
            if (value.isEmpty()) {
                append(" []\n")
                return
            }
            val simple = value.all { it !is Map<*, *> && it !is Iterable<*> }
            if (simple) {
                append(' ')
                append(value.joinToString(prefix = "[", postfix = "]") { scalar("", it) })
                append('\n')
                return
            }
            append('\n')
            value.forEach { item ->
                append(" ".repeat(indent + 2)).append("- ")
                when (item) {
                    is Map<*, *> -> {
                        append('\n')
                        appendMap(item, indent + 4)
                    }
                    else -> appendScalar("", item).append('\n')
                }
            }
        }

        private fun StringBuilder.appendScalar(key: String, value: Any?): StringBuilder =
            append(scalar(key, value))

        private fun scalar(key: String, value: Any?): String =
            when (value) {
                null -> "null"
                is Number, is Boolean -> value.toString()
                else -> quoteYamlScalar(key, value.toString())
            }

        private fun quoteYamlScalar(key: String, text: String): String {
            val needsQuote = key == "swagger" ||
                text.isBlank() ||
                text.any { it in YAML_QUOTED_CHARS } ||
                text.firstOrNull()?.let { it == '-' || it == '?' || it == '@' || it == '`' } == true
            return if (needsQuote) "'${text.replace("'", "''")}'" else text
        }
    }

    private object MarkdownEmitter {
        fun emit(endpoints: List<ApiExportEndpoint>, options: ApiDocumentOptions): String = buildString {
            append("# ").append(options.title).append("\n\n")
            if (options.description.isNotBlank()) {
                append(options.description).append("\n\n")
            }
            append("**Version:** ").append(options.version).append("\n\n")

            val groups = endpoints
                .sortedWith(compareBy<ApiExportEndpoint> { normalizePath(it.path) }.thenBy { it.method })
                .groupBy { it.group }

            groups.forEach { (group, groupEndpoints) ->
                if (group.isNotBlank()) {
                    append("## ").append(group).append("\n\n")
                }
                groupEndpoints.forEach { endpoint ->
                    appendEndpoint(endpoint)
                }
            }
        }

        private fun StringBuilder.appendEndpoint(endpoint: ApiExportEndpoint) {
            val method = endpoint.method.uppercase(Locale.ROOT)
            val title = endpoint.summary.ifBlank { endpoint.functionName }
            append("### ").append(title).append("\n\n")
            append("`").append(method).append(" ").append(normalizePath(endpoint.path)).append("`\n\n")

            // Path parameters
            val pathParams = mergePathParams(endpoint)
            if (pathParams.isNotEmpty()) {
                append("**Path Parameters**\n\n")
                append("| Name | Type | Required | Example |\n")
                append("|------|------|----------|---------|\n")
                pathParams.forEach { p ->
                    append("| ").append(escapeCell(p.name))
                        .append(" | string | Yes | ").append(escapeCell(p.testValue)).append(" |\n")
                }
                append("\n")
            }

            // Query parameters
            if (endpoint.queryParams.isNotEmpty()) {
                append("**Query Parameters**\n\n")
                append("| Name | Type | Required | Example |\n")
                append("|------|------|----------|---------|\n")
                endpoint.queryParams.forEach { p ->
                    append("| ").append(escapeCell(p.name))
                        .append(" | string | No | ").append(escapeCell(p.testValue)).append(" |\n")
                }
                append("\n")
            }

            // Header parameters
            if (endpoint.headerParams.isNotEmpty()) {
                append("**Header Parameters**\n\n")
                append("| Name | Type | Required | Example |\n")
                append("|------|------|----------|---------|\n")
                endpoint.headerParams.forEach { p ->
                    append("| ").append(escapeCell(p.name))
                        .append(" | string | No | ").append(escapeCell(p.testValue)).append(" |\n")
                }
                append("\n")
            }

            // Cookie parameters
            if (endpoint.cookieParams.isNotEmpty()) {
                append("**Cookie Parameters**\n\n")
                append("| Name | Type | Required | Example |\n")
                append("|------|------|----------|---------|\n")
                endpoint.cookieParams.forEach { p ->
                    append("| ").append(escapeCell(p.name))
                        .append(" | string | No | ").append(escapeCell(p.testValue)).append(" |\n")
                }
                append("\n")
            }

            // Form-data parameters
            if (endpoint.formParams.isNotEmpty()) {
                append("**Form Data**\n\n")
                append("| Name | Type | Example |\n")
                append("|------|------|---------|\n")
                endpoint.formParams.forEach { fp ->
                    val type = if (fp.type.name == "FILE") "file" else "string"
                    append("| ").append(escapeCell(fp.name))
                        .append(" | ").append(type)
                        .append(" | ").append(escapeCell(fp.testValue)).append(" |\n")
                }
                append("\n")
            }

            // Request body
            if (endpoint.bodyJson != null) {
                append("**Request Body** (`application/json`)\n\n")
                append("```json\n")
                append(endpoint.bodyJson)
                append("\n```\n\n")
            }

            // Response
            append("**Response** (`200 Success`)\n\n")
            if (endpoint.responseJson != null) {
                append("```json\n")
                append(endpoint.responseJson)
                append("\n```\n\n")
            } else {
                append("_No response schema available._\n\n")
            }

            // Source
            append("> Source: `${escapeCell(endpoint.sourceFileName)}:${endpoint.sourceLine}`  ")
            append("Handler: `${escapeCell(endpoint.className)}#${escapeCell(endpoint.functionName)}`\n\n")
            append("---\n\n")
        }

        /** Merge URL-pattern path params with extractor path params, deduplicated by name */
        private fun mergePathParams(endpoint: ApiExportEndpoint): List<ExtractedParam> {
            val urlNames = pathParameterNames(endpoint.path).toSet()
            val fromExtractor = endpoint.pathParams.associateBy { it.name }
            val result = mutableListOf<io.github.movebrickschi.restfulall.model.ExtractedParam>()
            for (name in urlNames) {
                val e = fromExtractor[name]
                result.add(io.github.movebrickschi.restfulall.model.ExtractedParam(
                    name = name,
                    location = io.github.movebrickschi.restfulall.model.ParamLocation.PATH,
                    testValue = e?.testValue ?: "",
                ))
            }
            endpoint.pathParams.filter { it.name !in urlNames }.forEach { result.add(it) }
            return result
        }

        private fun escapeCell(value: String): String =
            value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")

        private fun pathParameterNames(path: String): List<String> =
            PATH_PARAMETER_PATTERN.findAll(path).map { it.groupValues[1] }.distinct().toList()
    }

    private val PATH_PARAMETER_PATTERN = Regex("\\{([^}/]+)}")
    private val YAML_QUOTED_CHARS = setOf(':', '#', '\n', ',', '[', ']', '{', '}', '&', '*', '|', '>', '!', '%')
}
