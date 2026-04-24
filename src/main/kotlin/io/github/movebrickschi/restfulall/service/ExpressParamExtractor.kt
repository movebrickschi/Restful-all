package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.ExtractedFormParam
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.ExtractedParam
import io.github.movebrickschi.restfulall.model.FormFieldType
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.ParamLocation
import io.github.movebrickschi.restfulall.model.RouteInfo

/**
 * Express 参数提取：在路由声明行扫描 multer 中间件。
 *
 * 支持模式（middleware 形态）：
 *   upload.single('avatar')                 -> form file: avatar
 *   upload.array('photos', 12)              -> form file: photos
 *   upload.fields([{name:'a'}, {name:'b'}]) -> form file: a, b
 *   upload.any()                            -> form file: file（占位）
 *   upload.none()                           -> 仅 text，不做特殊处理
 *
 * 路由路径里的 `:param` 提取为 path 参数。
 */
object ExpressParamExtractor {

    fun extract(routeInfo: RouteInfo): ExtractedMethodParams? {
        if (routeInfo.framework != Framework.EXPRESS) return null

        val content = try {
            String(routeInfo.file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val lines = content.lines()
        if (routeInfo.lineNumber !in lines.indices) return null

        // 取路由声明所在的逻辑行（跨行调用整体拼接）
        val declaration = readRouteDeclaration(lines, routeInfo.lineNumber)

        val pathParams = mutableListOf<ExtractedParam>()
        for (match in PATH_PARAM_REGEX.findAll(routeInfo.fullPath)) {
            pathParams.add(ExtractedParam(match.groupValues[1], ParamLocation.PATH, ""))
        }

        val formParams = mutableListOf<ExtractedFormParam>()

        for (m in MULTER_SINGLE_REGEX.findAll(declaration)) {
            formParams.add(ExtractedFormParam(m.groupValues[1], FormFieldType.FILE, ""))
        }
        for (m in MULTER_ARRAY_REGEX.findAll(declaration)) {
            formParams.add(ExtractedFormParam(m.groupValues[1], FormFieldType.FILE, ""))
        }
        for (m in MULTER_FIELDS_REGEX.findAll(declaration)) {
            val fieldsBlock = m.groupValues[1]
            for (fm in FIELDS_NAME_REGEX.findAll(fieldsBlock)) {
                formParams.add(ExtractedFormParam(fm.groupValues[1], FormFieldType.FILE, ""))
            }
        }
        if (MULTER_ANY_REGEX.containsMatchIn(declaration) && formParams.none { it.type == FormFieldType.FILE }) {
            formParams.add(ExtractedFormParam("file", FormFieldType.FILE, ""))
        }

        return ExtractedMethodParams(
            pathParams = pathParams,
            formParams = formParams,
        )
    }

    private fun readRouteDeclaration(lines: List<String>, startLine: Int): String {
        val builder = StringBuilder()
        var paren = 0
        var started = false
        for (i in startLine until minOf(startLine + 30, lines.size)) {
            for (ch in lines[i]) {
                builder.append(ch)
                when (ch) {
                    '(' -> { paren++; started = true }
                    ')' -> paren--
                }
            }
            builder.append(' ')
            if (started && paren <= 0) break
        }
        return builder.toString()
    }

    private val PATH_PARAM_REGEX = Regex(""":(\w+)""")
    private val MULTER_SINGLE_REGEX = Regex("""\b\w+\.single\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    private val MULTER_ARRAY_REGEX = Regex("""\b\w+\.array\s*\(\s*['"]([^'"]+)['"]""")
    private val MULTER_FIELDS_REGEX = Regex("""\b\w+\.fields\s*\(\s*\[(.*?)]\s*\)""", RegexOption.DOT_MATCHES_ALL)
    private val FIELDS_NAME_REGEX = Regex("""name\s*:\s*['"]([^'"]+)['"]""")
    private val MULTER_ANY_REGEX = Regex("""\b\w+\.any\s*\(\s*\)""")
}
