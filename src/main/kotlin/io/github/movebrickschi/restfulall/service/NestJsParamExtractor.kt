package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.ExtractedFormParam
import io.github.movebrickschi.restfulall.model.ExtractedMethodParams
import io.github.movebrickschi.restfulall.model.ExtractedParam
import io.github.movebrickschi.restfulall.model.FormFieldType
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.ParamLocation
import io.github.movebrickschi.restfulall.model.RouteInfo

/**
 * NestJS 参数提取：基于正则解析方法签名内的参数装饰器。
 *
 * 支持的装饰器：
 *   @UploadedFile()  / @UploadedFile('field')              -> form file
 *   @UploadedFiles() / @UploadedFiles('field')             -> form file
 *   @Body() body: Dto                                       -> json body
 *   @Body('field') value                                    -> form text (与上同 form 时归并)
 *   @Query('name') name                                     -> query
 *   @Param('id')   id                                       -> path
 *   @Headers('x')  h                                        -> header
 *
 * 任意 @UploadedFile/@UploadedFiles 出现时，所有非 path/header 参数统一进 form-data。
 */
object NestJsParamExtractor {

    fun extract(routeInfo: RouteInfo): ExtractedMethodParams? {
        if (routeInfo.framework != Framework.NESTJS) return null

        val content = try {
            String(routeInfo.file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        val lines = content.lines()
        val signature = readMethodSignature(lines, routeInfo) ?: return null
        val rawParams = splitTopLevel(signature)

        val parsed = rawParams.mapNotNull { parseParam(it) }
        if (parsed.isEmpty()) return ExtractedMethodParams()

        val hasFile = parsed.any { it.kind == NestParamKind.FILE_SINGLE || it.kind == NestParamKind.FILE_MULTI }

        val queryParams = mutableListOf<ExtractedParam>()
        val pathParams = mutableListOf<ExtractedParam>()
        val headerParams = mutableListOf<ExtractedParam>()
        val formParams = mutableListOf<ExtractedFormParam>()
        var bodyJson: String? = null

        for (p in parsed) {
            when (p.kind) {
                NestParamKind.FILE_SINGLE,
                NestParamKind.FILE_MULTI -> formParams.add(ExtractedFormParam(p.name, FormFieldType.FILE, ""))
                NestParamKind.PATH -> pathParams.add(ExtractedParam(p.name, ParamLocation.PATH, defaultValueFor(p.tsType)))
                NestParamKind.HEADER -> headerParams.add(ExtractedParam(p.name, ParamLocation.HEADER, ""))
                NestParamKind.QUERY -> {
                    if (hasFile) formParams.add(ExtractedFormParam(p.name, FormFieldType.TEXT, defaultValueFor(p.tsType)))
                    else queryParams.add(ExtractedParam(p.name, ParamLocation.QUERY, defaultValueFor(p.tsType)))
                }
                NestParamKind.BODY_FIELD -> {
                    if (hasFile) formParams.add(ExtractedFormParam(p.name, FormFieldType.TEXT, defaultValueFor(p.tsType)))
                    else queryParams.add(ExtractedParam(p.name, ParamLocation.QUERY, defaultValueFor(p.tsType)))
                }
                NestParamKind.BODY_OBJECT -> {
                    if (hasFile) {
                        // multipart 模式下整对象 body 暂不展开，仅放一个占位字段
                        formParams.add(ExtractedFormParam(p.name, FormFieldType.TEXT, ""))
                    } else if (bodyJson == null) {
                        bodyJson = "{}"
                    }
                }
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

    // ========== 方法签名定位 ==========

    /**
     * 从路由所在行向下找到该路由对应的方法签名，把 `(...)` 之间的内容拼成单行返回。
     */
    private fun readMethodSignature(lines: List<String>, routeInfo: RouteInfo): String? {
        var startLine = -1
        for (i in routeInfo.lineNumber until minOf(routeInfo.lineNumber + 15, lines.size)) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("@") || trimmed.isEmpty()) continue
            if (METHOD_NAME_REGEX.containsMatchIn(trimmed)) {
                startLine = i
                break
            }
        }
        if (startLine < 0) return null

        val builder = StringBuilder()
        var depth = 0
        var inSignature = false
        for (i in startLine until lines.size) {
            for (ch in lines[i]) {
                when (ch) {
                    '(' -> {
                        if (!inSignature) {
                            inSignature = true
                            depth = 1
                            continue
                        }
                        depth++
                        builder.append(ch)
                    }
                    ')' -> {
                        depth--
                        if (depth == 0) return builder.toString()
                        builder.append(ch)
                    }
                    else -> if (inSignature) builder.append(ch)
                }
            }
            if (inSignature) builder.append(' ')
            if (i - startLine > 20) break
        }
        return null
    }

    /**
     * 在顶层逗号处切分参数列表，跳过 ()/{}/[]/<> 与字符串内的逗号。
     */
    private fun splitTopLevel(text: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var paren = 0
        var brace = 0
        var bracket = 0
        var angle = 0
        var stringChar: Char? = null
        for (ch in text) {
            if (stringChar != null) {
                buf.append(ch)
                if (ch == stringChar && buf.length >= 2 && buf[buf.length - 2] != '\\') stringChar = null
                continue
            }
            when (ch) {
                '\'', '"', '`' -> { stringChar = ch; buf.append(ch) }
                '(' -> { paren++; buf.append(ch) }
                ')' -> { paren--; buf.append(ch) }
                '{' -> { brace++; buf.append(ch) }
                '}' -> { brace--; buf.append(ch) }
                '[' -> { bracket++; buf.append(ch) }
                ']' -> { bracket--; buf.append(ch) }
                '<' -> { angle++; buf.append(ch) }
                '>' -> { if (angle > 0) angle--; buf.append(ch) }
                ',' -> if (paren == 0 && brace == 0 && bracket == 0 && angle == 0) {
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

    // ========== 单个参数解析 ==========

    private data class ParsedParam(
        val name: String,
        val tsType: String,
        val kind: NestParamKind,
    )

    private enum class NestParamKind { FILE_SINGLE, FILE_MULTI, BODY_OBJECT, BODY_FIELD, QUERY, PATH, HEADER }

    private fun parseParam(raw: String): ParsedParam? {
        // 抓取最后一个装饰器之后的 "name: Type"
        val text = raw.trim()
        val decoratorMatch = LAST_DECORATOR_REGEX.find(text)
        val decorator = decoratorMatch?.groupValues?.get(1)
        val decoratorArg = decoratorMatch?.groupValues?.get(2)?.trim()

        val rest = (decoratorMatch?.let { text.substring(it.range.last + 1) } ?: text).trim()
        val nameTypeMatch = NAME_TYPE_REGEX.find(rest)
        val rawName = nameTypeMatch?.groupValues?.get(1)?.trim()?.removePrefix("...")
        val tsType = nameTypeMatch?.groupValues?.get(2)?.trim() ?: ""

        val explicitName = decoratorArg?.let { stripQuotes(it) }?.takeIf { it.isNotBlank() }
        val finalName = explicitName ?: rawName ?: return null

        val kind = when (decorator) {
            "UploadedFile" -> NestParamKind.FILE_SINGLE
            "UploadedFiles" -> NestParamKind.FILE_MULTI
            "Body" -> if (explicitName != null) NestParamKind.BODY_FIELD else NestParamKind.BODY_OBJECT
            "Query" -> NestParamKind.QUERY
            "Param" -> NestParamKind.PATH
            "Headers" -> NestParamKind.HEADER
            else -> return null  // @Req / @Res / @Session / @Ip 等未识别一律跳过
        }

        return ParsedParam(finalName, tsType, kind)
    }

    private fun stripQuotes(s: String): String {
        val t = s.trim()
        if (t.length >= 2 && (t.first() == t.last()) && (t.first() == '\'' || t.first() == '"' || t.first() == '`')) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    private fun defaultValueFor(tsType: String): String {
        val t = tsType.lowercase()
        return when {
            t.startsWith("number") -> "1"
            t.startsWith("boolean") -> "true"
            else -> ""
        }
    }

    private val METHOD_NAME_REGEX =
        Regex("""(?:public|private|protected|async|static|\s)*\b(\w+)\s*\(""")

    private val LAST_DECORATOR_REGEX =
        Regex("""@(UploadedFile|UploadedFiles|Body|Query|Param|Headers)\s*\(([^)]*)\)""")

    private val NAME_TYPE_REGEX =
        Regex("""([\w$.]+)\s*\??\s*:\s*([^=]+?)(?:\s*=\s*.+)?$""")
}
