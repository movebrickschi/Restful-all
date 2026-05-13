package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.Assertion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssertionEngineTest {

    private val sampleBody = """{"code":0,"message":"ok","data":{"users":[{"id":1,"name":"alice"},{"id":2,"name":"bob"}]}}"""

    private fun eval(
        assertion: Assertion,
        body: String = sampleBody,
        statusCode: Int = 200,
        elapsedMs: Long = 120L,
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json")),
    ) = AssertionEngine.evaluate(assertion, body, statusCode, elapsedMs, headers)

    @Test
    fun `jsonpath equals passes`() {
        val a = Assertion(expression = "$.code", operator = Assertion.Operator.EQUALS, expected = "0")
        val r = eval(a)
        assertTrue(r.passed)
        assertEquals("0", r.actual)
    }

    @Test
    fun `jsonpath equals fails when value differs`() {
        val a = Assertion(expression = "$.code", operator = Assertion.Operator.EQUALS, expected = "1")
        val r = eval(a)
        assertFalse(r.passed)
    }

    @Test
    fun `jsonpath not exists succeeds for missing path`() {
        val a = Assertion(expression = "$.missing.deep", operator = Assertion.Operator.NOT_EXISTS)
        val r = eval(a)
        assertTrue(r.passed)
        assertNull(r.actual)
    }

    @Test
    fun `jsonpath exists fails for missing path`() {
        val a = Assertion(expression = "$.missing", operator = Assertion.Operator.EXISTS)
        val r = eval(a)
        assertFalse(r.passed)
    }

    @Test
    fun `status code equals`() {
        val a = Assertion(source = Assertion.Source.STATUS_CODE, operator = Assertion.Operator.EQUALS, expected = "200")
        val r = eval(a, statusCode = 200)
        assertTrue(r.passed)
    }

    @Test
    fun `status code greater than`() {
        val a = Assertion(source = Assertion.Source.STATUS_CODE, operator = Assertion.Operator.GREATER_THAN, expected = "399")
        val r = eval(a, statusCode = 404)
        assertTrue(r.passed)
    }

    @Test
    fun `header contains case insensitive`() {
        val a = Assertion(
            source = Assertion.Source.HEADER,
            expression = "content-type",
            operator = Assertion.Operator.CONTAINS,
            expected = "json",
        )
        val r = eval(a)
        assertTrue(r.passed)
        assertEquals("application/json", r.actual)
    }

    @Test
    fun `response time less than`() {
        val a = Assertion(
            source = Assertion.Source.RESPONSE_TIME_MS,
            operator = Assertion.Operator.LESS_THAN,
            expected = "500",
        )
        val r = eval(a, elapsedMs = 120L)
        assertTrue(r.passed)
    }

    @Test
    fun `matches regex on jsonpath value`() {
        val a = Assertion(
            expression = "$.data.users[0].name",
            operator = Assertion.Operator.MATCHES_REGEX,
            expected = "^a.*e$",
        )
        val r = eval(a)
        assertTrue(r.passed)
    }

    @Test
    fun `disabled assertion always passes`() {
        val a = Assertion(
            enabled = false,
            expression = "$.code",
            operator = Assertion.Operator.EQUALS,
            expected = "999",
        )
        val r = eval(a)
        assertTrue(r.passed)
    }

    @Test
    fun `invalid jsonpath expression captured as error`() {
        val a = Assertion(expression = "\$.[", operator = Assertion.Operator.EXISTS)
        val r = eval(a)
        assertFalse(r.passed)
        assertTrue("expected error captured, got=${r.error}", r.error != null)
    }
}
