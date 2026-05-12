package io.github.movebrickschi.restfulall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinVariableResolverTest {

    @Test
    fun `resolve uuid should produce valid UUID`() {
        val result = BuiltinVariableResolver.resolve("\${random.uuid}")
        assertEquals(36, result.length)
        assertTrue(result.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun `resolve random int should be numeric`() {
        val result = BuiltinVariableResolver.resolve("\${random.int}")
        assertTrue(result.toIntOrNull() != null)
    }

    @Test
    fun `resolve random int with range should be in bounds`() {
        repeat(50) {
            val result = BuiltinVariableResolver.resolve("\${random.int(1,100)}").toInt()
            assertTrue("$result should be in [1,100]", result in 1..100)
        }
    }

    @Test
    fun `resolve random string should be 10 chars by default`() {
        val result = BuiltinVariableResolver.resolve("\${random.string}")
        assertEquals(10, result.length)
        assertTrue(result.all { it.isLetterOrDigit() })
    }

    @Test
    fun `resolve random string with length`() {
        val result = BuiltinVariableResolver.resolve("\${random.string(20)}")
        assertEquals(20, result.length)
    }

    @Test
    fun `resolve timestamp should be numeric seconds`() {
        val result = BuiltinVariableResolver.resolve("\${timestamp}")
        val ts = result.toLong()
        assertTrue(ts > 1_700_000_000)
        assertTrue(ts < 2_000_000_000)
    }

    @Test
    fun `resolve timestamp ms should be numeric milliseconds`() {
        val result = BuiltinVariableResolver.resolve("\${timestamp.ms}")
        val ts = result.toLong()
        assertTrue(ts > 1_700_000_000_000)
    }

    @Test
    fun `resolve date should follow default format`() {
        val result = BuiltinVariableResolver.resolve("\${date}")
        assertTrue(result.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `resolve date with custom pattern`() {
        val result = BuiltinVariableResolver.resolve("\${date(yyyyMMdd)}")
        assertTrue(result.matches(Regex("\\d{8}")))
    }

    @Test
    fun `mixed template with builtin and plain text`() {
        val result = BuiltinVariableResolver.resolve("""{"id":"\${random.uuid}","ts":\${timestamp}}""")
        assertFalse(result.contains("\${random.uuid}"))
        assertFalse(result.contains("\${timestamp}"))
        assertTrue(result.contains("\"id\":\""))
        assertTrue(result.contains("\"ts\":"))
    }

    @Test
    fun `plain text without variables should pass through`() {
        assertEquals("hello world", BuiltinVariableResolver.resolve("hello world"))
    }

    @Test
    fun `unknown builtin should be preserved`() {
        val input = "\${unknown.var}"
        assertEquals(input, BuiltinVariableResolver.resolve(input))
    }

    @Test
    fun `each invocation produces different uuid`() {
        val a = BuiltinVariableResolver.resolve("\${random.uuid}")
        val b = BuiltinVariableResolver.resolve("\${random.uuid}")
        assertFalse("Two calls should produce different UUIDs", a == b)
    }
}
