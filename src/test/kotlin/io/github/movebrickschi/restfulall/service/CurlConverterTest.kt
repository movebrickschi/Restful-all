package io.github.movebrickschi.restfulall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlConverterTest {

    @Test
    fun `parse simple GET`() {
        val spec = CurlConverter.parse("curl http://localhost:8080/api/users")
        assertEquals("GET", spec.method)
        assertEquals("http://localhost:8080/api/users", spec.url)
    }

    @Test
    fun `parse POST with json body`() {
        val spec = CurlConverter.parse("""curl -X POST http://localhost/api -H 'Content-Type: application/json' -d '{"name":"test"}'""")
        assertEquals("POST", spec.method)
        assertEquals("json", spec.bodyType)
        assertEquals("""{"name":"test"}""", spec.bodyContent)
    }

    @Test
    fun `parse with bearer auth header`() {
        val spec = CurlConverter.parse("""curl -H 'Authorization: Bearer abc123' http://localhost/api""")
        assertEquals("Bearer abc123", spec.headers.first { it.first == "Authorization" }.second)
    }

    @Test
    fun `parse with basic auth flag`() {
        val spec = CurlConverter.parse("""curl -u user:pass http://localhost/api""")
        val authHeader = spec.headers.first { it.first == "Authorization" }.second
        assertTrue(authHeader.startsWith("Basic "))
    }

    @Test
    fun `parse with cookies`() {
        val spec = CurlConverter.parse("""curl -b 'sid=abc; token=xyz' http://localhost/api""")
        assertEquals(2, spec.cookies.size)
        assertEquals("abc", spec.cookies[0].second)
    }

    @Test
    fun `parse data without explicit method should default to POST`() {
        val spec = CurlConverter.parse("""curl -d 'key=value' http://localhost/api""")
        assertEquals("POST", spec.method)
    }

    @Test
    fun `parse quoted URL`() {
        val spec = CurlConverter.parse("""curl 'http://localhost:8080/api?q=hello world'""")
        assertEquals("http://localhost:8080/api?q=hello world", spec.url)
    }

    @Test(expected = CurlConverter.CurlParseException::class)
    fun `parse empty input should throw`() {
        CurlConverter.parse("")
    }

    @Test(expected = CurlConverter.CurlParseException::class)
    fun `parse non-curl input should throw`() {
        CurlConverter.parse("wget http://example.com")
    }

    @Test
    fun `export simple GET`() {
        val spec = RequestSpec(url = "http://localhost/api")
        val curl = CurlConverter.export(spec)
        assertEquals("curl 'http://localhost/api'", curl)
    }

    @Test
    fun `export POST with json`() {
        val spec = RequestSpec(
            method = "POST",
            url = "http://localhost/api",
            headers = listOf("Content-Type" to "application/json"),
            bodyType = "json",
            bodyContent = """{"name":"test"}""",
        )
        val curl = CurlConverter.export(spec)
        assertTrue(curl.startsWith("curl -X POST"))
        assertTrue(curl.contains("-H 'Content-Type: application/json'"))
        assertTrue(curl.contains("""-d '{"name":"test"}'"""))
    }

    @Test
    fun `export with query params`() {
        val spec = RequestSpec(
            url = "http://localhost/api",
            queryParams = listOf("page" to "1", "size" to "10"),
        )
        val curl = CurlConverter.export(spec)
        assertTrue(curl.contains("page=1"))
        assertTrue(curl.contains("size=10"))
    }

    @Test
    fun `round-trip parse then export`() {
        val original = """curl -X PUT 'http://localhost/api/1' -H 'Content-Type: application/json' -d '{"name":"updated"}'"""
        val spec = CurlConverter.parse(original)
        val exported = CurlConverter.export(spec)
        assertTrue(exported.contains("-X PUT"))
        assertTrue(exported.contains("http://localhost/api/1"))
    }

    @Test
    fun `tokenize handles mixed quotes`() {
        val tokens = CurlConverter.tokenize("""curl -H "Authorization: Bearer abc" 'http://example.com'""")
        assertEquals(4, tokens.size)
        assertEquals("Authorization: Bearer abc", tokens[2])
    }
}
