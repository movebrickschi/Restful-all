package io.github.movebrickschi.restfulall.export

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
)

object ApiDocumentExporter {

    fun fromRoutes(routes: List<RouteInfo>): List<ApiExportEndpoint> =
        routes.map { route ->
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
        val pathParameters = pathParameterNames(endpoint.path).map { name ->
            linkedMapOf(
                "name" to name,
                "in" to "path",
                "required" to true,
                "schema" to linkedMapOf("type" to "string"),
            )
        }
        val operation = linkedMapOf<String, Any?>(
            "summary" to endpoint.summary,
            "operationId" to operationId(endpoint),
            "tags" to listOf(endpoint.group).filter { it.isNotBlank() },
            "parameters" to pathParameters,
            "responses" to linkedMapOf(
                "200" to linkedMapOf("description" to "Success"),
            ),
            "x-restful-all-source" to linkedMapOf(
                "file" to endpoint.sourcePath,
                "line" to endpoint.sourceLine,
                "handler" to "${endpoint.className}#${endpoint.functionName}",
                "framework" to endpoint.framework,
            ),
        )
        if (swagger) {
            operation["produces"] = listOf("application/json")
        }
        return operation
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
            append("| Method | Path | Name | Group | Handler | Source |\n")
            append("|---|---|---|---|---|---|\n")
            endpoints
                .sortedWith(compareBy<ApiExportEndpoint> { normalizePath(it.path) }.thenBy { it.method })
                .forEach { endpoint ->
                    append("| ")
                    append(escapeCell(endpoint.method.uppercase(Locale.ROOT))).append(" | ")
                    append(escapeCell(normalizePath(endpoint.path))).append(" | ")
                    append(escapeCell(endpoint.summary)).append(" | ")
                    append(escapeCell(endpoint.group)).append(" | ")
                    append(escapeCell("${endpoint.className}#${endpoint.functionName}")).append(" | ")
                    append(escapeCell("${endpoint.sourceFileName}:${endpoint.sourceLine}")).append(" |\n")
                }
        }

        private fun escapeCell(value: String): String =
            value.replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")
    }

    private val PATH_PARAMETER_PATTERN = Regex("\\{([^}/]+)}")
    private val YAML_QUOTED_CHARS = setOf(':', '#', '\n', ',', '[', ']', '{', '}', '&', '*', '|', '>', '!', '%')
}
