package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.diagnostic.Logger
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.PathNotFoundException
import io.github.movebrickschi.restfulall.model.Assertion
import io.github.movebrickschi.restfulall.model.AssertionResult

/**
 * F7: 响应断言执行引擎。
 *
 * 输入：一个 [Assertion] + 必要的响应字段（body / statusCode / elapsed / headers）；
 * 输出：[AssertionResult]。
 *
 * 设计：纯函数 + 单例 object，方便测试、可在任意线程调（无共享可变状态）。
 *
 * JSON Path 走 jayway，使用 [Option.SUPPRESS_EXCEPTIONS] 让 `$.missing.deep.path` 返回 null
 * 而不抛 [PathNotFoundException]，统一交给 EXISTS / NOT_EXISTS 算子判定。
 */
object AssertionEngine {

    private val LOG = Logger.getInstance(AssertionEngine::class.java)

    private val JSON_PATH_CONFIG = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS, Option.DEFAULT_PATH_LEAF_TO_NULL)

    /**
     * 执行单条断言。
     *
     * @param body 响应正文。JSON_PATH 取值时需要；其它来源可为空字符串
     * @param statusCode HTTP 状态码（如 200 / 404 / 500）
     * @param elapsedMs 响应耗时毫秒
     * @param headers HTTP 响应头（key 不限大小写；查询时按 case-insensitive 匹配）
     */
    fun evaluate(
        assertion: Assertion,
        body: String,
        statusCode: Int,
        elapsedMs: Long,
        headers: Map<String, List<String>>,
    ): AssertionResult {
        if (!assertion.enabled) {
            return AssertionResult(assertion, passed = true, actual = null)
        }
        return try {
            val actual = extractActual(assertion, body, statusCode, elapsedMs, headers)
            val passed = applyOperator(assertion.operator, actual, assertion.expected)
            AssertionResult(assertion, passed = passed, actual = actual)
        } catch (e: Exception) {
            LOG.debug("AssertionEngine.evaluate failed for assertion=${assertion.id}", e)
            AssertionResult(assertion, passed = false, actual = null, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 批量执行；遇到单条异常不影响其它断言。
     */
    fun evaluateAll(
        assertions: List<Assertion>,
        body: String,
        statusCode: Int,
        elapsedMs: Long,
        headers: Map<String, List<String>>,
    ): List<AssertionResult> = assertions.map { evaluate(it, body, statusCode, elapsedMs, headers) }

    private fun extractActual(
        assertion: Assertion,
        body: String,
        statusCode: Int,
        elapsedMs: Long,
        headers: Map<String, List<String>>,
    ): String? {
        return when (assertion.source) {
            Assertion.Source.STATUS_CODE -> statusCode.toString()
            Assertion.Source.RESPONSE_TIME_MS -> elapsedMs.toString()
            Assertion.Source.HEADER -> {
                val name = assertion.expression.trim()
                val match = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                match?.value?.firstOrNull()
            }
            Assertion.Source.JSON_PATH -> evalJsonPath(body, assertion.expression)
        }
    }

    private fun evalJsonPath(body: String, expression: String): String? {
        if (body.isBlank()) return null
        val raw: Any? = try {
            JsonPath.using(JSON_PATH_CONFIG).parse(body).read(expression)
        } catch (_: PathNotFoundException) {
            null
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON Path '$expression' evaluation failed: ${e.message}", e)
        }
        return raw?.toString()
    }

    private fun applyOperator(operator: Assertion.Operator, actual: String?, expected: String): Boolean {
        return when (operator) {
            Assertion.Operator.EXISTS -> actual != null
            Assertion.Operator.NOT_EXISTS -> actual == null
            Assertion.Operator.EQUALS -> actual == expected
            Assertion.Operator.NOT_EQUALS -> actual != expected
            Assertion.Operator.CONTAINS -> actual != null && actual.contains(expected)
            Assertion.Operator.NOT_CONTAINS -> actual == null || !actual.contains(expected)
            Assertion.Operator.GREATER_THAN -> compareNumeric(actual, expected) { a, e -> a > e }
            Assertion.Operator.LESS_THAN -> compareNumeric(actual, expected) { a, e -> a < e }
            Assertion.Operator.MATCHES_REGEX -> {
                if (actual == null) return false
                try {
                    Regex(expected).containsMatchIn(actual)
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private fun compareNumeric(
        actual: String?,
        expected: String,
        op: (Double, Double) -> Boolean,
    ): Boolean {
        if (actual == null) return false
        val a = actual.toDoubleOrNull() ?: return false
        val e = expected.toDoubleOrNull() ?: return false
        return op(a, e)
    }
}
