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

        var classPrefix = ""
        var className = ""
        var isController = false

        for ((index, line) in lines.withIndex()) {
            if (commentMap[index]) continue
            val trimmed = line.trim()

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
                    )
                )
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
                    )
                )
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

    companion object {
        private val CONTROLLER_ANNOTATION =
            Regex("""@(?:RestController|Controller)\b""")
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
