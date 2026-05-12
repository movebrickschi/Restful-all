package io.github.movebrickschi.restfulall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3 - SecretStorageService 纯逻辑单测。
 *
 * 注意：PasswordSafe 读写需要 IntelliJ Platform 测试框架（BasePlatformTestCase）；
 * 本类仅测试 key 生成逻辑（不依赖 IDE）。
 * 集成测试（真实读写 PasswordSafe）在 IDE sandbox test 里做。
 */
class SecretStorageServiceTest {

    @Test
    fun `envKey should follow namespace convention`() {
        assertEquals("env:prod-123:DB_PASSWORD", SecretStorageService.envKey("prod-123", "DB_PASSWORD"))
    }

    @Test
    fun `authKey should follow namespace convention`() {
        assertEquals("auth:bearer-abc", SecretStorageService.authKey("bearer-abc"))
    }

    @Test
    fun `aiKey should follow namespace convention`() {
        assertEquals("ai:openai", SecretStorageService.aiKey("openai"))
        assertEquals("ai:anthropic", SecretStorageService.aiKey("anthropic"))
    }

    @Test
    fun `syncKey should follow namespace convention`() {
        assertEquals("sync:user-42", SecretStorageService.syncKey("user-42"))
    }

    @Test
    fun `key format should always start with namespace colon`() {
        val keys = listOf(
            SecretStorageService.envKey("e1", "k1"),
            SecretStorageService.authKey("a1"),
            SecretStorageService.aiKey("p1"),
            SecretStorageService.syncKey("s1"),
        )
        val namespaces = setOf("env:", "auth:", "ai:", "sync:")
        keys.forEach { k ->
            assertTrue(
                "key=$k 必须以四个命名空间之一开头",
                namespaces.any { ns -> k.startsWith(ns) },
            )
        }
    }

    @Test
    fun `all key factories should produce unique keys for different inputs`() {
        val k1 = SecretStorageService.envKey("env1", "KEY_A")
        val k2 = SecretStorageService.envKey("env1", "KEY_B")
        val k3 = SecretStorageService.envKey("env2", "KEY_A")
        assertFalse("不同 varKey 产生不同 key", k1 == k2)
        assertFalse("不同 envId 产生不同 key", k1 == k3)
    }

    @Test
    fun `envKey with special characters should be preserved`() {
        val key = SecretStorageService.envKey("my-env", "API_KEY_V2")
        assertEquals("env:my-env:API_KEY_V2", key)
    }
}
