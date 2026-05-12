package io.github.movebrickschi.restfulall.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlin.random.Random

/**
 * v1.3 F8 - 内置变量解析。
 *
 * 在 EnvironmentService.resolve 之后再走一遍内置变量替换，
 * 处理 `${random.uuid}` / `${timestamp}` / `${random.int(1,100)}` 等动态值。
 * 每次调用实时生成（不缓存），因此每次发送请求的值不同。
 *
 * ## 支持变量
 *
 * | 语法 | 说明 |
 * |------|------|
 * | `${random.uuid}` | UUID v4 |
 * | `${random.int}` | 0 ~ Int.MAX_VALUE |
 * | `${random.int(min,max)}` | min ~ max 闭区间 |
 * | `${random.string(len)}` | 指定长度字母数字串 |
 * | `${random.string}` | 10 位字母数字串 |
 * | `${timestamp}` | 秒级 Unix 时间戳 |
 * | `${timestamp.ms}` | 毫秒级 Unix 时间戳 |
 * | `${date(pattern)}` | 按 SimpleDateFormat 格式化当前日期 |
 * | `${date}` | yyyy-MM-dd |
 */
object BuiltinVariableResolver {

    private val BUILTIN_PATTERN = Regex("""\$\{(random\.uuid|random\.int(?:\(\d+,\d+\))?|random\.string(?:\(\d+\))?|timestamp(?:\.ms)?|date(?:\([^)]+\))?)}""")
    private val ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun resolve(input: String): String {
        if (!input.contains("\${")) return input
        return BUILTIN_PATTERN.replace(input) { match ->
            resolveToken(match.groupValues[1])
        }
    }

    private fun resolveToken(token: String): String = when {
        token == "random.uuid" -> UUID.randomUUID().toString()
        token == "random.int" -> Random.nextInt(0, Int.MAX_VALUE).toString()
        token.startsWith("random.int(") -> {
            val (min, max) = parseIntRange(token)
            Random.nextInt(min, max + 1).toString()
        }
        token == "random.string" -> randomString(10)
        token.startsWith("random.string(") -> {
            val len = token.removePrefix("random.string(").removeSuffix(")").toIntOrNull() ?: 10
            randomString(len.coerceIn(1, 1000))
        }
        token == "timestamp" -> (System.currentTimeMillis() / 1000).toString()
        token == "timestamp.ms" -> System.currentTimeMillis().toString()
        token == "date" -> SimpleDateFormat("yyyy-MM-dd").format(Date())
        token.startsWith("date(") -> {
            val pattern = token.removePrefix("date(").removeSuffix(")")
            try { SimpleDateFormat(pattern).format(Date()) } catch (_: Exception) { "\${$token}" }
        }
        else -> "\${$token}"
    }

    private fun parseIntRange(token: String): Pair<Int, Int> {
        val inner = token.removePrefix("random.int(").removeSuffix(")")
        val parts = inner.split(",")
        val min = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val max = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: Int.MAX_VALUE
        return if (min <= max) min to max else max to min
    }

    private fun randomString(length: Int): String =
        (1..length).map { ALPHANUM[Random.nextInt(ALPHANUM.length)] }.joinToString("")
}
