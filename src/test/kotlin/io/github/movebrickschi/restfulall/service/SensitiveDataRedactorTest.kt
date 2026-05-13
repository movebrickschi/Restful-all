package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.ParamEntry
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.1 - SensitiveDataRedactor 纯静态单测。
 *
 * 不依赖 IntelliJ Platform；专测命中策略与值替换。
 */
class SensitiveDataRedactorTest {

    @Test
    fun `header named Authorization is redacted`() {
        val r = SensitiveDataRedactor.redactHeader(ParamEntry(true, "Authorization", "Bearer abc"))
        assertEquals(SensitiveDataRedactor.REDACTED, r.value)
        assertEquals("Authorization", r.name)
        assertEquals(true, r.enabled)
    }

    @Test
    fun `header named Cookie is redacted`() {
        val r = SensitiveDataRedactor.redactHeader(ParamEntry(true, "cookie", "sid=xxx"))
        assertEquals(SensitiveDataRedactor.REDACTED, r.value)
    }

    @Test
    fun `header containing token in name is redacted`() {
        listOf("X-Auth-Token", "X-Api-Key", "X-Session-Id", "X-Csrf-Token").forEach { name ->
            val r = SensitiveDataRedactor.redactHeader(ParamEntry(true, name, "secretValue"))
            assertEquals("name=$name should redact", SensitiveDataRedactor.REDACTED, r.value)
        }
    }

    @Test
    fun `header content-type is preserved as is`() {
        val original = ParamEntry(true, "Content-Type", "application/json")
        val r = SensitiveDataRedactor.redactHeader(original)
        assertSame("non-sensitive header should not allocate copy", original, r)
    }

    @Test
    fun `param named password or token is redacted`() {
        listOf("password", "access_token", "api_key", "secret", "signature").forEach { name ->
            val r = SensitiveDataRedactor.redactParam(ParamEntry(true, name, "v"))
            assertEquals("name=$name should redact", SensitiveDataRedactor.REDACTED, r.value)
        }
    }

    @Test
    fun `param named pageSize is preserved`() {
        val original = ParamEntry(true, "pageSize", "20")
        val r = SensitiveDataRedactor.redactParam(original)
        assertSame(original, r)
    }

    @Test
    fun `url query string redacts api_key and token`() {
        val redacted = SensitiveDataRedactor.redactUrlQuery(
            "https://api.example.com/v1/u?id=1&api_key=AKIAxxx&token=ey.J&page=2",
        )
        assertTrue(redacted.contains("id=1"))
        assertTrue(redacted.contains("page=2"))
        assertTrue(redacted.contains("api_key=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(redacted.contains("token=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(!redacted.contains("AKIAxxx"))
        assertTrue(!redacted.contains("ey.J"))
    }

    @Test
    fun `url without query string is unchanged`() {
        val url = "https://api.example.com/v1/u"
        assertEquals(url, SensitiveDataRedactor.redactUrlQuery(url))
    }

    @Test
    fun `body json redacts password and token fields`() {
        val body = """{"username":"alice","password":"p@ss","access_token":"eyJabc","other":"keep"}"""
        val redacted = SensitiveDataRedactor.redactBody(body)
        assertTrue(redacted.contains("\"password\":\"${SensitiveDataRedactor.REDACTED}\""))
        assertTrue(redacted.contains("\"access_token\":\"${SensitiveDataRedactor.REDACTED}\""))
        assertTrue(redacted.contains("\"username\":\"alice\""))
        assertTrue(redacted.contains("\"other\":\"keep\""))
        assertTrue(!redacted.contains("p@ss"))
        assertTrue(!redacted.contains("eyJabc"))
    }

    @Test
    fun `body without json sensitive keys is unchanged`() {
        val body = """{"foo":"bar","count":3}"""
        assertEquals(body, SensitiveDataRedactor.redactBody(body))
    }

    @Test
    fun `redact does not mutate original entry`() {
        val original = RequestHistoryEntry(
            url = "https://api.example.com?token=abc",
            headers = mutableListOf(ParamEntry(true, "Authorization", "Bearer x")),
            cookies = mutableListOf(ParamEntry(true, "session", "raw")),
            body = """{"password":"p"}""",
        )
        val redacted = SensitiveDataRedactor.redact(original)
        assertNotEquals(original.url, redacted.url)
        assertEquals("Bearer x", original.headers[0].value)
        assertEquals(SensitiveDataRedactor.REDACTED, redacted.headers[0].value)
        assertEquals("raw", original.cookies[0].value)
        assertEquals(SensitiveDataRedactor.REDACTED, redacted.cookies[0].value)
        assertTrue(original.body.contains("\"p\""))
        assertTrue(redacted.body.contains(SensitiveDataRedactor.REDACTED))
    }

    @Test
    fun `redact preserves response status and elapsed`() {
        val original = RequestHistoryEntry(
            url = "https://x.com",
            responseStatus = 401,
            elapsed = 123L,
            timestamp = 999L,
        )
        val redacted = SensitiveDataRedactor.redact(original)
        assertEquals(401, redacted.responseStatus)
        assertEquals(123L, redacted.elapsed)
        assertEquals(999L, redacted.timestamp)
    }

    @Test
    fun `blank values are not replaced with REDACTED placeholder`() {
        val r = SensitiveDataRedactor.redactHeader(ParamEntry(true, "Authorization", ""))
        assertEquals("", r.value)
    }
}
