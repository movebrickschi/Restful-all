package io.github.movebrickschi.restfulall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class ResponseCharsetTest {

    @Test
    fun `blank content-type falls back to utf-8`() {
        assertEquals(Charsets.UTF_8, parseResponseCharset(""))
    }

    @Test
    fun `content-type without charset falls back to utf-8`() {
        assertEquals(Charsets.UTF_8, parseResponseCharset("application/json"))
    }

    @Test
    fun `extracts utf-8 explicit`() {
        assertEquals(Charsets.UTF_8, parseResponseCharset("application/json; charset=utf-8"))
    }

    @Test
    fun `extracts gbk for chinese api`() {
        val cs = parseResponseCharset("text/html; charset=GBK")
        assertEquals(Charset.forName("GBK"), cs)
    }

    @Test
    fun `extracts iso-8859-1`() {
        val cs = parseResponseCharset("text/plain; charset=ISO-8859-1")
        assertEquals(Charset.forName("ISO-8859-1"), cs)
    }

    @Test
    fun `unknown charset triggers callback and falls back to utf-8`() {
        val seen = mutableListOf<String>()
        val cs = parseResponseCharset("text/plain; charset=fake-charset-xyz") { seen.add(it) }
        assertEquals(Charsets.UTF_8, cs)
        assertEquals(listOf("fake-charset-xyz"), seen)
    }

    @Test
    fun `quoted charset value is unquoted`() {
        val cs = parseResponseCharset("text/plain; charset=\"utf-8\"")
        assertEquals(Charsets.UTF_8, cs)
    }

    @Test
    fun `case insensitive charset key`() {
        val cs = parseResponseCharset("application/xml; CHARSET=utf-8")
        assertEquals(Charsets.UTF_8, cs)
    }

    @Test
    fun `extra parameters after charset are ignored`() {
        val cs = parseResponseCharset("application/json; charset=utf-8; boundary=xyz")
        assertEquals(Charsets.UTF_8, cs)
    }

    @Test
    fun `unknown charset without callback does not throw`() {
        val cs = parseResponseCharset("text/plain; charset=bogus")
        assertTrue(cs == Charsets.UTF_8)
    }
}
