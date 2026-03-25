package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo

class NestJsRouteScanner : RouteScanner {

    override fun supportedExtensions(): Set<String> = setOf("ts", "tsx")

    override fun scanFile(file: VirtualFile): List<RouteInfo> {
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.warn("Failed to read file: ${file.path}", e)
            return emptyList()
        }

        if (!content.contains("@Controller")) return emptyList()

        LOG.debug("Scanning NestJS file: ${file.path}")

        val lines = content.lines()
        val commentMap = CommentFilter.buildCommentMap(lines, CommentFilter.Language.C_STYLE)
        val routes = mutableListOf<RouteInfo>()

        var controllerPrefix = ""
        var className = ""
        var inController = false

        for ((index, line) in lines.withIndex()) {
            if (commentMap[index]) continue
            val trimmed = line.trim()

            val controllerMatch = CONTROLLER_PATTERN.find(trimmed)
            if (controllerMatch != null) {
                controllerPrefix = controllerMatch.groupValues[1]
                inController = true
                className = findClassName(lines, index)
                LOG.debug("  Found @Controller('$controllerPrefix') -> $className at line $index")
                continue
            }

            if (CONTROLLER_NO_ARG_PATTERN.find(trimmed) != null) {
                controllerPrefix = ""
                inController = true
                className = findClassName(lines, index)
                LOG.debug("  Found @Controller() -> $className at line $index")
                continue
            }

            if (!inController) continue

            val methodMatch = METHOD_PATTERN.find(trimmed)
            if (methodMatch != null) {
                val httpMethodStr = methodMatch.groupValues[1]
                val methodPath = methodMatch.groupValues[2]
                val httpMethod = HttpMethod.fromString(httpMethodStr) ?: continue
                val functionName = findFunctionName(lines, index)

                val fullPath = buildPath(controllerPrefix, methodPath)

                routes.add(
                    RouteInfo(
                        method = httpMethod,
                        fullPath = fullPath,
                        className = className,
                        functionName = functionName,
                        file = file,
                        lineNumber = index,
                        framework = Framework.NESTJS,
                    )
                )
            }
        }

        if (routes.isNotEmpty()) {
            LOG.info("Found ${routes.size} NestJS routes in ${file.name}")
        }

        return routes
    }

    private fun findClassName(lines: List<String>, fromLine: Int): String {
        for (i in fromLine until minOf(fromLine + 5, lines.size)) {
            val match = CLASS_PATTERN.find(lines[i])
            if (match != null) return match.groupValues[1]
        }
        return "Unknown"
    }

    private fun findFunctionName(lines: List<String>, fromLine: Int): String {
        for (i in fromLine until minOf(fromLine + 10, lines.size)) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("@") || trimmed.isEmpty()) continue
            val match = TS_FUNCTION_PATTERN.find(trimmed)
            if (match != null) return match.groupValues[1]
            val fallback = FUNCTION_PATTERN.findAll(trimmed).firstOrNull()
            if (fallback != null) return fallback.groupValues[1]
        }
        return "unknown"
    }

    private fun buildPath(controllerPrefix: String, methodPath: String): String {
        val prefix = controllerPrefix.trim('/')
        val path = methodPath.trim('/')
        return when {
            prefix.isEmpty() && path.isEmpty() -> "/"
            prefix.isEmpty() -> "/$path"
            path.isEmpty() -> "/$prefix"
            else -> "/$prefix/$path"
        }
    }

    companion object {
        private val LOG = Logger.getInstance(NestJsRouteScanner::class.java)

        private val CONTROLLER_PATTERN =
            Regex("""@Controller\s*\(\s*['"]([^'"]*)['"]\s*\)""")
        private val CONTROLLER_NO_ARG_PATTERN =
            Regex("""@Controller\s*\(\s*\)""")
        private val METHOD_PATTERN =
            Regex("""@(Get|Post|Put|Delete|Patch|Head|Options|All)\s*\(\s*(?:['"]([^'"]*)['"]\s*)?\)""")
        private val CLASS_PATTERN =
            Regex("""class\s+(\w+)""")
        private val TS_FUNCTION_PATTERN =
            Regex("""(?:async\s+)?(\w+)\s*\(""")
        private val FUNCTION_PATTERN =
            Regex("""(\w+)\s*\(""")
    }
}
