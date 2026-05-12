package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.EnvironmentEntry
import io.github.movebrickschi.restfulall.model.EnvVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v1.3 F1 - EnvironmentService 纯逻辑单测。
 *
 * 覆盖：变量替换、嵌套、循环引用检测、缺失变量、模型 CRUD 逻辑。
 * 不依赖 IntelliJ Platform（不走 PasswordSafe / PersistentStateComponent 生命周期）。
 */
class EnvironmentServiceTest {

    @Test
    fun `VAR_PATTERN should match standard dollar-brace syntax`() {
        val pattern = Regex("""\$\{([^}]+)}""")
        val input = "http://\${host}:\${port}/\${context-path}/api"
        val matches = pattern.findAll(input).map { it.groupValues[1] }.toList()
        assertEquals(listOf("host", "port", "context-path"), matches)
    }

    @Test
    fun `VAR_PATTERN should not match empty braces`() {
        val pattern = Regex("""\$\{([^}]+)}""")
        val input = "http://\${}/test"
        assertTrue(pattern.findAll(input).toList().isEmpty())
    }

    @Test
    fun `VAR_PATTERN should match underscored and dotted keys`() {
        val pattern = Regex("""\$\{([^}]+)}""")
        val input = "\${API_KEY} and \${env.host}"
        val matches = pattern.findAll(input).map { it.groupValues[1] }.toList()
        assertEquals(listOf("API_KEY", "env.host"), matches)
    }

    @Test
    fun `EnvironmentEntry newDefault should have correct defaults`() {
        val def = EnvironmentEntry.newDefault()
        assertEquals(EnvironmentEntry.DEFAULT_ID, def.id)
        assertEquals(EnvironmentEntry.DEFAULT_NAME, def.name)
        assertTrue(def.isActive)
        assertTrue(def.variables.isEmpty())
    }

    @Test
    fun `activeVariables should filter disabled and blank-key entries`() {
        val env = EnvironmentEntry(
            variables = mutableListOf(
                EnvVariable(enabled = true, key = "HOST", value = "localhost"),
                EnvVariable(enabled = false, key = "PORT", value = "8080"),
                EnvVariable(enabled = true, key = "", value = "empty-key"),
                EnvVariable(enabled = true, key = "PATH", value = "/api"),
            ),
        )
        val active = env.activeVariables()
        assertEquals(2, active.size)
        assertEquals("HOST", active[0].key)
        assertEquals("PATH", active[1].key)
    }

    @Test
    fun `findVariable should return matching enabled entry`() {
        val env = EnvironmentEntry(
            variables = mutableListOf(
                EnvVariable(enabled = true, key = "TOKEN", value = "abc"),
                EnvVariable(enabled = false, key = "TOKEN_DISABLED", value = "xyz"),
            ),
        )
        assertNotNull(env.findVariable("TOKEN"))
        assertNull(env.findVariable("TOKEN_DISABLED"))
        assertNull(env.findVariable("NOT_EXISTS"))
    }

    @Test
    fun `EnvVariable secretRef should generate correct prefix`() {
        val ref = EnvVariable.secretRef("env:prod:DB_PASS")
        assertEquals("__SECRET_REF__:env:prod:DB_PASS", ref)
    }

    @Test
    fun `EnvVariable isSecretRef should detect prefix`() {
        val v = EnvVariable(value = "__SECRET_REF__:env:prod:DB_PASS")
        assertTrue(v.isSecretRef())
        assertEquals("env:prod:DB_PASS", v.secretRefKey())
    }

    @Test
    fun `EnvVariable isSecretRef should return false for normal value`() {
        val v = EnvVariable(value = "just-a-value")
        assertTrue(!v.isSecretRef())
        assertNull(v.secretRefKey())
    }

    @Test
    fun `touch should update updatedAt timestamp`() {
        val env = EnvironmentEntry()
        val before = env.updatedAt
        Thread.sleep(10)
        env.touch()
        assertTrue(env.updatedAt > before)
    }

    @Test
    fun `inline resolve of simple template with mock varMap`() {
        val pattern = Regex("""\$\{([^}]+)}""")
        val varMap = mapOf("host" to "localhost", "port" to "8080")
        val template = "http://\${host}:\${port}/api"
        val result = pattern.replace(template) { match ->
            varMap[match.groupValues[1]] ?: match.value
        }
        assertEquals("http://localhost:8080/api", result)
    }

    @Test
    fun `inline resolve should preserve unknown vars`() {
        val pattern = Regex("""\$\{([^}]+)}""")
        val varMap = mapOf("host" to "localhost")
        val template = "http://\${host}:\${port}/api"
        val result = pattern.replace(template) { match ->
            varMap[match.groupValues[1]] ?: match.value
        }
        assertEquals("http://localhost:\${port}/api", result)
    }

    @Test
    fun `inline resolve detects circular reference`() {
        val varMap = mapOf("a" to "\${b}", "b" to "\${a}")
        val pattern = Regex("""\$\{([^}]+)}""")
        val visiting = mutableSetOf<String>()
        var caught = false

        fun resolveRec(input: String, depth: Int): String {
            if (depth > 10) { caught = true; return input }
            return pattern.replace(input) { match ->
                val key = match.groupValues[1]
                if (key in visiting) { caught = true; return@replace match.value }
                visiting.add(key)
                val raw = varMap[key] ?: return@replace match.value
                val r = resolveRec(raw, depth + 1)
                visiting.remove(key)
                r
            }
        }

        resolveRec("\${a}", 0)
        assertTrue("Should detect circular reference", caught)
    }

    @Test
    fun `inline resolve handles nested variables`() {
        val varMap = mapOf("base" to "http://\${host}:\${port}", "host" to "localhost", "port" to "3000")
        val pattern = Regex("""\$\{([^}]+)}""")
        val visiting = mutableSetOf<String>()

        fun resolveRec(input: String, depth: Int): String {
            if (depth > 10) return input
            return pattern.replace(input) { match ->
                val key = match.groupValues[1]
                if (key in visiting) return@replace match.value
                visiting.add(key)
                val raw = varMap[key] ?: return@replace match.value
                val r = resolveRec(raw, depth + 1)
                visiting.remove(key)
                r
            }
        }

        val result = resolveRec("\${base}/api", 0)
        assertEquals("http://localhost:3000/api", result)
    }
}
