package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo

class ExpressRouteScanner : RouteScanner {

    override fun supportedExtensions(): Set<String> = setOf("ts", "tsx", "js", "jsx")

    override fun scanFile(file: VirtualFile): List<RouteInfo> {
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }

        if (!content.contains("router.") && !content.contains("app.")) return emptyList()

        val lines = content.lines()
        val commentMap = CommentFilter.buildCommentMap(lines, CommentFilter.Language.C_STYLE)
        val routes = mutableListOf<RouteInfo>()

        for ((index, line) in lines.withIndex()) {
            if (commentMap[index]) continue
            val match = ROUTE_PATTERN.find(line) ?: continue
            val httpMethodStr = match.groupValues[1]
            val path = match.groupValues[2]
            val httpMethod = HttpMethod.fromString(httpMethodStr) ?: continue

            routes.add(
                RouteInfo(
                    method = httpMethod,
                    fullPath = if (path.startsWith("/")) path else "/$path",
                    className = file.nameWithoutExtension,
                    functionName = extractHandlerName(line, match) ?: "anonymous",
                    file = file,
                    lineNumber = index,
                    framework = Framework.EXPRESS,
                )
            )
        }

        return routes
    }

    private fun extractHandlerName(line: String, routeMatch: MatchResult): String? {
        val afterPath = line.substring(routeMatch.range.last + 1)
        val handlerMatch = HANDLER_NAME_PATTERN.find(afterPath)
        return handlerMatch?.groupValues?.get(1)
    }

    companion object {
        private val ROUTE_PATTERN =
            Regex("""(?:router|app)\.(get|post|put|delete|patch|head|options|all)\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        private val HANDLER_NAME_PATTERN =
            Regex("""\b(\w+)\b""")
    }
}
