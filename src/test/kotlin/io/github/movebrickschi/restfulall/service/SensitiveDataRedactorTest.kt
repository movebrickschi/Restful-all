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

    @Test
    fun `body redacts bearer_token pwd refresh_token private_key fields`() {
        val body = """{"bearer_token":"abc","pwd":"123","refresh_token":"r1","private_key":"pem","keep":"yes"}"""
        val redacted = SensitiveDataRedactor.redactBody(body)
        assertTrue(redacted.contains("\"bearer_token\":\"${SensitiveDataRedactor.REDACTED}\""))
        assertTrue(redacted.contains("\"pwd\":\"${SensitiveDataRedactor.REDACTED}\""))
        assertTrue(redacted.contains("\"refresh_token\":\"${SensitiveDataRedactor.REDACTED}\""))
        assertTrue(redacted.contains("\"private_key\":\"${SensitiveDataRedactor.REDACTED}\""))
        assertTrue(redacted.contains("\"keep\":\"yes\""))
        assertTrue(!redacted.contains("abc"))
        assertTrue(!redacted.contains("123"))
        assertTrue(!redacted.contains("\"r1\""))
        assertTrue(!redacted.contains("\"pem\""))
    }

    @Test
    fun `body redacts form-urlencoded password and api_key`() {
        val body = "username=alice&password=p%40ss&api_key=AKIAxxx&count=3"
        val redacted = SensitiveDataRedactor.redactBody(body)
        assertTrue(redacted.contains("password=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(redacted.contains("api_key=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(redacted.contains("username=alice"))
        assertTrue(redacted.contains("count=3"))
        assertTrue(!redacted.contains("p%40ss"))
        assertTrue(!redacted.contains("AKIAxxx"))
    }

    @Test
    fun `redactPlainText handles error message with embedded url`() {
        val raw = "Could not resolve host: api.example.com/v1/u?api_key=AKIAxxx&token=eyJ.J&page=2"
        val redacted = SensitiveDataRedactor.redactPlainText(raw)
        assertTrue(redacted.contains("api_key=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(redacted.contains("token=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(redacted.contains("page=2"))
        assertTrue(!redacted.contains("AKIAxxx"))
        assertTrue(!redacted.contains("eyJ.J"))
    }

    @Test
    fun `redactPlainText preserves non-sensitive text`() {
        val raw = "Connection refused: localhost:8080"
        assertEquals(raw, SensitiveDataRedactor.redactPlainText(raw))
    }

    @Test
    fun `redactPlainText handles blank input`() {
        assertEquals("", SensitiveDataRedactor.redactPlainText(""))
    }

    @Test
    fun `redact entry responseBody redacts embedded url tokens from error fallback`() {
        val original = RequestHistoryEntry(
            url = "https://api.example.com/u",
            responseBody = "Request failed for https://api.example.com/u?api_key=AKIAxxx",
        )
        val redacted = SensitiveDataRedactor.redact(original)
        assertTrue(redacted.responseBody.contains("api_key=${SensitiveDataRedactor.REDACTED}"))
        assertTrue(!redacted.responseBody.contains("AKIAxxx"))
        assertTrue(original.responseBody.contains("AKIAxxx"))
    }

    @Test
    fun `header containing bearer or private_key in name is redacted`() {
        listOf("X-Bearer", "X-Private-Key", "X-OTP", "X-PIN").forEach { name ->
            val r = SensitiveDataRedactor.redactHeader(ParamEntry(true, name, "secretValue"))
            assertEquals("name=$name should redact", SensitiveDataRedactor.REDACTED, r.value)
        }
    }
}
