package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.ExtractedFormParam
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.ExtractedParam
import io.github.movebrickschi.restfulall.model.FormFieldType
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.ParamLocation
import io.github.movebrickschi.restfulall.model.RouteInfo

/**
 * Python 参数提取：
 *
 * FastAPI（基于 def 签名）：
 *   file: UploadFile = File(...)              -> form file
 *   files: list[UploadFile] = File(...)       -> form file
 *   data: bytes = File(...)                   -> form file
 *   name: str = Form(...)                     -> form text
 *   q: str = Query(...)                       -> query
 *   item_id: int = Path(...)                  -> path
 *   x_token: str = Header(...)                -> header
 *
 * Flask（扫描函数体）：
 *   request.files['key'] / request.files.get('key') -> form file
 *   request.form['key']  / request.form.get('key')  -> form text
 *   request.args['key']  / request.args.get('key')  -> query
 *
 * 任一文件参数出现时，所有 query/form 参数统一进 form-data。
 */
object PythonParamExtractor {

    fun extract(routeInfo: RouteInfo): ExtractedMethodParams? {
        if (routeInfo.framework != Framework.PYTHON) return null

        val content = try {
            String(routeInfo.file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val lines = content.lines()
        val defStart = findDefLine(lines, routeInfo.lineNumber) ?: return null
        val signature = readDefSignature(lines, defStart) ?: return null

        val pathParams = mutableListOf<ExtractedParam>()
        for (m in PATH_VAR_REGEX.findAll(routeInfo.fullPath)) {
            pathParams.add(ExtractedParam(m.groupValues[1], ParamLocation.PATH, ""))
        }

        // FastAPI 签名解析
        val fastApi = parseFastApiSignature(signature)
        // Flask 函数体扫描
        val flask = if (fastApi.formParams.isEmpty() && fastApi.queryParams.isEmpty()) {
            scanFlaskBody(lines, defStart)
        } else {
            ExtractedMethodParams()
        }

        // 合并：FastAPI 优先，Flask 补充
        val merged = mergeParams(fastApi, flask, fallbackPathParams = pathParams)

        // 若 FastAPI 没有抓到 path 参数，但 URL 模板里有，就用 URL 解析的 path 参数
        val finalPath = if (merged.pathParams.isEmpty() && pathParams.isNotEmpty()) {
            merged.copy(pathParams = pathParams)
        } else merged

        // 有文件参数时把 query 也合到 form-data
        if (finalPath.formParams.any { it.type == FormFieldType.FILE } && finalPath.queryParams.isNotEmpty()) {
            val moved = finalPath.queryParams.map { ExtractedFormParam(it.name, FormFieldType.TEXT, it.testValue) }
            return finalPath.copy(
                queryParams = emptyList(),
                formParams = finalPath.formParams + moved,
            )
        }

        return finalPath
    }

    private fun mergeParams(
        primary: ExtractedMethodParams,
        secondary: ExtractedMethodParams,
        fallbackPathParams: List<ExtractedParam>,
    ): ExtractedMethodParams {
        val pathParams = if (primary.pathParams.isNotEmpty()) primary.pathParams
        else if (secondary.pathParams.isNotEmpty()) secondary.pathParams
        else fallbackPathParams
        return ExtractedMethodParams(
            queryParams = primary.queryParams + secondary.queryParams,
            pathParams = pathParams,
            headerParams = primary.headerParams + secondary.headerParams,
            formParams = primary.formParams + secondary.formParams,
            bodyJson = primary.bodyJson ?: secondary.bodyJson,
        )
    }

    // ========== def 定位 + 签名抓取 ==========

    private fun findDefLine(lines: List<String>, fromLine: Int): Int? {
        for (i in (fromLine + 1) until minOf(fromLine + 6, lines.size)) {
            if (DEF_REGEX.containsMatchIn(lines[i])) return i
        }
        return null
    }

    private fun readDefSignature(lines: List<String>, defLine: Int): String? {
        val builder = StringBuilder()
        var paren = 0
        var started = false
        for (i in defLine until minOf(defLine + 50, lines.size)) {
            for (ch in lines[i]) {
                when (ch) {
                    '(' -> {
                        if (!started) { started = true; paren = 1; continue }
                        paren++
                        builder.append(ch)
                    }
                    ')' -> {
                        paren--
                        if (paren == 0) return builder.toString()
                        builder.append(ch)
                    }
                    else -> if (started) builder.append(ch)
                }
            }
            if (started) builder.append(' ')
        }
        return null
    }

    // ========== FastAPI 签名解析 ==========

    private fun parseFastApiSignature(signature: String): ExtractedMethodParams {
        val rawParams = splitTopLevel(signature)
        val queryParams = mutableListOf<ExtractedParam>()
        val pathParams = mutableListOf<ExtractedParam>()
        val headerParams = mutableListOf<ExtractedParam>()
        val formParams = mutableListOf<ExtractedFormParam>()
        var bodyJson: String? = null

        // 第一遍判断是否有文件
        val parsedAll = rawParams.mapNotNull { parsePythonParam(it) }
        val hasFile = parsedAll.any { it.kind == PyKind.FILE }

        for (p in parsedAll) {
            when (p.kind) {
                PyKind.FILE -> formParams.add(ExtractedFormParam(p.name, FormFieldType.FILE, ""))
                PyKind.FORM -> formParams.add(ExtractedFormParam(p.name, FormFieldType.TEXT, defaultFor(p.pyType)))
                PyKind.PATH -> pathParams.add(ExtractedParam(p.name, ParamLocation.PATH, defaultFor(p.pyType)))
                PyKind.HEADER -> headerParams.add(ExtractedParam(p.name, ParamLocation.HEADER, ""))
                PyKind.QUERY -> {
                    if (hasFile) formParams.add(ExtractedFormParam(p.name, FormFieldType.TEXT, defaultFor(p.pyType)))
                    else queryParams.add(ExtractedParam(p.name, ParamLocation.QUERY, defaultFor(p.pyType)))
                }
                PyKind.BODY -> if (!hasFile && bodyJson == null) bodyJson = "{}"
                PyKind.SKIP -> Unit
            }
        }

        return ExtractedMethodParams(
            queryParams = queryParams,
            pathParams = pathParams,
            headerParams = headerParams,
            formParams = formParams,
            bodyJson = bodyJson,
        )
    }

    private data class PyParam(val name: String, val pyType: String, val kind: PyKind)
    private enum class PyKind { FILE, FORM, QUERY, PATH, HEADER, BODY, SKIP }

    private fun parsePythonParam(raw: String): PyParam? {
        val text = raw.trim().removePrefix("*").removePrefix("*")
        if (text.isEmpty() || text.startsWith("self") || text.startsWith("cls")) return null

        val nameEnd = indexOfTopLevel(text, ':')
        val name: String
        val rest: String
        if (nameEnd < 0) {
            // 无类型注解：name 或 name=default
            val eq = text.indexOf('=')
            name = if (eq < 0) text.trim() else text.substring(0, eq).trim()
            rest = if (eq < 0) "" else text.substring(eq + 1).trim()
        } else {
            name = text.substring(0, nameEnd).trim()
            rest = text.substring(nameEnd + 1).trim()
        }
        if (name.isBlank()) return null

        val eqIdx = indexOfTopLevel(rest, '=')
        val typePart = if (eqIdx < 0) rest else rest.substring(0, eqIdx).trim()
        val defaultPart = if (eqIdx < 0) "" else rest.substring(eqIdx + 1).trim()

        val typeLower = typePart.lowercase()
        val defaultLower = defaultPart.lowercase()

        // 文件类型判断（类型本身或 default 是 File(...)/UploadFile）
        if (typeContainsFile(typeLower) || defaultLower.startsWith("file(")) {
            return PyParam(name, typePart, PyKind.FILE)
        }

        return when {
            defaultLower.startsWith("form(") -> PyParam(name, typePart, PyKind.FORM)
            defaultLower.startsWith("query(") -> PyParam(name, typePart, PyKind.QUERY)
            defaultLower.startsWith("path(") -> PyParam(name, typePart, PyKind.PATH)
            defaultLower.startsWith("header(") -> PyParam(name, typePart, PyKind.HEADER)
            defaultLower.startsWith("cookie(") -> PyParam(name, typePart, PyKind.SKIP)
            defaultLower.startsWith("body(") -> PyParam(name, typePart, PyKind.BODY)
            defaultLower.startsWith("depends(") -> PyParam(name, typePart, PyKind.SKIP)
            typeLower in PRIMITIVE_TYPES -> PyParam(name, typePart, PyKind.QUERY)
            typeLower.isBlank() -> null
            else -> PyParam(name, typePart, PyKind.BODY)
        }
    }

    private fun typeContainsFile(typeLower: String): Boolean {
        if (typeLower.isBlank()) return false
        return "uploadfile" in typeLower ||
            typeLower == "bytes" || typeLower.endsWith("[bytes]") ||
            typeLower.contains("uploadfile")
    }

    // ========== Flask 函数体扫描 ==========

    private fun scanFlaskBody(lines: List<String>, defLine: Int): ExtractedMethodParams {
        val baseIndent = lines[defLine].indexOfFirst { !it.isWhitespace() }
        if (baseIndent < 0) return ExtractedMethodParams()
        val formParams = linkedMapOf<String, FormFieldType>()
        val queryParams = linkedSetOf<String>()

        // 跳过 def 行本身，直到遇到同级或更外层缩进的非空行才停止
        var i = defLine + 1
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimEnd()
            if (trimmed.isNotEmpty()) {
                val indent = line.indexOfFirst { !it.isWhitespace() }
                if (indent <= baseIndent) break
            }
            for (m in FLASK_FILES_REGEX.findAll(line)) {
                val key = m.firstNonEmptyGroup()
                if (key.isNotBlank()) formParams[key] = FormFieldType.FILE
            }
            for (m in FLASK_FORM_REGEX.findAll(line)) {
                val key = m.firstNonEmptyGroup()
                if (key.isNotBlank()) formParams.putIfAbsent(key, FormFieldType.TEXT)
            }
            for (m in FLASK_ARGS_REGEX.findAll(line)) {
                val key = m.firstNonEmptyGroup()
                if (key.isNotBlank()) queryParams.add(key)
            }
            i++
        }

        return ExtractedMethodParams(
            queryParams = queryParams.map { ExtractedParam(it, ParamLocation.QUERY, "") },
            formParams = formParams.map { (k, v) -> ExtractedFormParam(k, v, "") },
        )
    }

    // ========== 工具 ==========

    private fun splitTopLevel(text: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var paren = 0
        var bracket = 0
        var brace = 0
        var stringChar: Char? = null
        for (ch in text) {
            if (stringChar != null) {
                buf.append(ch)
                if (ch == stringChar) stringChar = null
                continue
            }
            when (ch) {
                '\'', '"' -> { stringChar = ch; buf.append(ch) }
                '(' -> { paren++; buf.append(ch) }
                ')' -> { paren--; buf.append(ch) }
                '[' -> { bracket++; buf.append(ch) }
                ']' -> { bracket--; buf.append(ch) }
                '{' -> { brace++; buf.append(ch) }
                '}' -> { brace--; buf.append(ch) }
                ',' -> if (paren == 0 && bracket == 0 && brace == 0) {
                    result.add(buf.toString().trim())
                    buf.clear()
                } else buf.append(ch)
                else -> buf.append(ch)
            }
        }
        val tail = buf.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result.filter { it.isNotEmpty() }
    }

    private fun indexOfTopLevel(text: String, target: Char): Int {
        var paren = 0; var bracket = 0; var brace = 0
        var stringChar: Char? = null
        for (i in text.indices) {
            val ch = text[i]
            if (stringChar != null) {
                if (ch == stringChar) stringChar = null
                continue
            }
            when (ch) {
                '\'', '"' -> stringChar = ch
                '(' -> paren++
                ')' -> paren--
                '[' -> bracket++
                ']' -> bracket--
                '{' -> brace++
                '}' -> brace--
                target -> if (paren == 0 && bracket == 0 && brace == 0) return i
            }
        }
        return -1
    }

    private fun defaultFor(pyType: String): String {
        val t = pyType.trim().lowercase()
        return when {
            t == "int" || t == "float" -> "1"
            t == "bool" -> "true"
            else -> ""
        }
    }

    private val PRIMITIVE_TYPES = setOf("str", "int", "float", "bool")
    private val DEF_REGEX = Regex("""\b(?:async\s+)?def\s+\w+\s*\(""")
    private val PATH_VAR_REGEX = Regex("""\{(\w+)}""")
    private val FLASK_FILES_REGEX =
        Regex("""request\.files(?:\.get\s*\(\s*['"]([^'"]+)['"]|\[\s*['"]([^'"]+)['"]\s*])""")
    private val FLASK_FORM_REGEX =
        Regex("""request\.form(?:\.get\s*\(\s*['"]([^'"]+)['"]|\[\s*['"]([^'"]+)['"]\s*])""")
    private val FLASK_ARGS_REGEX =
        Regex("""request\.args(?:\.get\s*\(\s*['"]([^'"]+)['"]|\[\s*['"]([^'"]+)['"]\s*])""")

    // 将上面正则的两组合并为一个有效组
    private fun MatchResult.firstNonEmptyGroup(): String =
        groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: ""
}
