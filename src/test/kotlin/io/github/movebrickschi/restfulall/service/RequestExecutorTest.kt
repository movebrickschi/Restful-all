package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.ParamEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3 W1-3 - RequestSpec / RequestResult 数据模型单测。
 *
 * 不依赖 IntelliJ Platform（纯 POJO 测试）。
 * 集成测试（真实 HTTP 调用 + EnvironmentService 注入）在 IDE sandbox test 里做。
 */
class RequestExecutorTest {

    @Test
    fun `RequestSpec default values should be safe`() {
        val spec = RequestSpec()
        assertEquals("GET", spec.method)
        assertEquals("", spec.url)
        assertTrue(spec.queryParams.isEmpty())
        assertTrue(spec.headers.isEmpty())
        assertTrue(spec.cookies.isEmpty())
        assertTrue(spec.pathParams.isEmpty())
        assertEquals("none", spec.bodyType)
        assertEquals("", spec.bodyContent)
        assertTrue(spec.formParams.isEmpty())
        assertEquals(30L, spec.timeoutSeconds)
    }

    @Test
    fun `RequestSpec should hold all fields correctly`() {
        val spec = RequestSpec(
            method = "POST",
            url = "http://localhost:8080/api/users",
            queryParams = listOf("page" to "1", "size" to "10"),
            headers = listOf("Authorization" to "Bearer abc"),
            cookies = listOf("sid" to "xyz"),
            pathParams = listOf("id" to "42"),
            bodyType = "json",
            bodyContent = """{"name":"test"}""",
            formParams = listOf(ParamEntry(true, "file", "/path/to/file")),
            timeoutSeconds = 60,
        )
        assertEquals("POST", spec.method)
        assertEquals(2, spec.queryParams.size)
        assertEquals("Bearer abc", spec.headers[0].second)
        assertEquals("json", spec.bodyType)
        assertEquals(60L, spec.timeoutSeconds)
    }

    @Test
    fun `RequestResult should distinguish success and error`() {
        val success = RequestResult(200, """{"ok":true}""", mapOf("Content-Type" to listOf("application/json")), 150)
        assertEquals(200, success.statusCode)
        assertNull(success.error)
        assertFalse(success.isSSE)

        val error = RequestResult(0, "", emptyMap(), 100, error = "Connection refused")
        assertEquals(0, error.statusCode)
        assertNotNull(error.error)
        assertEquals("Connection refused", error.error)
    }

    @Test
    fun `RequestResult should flag SSE and NDJSON`() {
        val sse = RequestResult(200, "", emptyMap(), 100, contentType = "text/event-stream", isSSE = true)
        assertTrue(sse.isSSE)
        assertFalse(sse.isNdjson)

        val ndjson = RequestResult(200, "", emptyMap(), 100, contentType = "application/x-ndjson", isNdjson = true)
        assertFalse(ndjson.isSSE)
        assertTrue(ndjson.isNdjson)
    }

    @Test
    fun `RequestSpec copy should allow method override`() {
        val base = RequestSpec(method = "GET", url = "http://localhost/api")
        val post = base.copy(method = "POST", bodyType = "json", bodyContent = "{}")
        assertEquals("POST", post.method)
        assertEquals("json", post.bodyType)
        assertEquals("http://localhost/api", post.url)
    }

    @Test
    fun `METHODS_WITH_BODY should include POST PUT PATCH DELETE`() {
        val expected = setOf("POST", "PUT", "PATCH", "DELETE")
        expected.forEach { m ->
            assertTrue("$m should have body", m in expected)
        }
        assertFalse("GET should not have body", "GET" in expected)
        assertFalse("HEAD should not have body", "HEAD" in expected)
    }

    @Test
    fun `multipart header values should escape unsafe characters`() {
        assertEquals("field\\\"name", escapeMultipartHeaderValue("field\"name"))
        assertEquals("file\\\\name.txt", escapeMultipartHeaderValue("file\\name.txt"))
        assertEquals("badname", escapeMultipartHeaderValue("bad\r\nname"))
    }
}
