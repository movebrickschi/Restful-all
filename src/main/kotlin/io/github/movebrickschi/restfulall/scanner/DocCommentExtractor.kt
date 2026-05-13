package io.github.movebrickschi.restfulall.scanner

/**
 * 从路由声明（注解 / 装饰器 / 路由调用）上方回溯抽取文档注释纯文本。
 *
 * 设计取舍：
 * - 走文本回溯而非 PSI，与现有 `SpringRouteScanner` / `NestJsRouteScanner` 的正则扫描风格保持一致，
 *   不引入新依赖、不在后台扫描线程上动 PSI（避免与索引线程竞争 ReadAction）。
 * - 只清洗成纯文本：剥 KDoc 开头/结尾标记与每行的星号前缀，丢弃常见 JavaDoc tag 行
 *   （`@param` / `@return` / `@throws` / `@author` / `@see` / `@since` 等）。
 * - 不做 Markdown / HTML 渲染；后续 UI 用纯文本 + 简单 word-wrap。
 *
 * 抽取失败返回 `null`（而非空串），UI 据此隐藏说明栏。
 */
object DocCommentExtractor {

    /**
     * C-style 语言（Java / Kotlin / TS / JS）：从 [annotationLine] 向上找最近的 javadoc 注释块。
     *
     * 回溯规则：
     * 1. 跳过空行
     * 2. 跳过其它注解 / 装饰器行（以 `@` 开头）
     * 3. 命中注释块结尾标记 → 继续向上找匹配的开头标记 → 抽取并清洗
     * 4. 命中其它代码 → 停止，返回 null
     *
     * 单行注释（开头与结尾标记同一行）也支持。
     */
    fun extractJavaDocAbove(lines: List<String>, annotationLine: Int): String? {
        if (annotationLine <= 0) return null

        var cursor = annotationLine - 1
        while (cursor >= 0) {
            val trimmed = lines[cursor].trim()
            when {
                trimmed.isEmpty() -> cursor--
                trimmed.startsWith("@") -> cursor--
                trimmed.endsWith("*/") -> {
                    val endLine = cursor
                    val singleLine = SINGLE_LINE_JAVADOC.matchEntire(trimmed)
                    if (singleLine != null) {
                        return cleanLine(singleLine.groupValues[1]).takeIf { it.isNotBlank() }
                    }
                    var start = cursor - 1
                    while (start >= 0 && !lines[start].trim().startsWith("/**")) {
                        start--
                    }
                    if (start < 0) return null
                    return cleanBlock(lines.subList(start, endLine + 1))
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * Python：从路由装饰器行向下找下一个 `def` 行，再看其下一行是否为 docstring。
     *
     * 支持三引号双引号与三引号单引号包裹的 docstring，单行与多行。
     * `defLine` 自身可被传入（如果调用方已知道 def 行），否则向下在 [annotationLine]+1..+10 之间查找。
     */
    fun extractPythonDocstring(lines: List<String>, annotationLine: Int): String? {
        var defLine = -1
        for (i in (annotationLine + 1) until minOf(annotationLine + 10, lines.size)) {
            val t = lines[i].trim()
            if (t.startsWith("@") || t.isEmpty()) continue
            if (t.startsWith("def ") || t.startsWith("async def ")) {
                defLine = i
                break
            }
            return null
        }
        if (defLine < 0) return null

        for (i in (defLine + 1) until minOf(defLine + 4, lines.size)) {
            val t = lines[i].trim()
            if (t.isEmpty()) continue
            return readDocstringStartingAt(lines, i)
        }
        return null
    }

    private fun readDocstringStartingAt(lines: List<String>, startLine: Int): String? {
        val first = lines[startLine].trim()
        val openQuote = when {
            first.startsWith("\"\"\"") -> "\"\"\""
            first.startsWith("'''") -> "'''"
            else -> return null
        }
        val firstStripped = first.removePrefix(openQuote)
        if (firstStripped.endsWith(openQuote) && firstStripped.length >= openQuote.length) {
            val content = firstStripped.removeSuffix(openQuote).trim()
            return content.takeIf { it.isNotBlank() }
        }

        val sb = StringBuilder()
        if (firstStripped.isNotBlank()) sb.appendLine(firstStripped.trim())
        var i = startLine + 1
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trim()
            if (t.endsWith(openQuote)) {
                val tail = t.removeSuffix(openQuote).trim()
                if (tail.isNotBlank()) sb.appendLine(tail)
                break
            }
            sb.appendLine(raw.trimEnd())
            i++
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun cleanBlock(blockLines: List<String>): String? {
        val sb = StringBuilder()
        for ((idx, raw) in blockLines.withIndex()) {
            var line = raw.trim()
            if (idx == 0) line = line.removePrefix("/**").removePrefix("/*")
            if (idx == blockLines.lastIndex) line = line.removeSuffix("*/")
            line = line.trim()
            if (line.startsWith("*")) line = line.removePrefix("*").trim()
            if (line.isEmpty()) {
                if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                continue
            }
            if (shouldDropTagLine(line)) continue
            sb.append(line).append('\n')
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun cleanLine(content: String): String =
        content.trim().removePrefix("*").trim()

    private fun shouldDropTagLine(line: String): Boolean {
        val l = line.trimStart()
        return DROPPED_TAGS.any { l.startsWith(it) }
    }

    private val DROPPED_TAGS = listOf(
        "@param", "@return", "@throws", "@exception",
        "@author", "@see", "@since", "@version",
        "@serial", "@hidden",
    )

    private val SINGLE_LINE_JAVADOC = Regex("""^/\*\*\s*(.*?)\s*\*/$""")
}
