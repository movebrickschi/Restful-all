package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo

/**
 * Supports FastAPI (`@app.get("/path")`) and Flask (`@app.route("/path", methods=["GET"])`) patterns.
 */
class PythonRouteScanner : RouteScanner {

    override fun supportedExtensions(): Set<String> = setOf("py")

    override fun scanFile(file: VirtualFile): List<RouteInfo> {
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return emptyList()
        }

        if (!content.contains(".get(") && !content.contains(".post(") &&
            !content.contains(".route(") && !content.contains(".put(") &&
            !content.contains(".delete(") && !content.contains(".patch(")
        ) return emptyList()

        val lines = content.lines()
        val routes = mutableListOf<RouteInfo>()

        for ((index, line) in lines.withIndex()) {
            val fastApiMatch = FASTAPI_PATTERN.find(line)
            if (fastApiMatch != null) {
                val httpMethodStr = fastApiMatch.groupValues[1]
                val path = fastApiMatch.groupValues[2]
                val httpMethod = HttpMethod.fromString(httpMethodStr) ?: continue
                val functionName = findPythonFunctionName(lines, index)

                routes.add(
                    RouteInfo(
                        method = httpMethod,
                        fullPath = path,
                        className = file.nameWithoutExtension,
                        functionName = functionName,
                        file = file,
                        lineNumber = index,
                        framework = Framework.PYTHON,
                    )
                )
                continue
            }

            val flaskMatch = FLASK_ROUTE_PATTERN.find(line)
            if (flaskMatch != null) {
                val path = flaskMatch.groupValues[1]
                val methodsStr = flaskMatch.groupValues[2]
                val httpMethod = extractFlaskMethod(methodsStr)
                val functionName = findPythonFunctionName(lines, index)

                routes.add(
                    RouteInfo(
                        method = httpMethod,
                        fullPath = path,
                        className = file.nameWithoutExtension,
                        functionName = functionName,
                        file = file,
                        lineNumber = index,
                        framework = Framework.PYTHON,
                    )
                )
            }
        }

        return routes
    }

    private fun findPythonFunctionName(lines: List<String>, fromLine: Int): String {
        for (i in (fromLine + 1) until minOf(fromLine + 5, lines.size)) {
            val match = PYTHON_DEF_PATTERN.find(lines[i])
            if (match != null) return match.groupValues[1]
        }
        return "unknown"
    }

    private fun extractFlaskMethod(methodsStr: String): HttpMethod {
        val upper = methodsStr.uppercase()
        return when {
            "POST" in upper -> HttpMethod.POST
            "PUT" in upper -> HttpMethod.PUT
            "DELETE" in upper -> HttpMethod.DELETE
            "PATCH" in upper -> HttpMethod.PATCH
            else -> HttpMethod.GET
        }
    }

    companion object {
        private val FASTAPI_PATTERN =
            Regex("""@\w+\.(get|post|put|delete|patch|head|options)\s*\(\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val FLASK_ROUTE_PATTERN =
            Regex("""@\w+\.route\s*\(\s*["']([^"']+)["']\s*(?:,\s*methods\s*=\s*\[([^\]]*)\])?\s*\)""", RegexOption.IGNORE_CASE)
        private val PYTHON_DEF_PATTERN =
            Regex("""def\s+(\w+)\s*\(""")
    }
}
