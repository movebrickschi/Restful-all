package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo

class SpringRouteScanner : RouteScanner {

    override fun supportedExtensions(): Set<String> = setOf("java", "kt")

    override fun scanFile(file: VirtualFile): List<RouteInfo> {
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }

        if (!content.contains("@RestController") && !content.contains("@Controller") &&
            !content.contains("@RequestMapping") && !content.contains("Mapping(")
        ) return emptyList()

        val lines = content.lines()
        val commentMap = CommentFilter.buildCommentMap(lines, CommentFilter.Language.C_STYLE)
        val routes = mutableListOf<RouteInfo>()
        val packageName = PACKAGE_PATTERN.find(content)?.groupValues?.get(1).orEmpty()

        var classPrefix = ""
        var className = ""
        var routeGroupName = ""
        var routeName = ""
        var isController = false

        for ((index, line) in lines.withIndex()) {
            if (commentMap[index]) continue
            val trimmed = line.trim()

            extractRouteGroupName(trimmed)?.let { routeGroupName = it }
            extractRouteName(trimmed)?.let { routeName = it }

            if (CONTROLLER_ANNOTATION.containsMatchIn(trimmed)) {
                isController = true
            }

            val classMappingMatch = REQUEST_MAPPING_VALUE.find(trimmed)
            if (classMappingMatch != null && !isInsideMethod(lines, index)) {
                classPrefix = classMappingMatch.groupValues[1]
            }

            val classMatch = CLASS_PATTERN.find(trimmed)
            if (classMatch != null && isController) {
                className = classMatch.groupValues[1]
                if (routeGroupName.isBlank()) {
                    routeGroupName = className
                }
            }

            if (!isController) continue

            val shortcutMatch = SHORTCUT_MAPPING_PATTERN.find(trimmed)
            if (shortcutMatch != null) {
                val httpMethodStr = shortcutMatch.groupValues[1]
                val methodPath = shortcutMatch.groupValues[2]
                val httpMethod = HttpMethod.fromString(httpMethodStr) ?: continue
                val functionName = findFunctionName(lines, index)
                val fullPath = buildPath(classPrefix, methodPath)

                routes.add(
                    RouteInfo(
                        method = httpMethod,
                        fullPath = fullPath,
                        className = className,
                        functionName = functionName,
                        file = file,
                        lineNumber = index,
                        framework = Framework.SPRING,
                        packageName = packageName,
                        routeGroupName = routeGroupName.ifBlank { className },
                        routeName = routeName.ifBlank { functionName },
                    )
                )
                routeName = ""
                continue
            }

            val reqMappingMatch = REQUEST_MAPPING_WITH_METHOD.find(trimmed)
            if (reqMappingMatch != null && isInsideMethod(lines, index)) {
                val methodStr = reqMappingMatch.groupValues[1]
                val pathStr = reqMappingMatch.groupValues[2]
                val httpMethod = resolveSpringMethod(methodStr) ?: HttpMethod.GET
                val functionName = findFunctionName(lines, index)
                val fullPath = buildPath(classPrefix, pathStr)

                routes.add(
                    RouteInfo(
                        method = httpMethod,
                        fullPath = fullPath,
                        className = className,
                        functionName = functionName,
                        file = file,
                        lineNumber = index,
                        framework = Framework.SPRING,
                        packageName = packageName,
                        routeGroupName = routeGroupName.ifBlank { className },
                        routeName = routeName.ifBlank { functionName },
                    )
                )
                routeName = ""
            }
        }

        return routes
    }

    private fun isInsideMethod(lines: List<String>, currentLine: Int): Boolean {
        for (i in (currentLine + 1) until minOf(currentLine + 5, lines.size)) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("@")) continue
            if (CLASS_PATTERN.containsMatchIn(trimmed)) return false
            if (FUNCTION_PATTERN.containsMatchIn(trimmed)) return true
        }
        return true
    }

    private fun findFunctionName(lines: List<String>, fromLine: Int): String {
        for (i in fromLine until minOf(fromLine + 10, lines.size)) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("@") || trimmed.isEmpty()) continue
            val match = FUNCTION_PATTERN.findAll(trimmed).firstOrNull()
            if (match != null) return match.groupValues[1]
        }
        return "unknown"
    }

    private fun buildPath(prefix: String, methodPath: String): String {
        val p = prefix.trim('/')
        val m = methodPath.trim('/')
        return when {
            p.isEmpty() && m.isEmpty() -> "/"
            p.isEmpty() -> "/$m"
            m.isEmpty() -> "/$p"
            else -> "/$p/$m"
        }
    }

    private fun resolveSpringMethod(methodStr: String): HttpMethod? {
        val upper = methodStr.uppercase()
        return when {
            "GET" in upper -> HttpMethod.GET
            "POST" in upper -> HttpMethod.POST
            "PUT" in upper -> HttpMethod.PUT
            "DELETE" in upper -> HttpMethod.DELETE
            "PATCH" in upper -> HttpMethod.PATCH
            "HEAD" in upper -> HttpMethod.HEAD
            "OPTIONS" in upper -> HttpMethod.OPTIONS
            else -> null
        }
    }

    private fun extractRouteGroupName(line: String): String? {
        val tagName = TAG_NAME_PATTERN.find(line)?.groupValues?.get(1)
        if (!tagName.isNullOrBlank()) return tagName

        val apiName = API_GROUP_PATTERN.find(line)?.groupValues?.get(1)
        if (!apiName.isNullOrBlank()) return apiName

        return null
    }

    private fun extractRouteName(line: String): String? {
        val operationSummary = OPERATION_SUMMARY_PATTERN.find(line)?.groupValues?.get(1)
        if (!operationSummary.isNullOrBlank()) return operationSummary

        val apiOperationValue = API_OPERATION_VALUE_PATTERN.find(line)?.groupValues?.get(1)
        if (!apiOperationValue.isNullOrBlank()) return apiOperationValue

        return null
    }

    companion object {
        private val PACKAGE_PATTERN =
            Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)(?:\s*;)?""")
        private val CONTROLLER_ANNOTATION =
            Regex("""@(?:RestController|Controller)\b""")
        private val TAG_NAME_PATTERN =
            Regex("""@Tag\s*\([^)]*name\s*=\s*["']([^"']+)["']""")
        private val API_GROUP_PATTERN =
            Regex("""@Api\s*\([^)]*(?:tags|value)\s*=\s*(?:\{\s*)?["']([^"']+)["']""")
        private val OPERATION_SUMMARY_PATTERN =
            Regex("""@Operation\s*\([^)]*summary\s*=\s*["']([^"']+)["']""")
        private val API_OPERATION_VALUE_PATTERN =
            Regex("""@ApiOperation\s*\([^)]*(?:value|notes)\s*=\s*["']([^"']+)["']""")
        private val REQUEST_MAPPING_VALUE =
            Regex("""@RequestMapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']""")
        private val SHORTCUT_MAPPING_PATTERN =
            Regex("""@(Get|Post|Put|Delete|Patch)Mapping\s*\(\s*(?:value\s*=\s*)?["']([^"']*)["']\s*(?:,.*?)?\)""")
        private val REQUEST_MAPPING_WITH_METHOD =
            Regex("""@RequestMapping\s*\(.*?method\s*=\s*(?:RequestMethod\.)?(\w+).*?(?:value\s*=\s*)?["']([^"']*)["']""")
        private val CLASS_PATTERN =
            Regex("""class\s+(\w+)""")
        private val FUNCTION_PATTERN =
            Regex("""(\w+)\s*\(""")
    }
}
