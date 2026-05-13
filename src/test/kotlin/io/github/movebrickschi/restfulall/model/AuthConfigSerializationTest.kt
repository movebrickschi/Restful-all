package io.github.movebrickschi.restfulall.model

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-1 安全回归测试：保证 [AuthConfig] 的密文字段 (bearerToken / basicPassword / apiKeyValue)
 * 不会被 IntelliJ XmlSerializer 落盘到任何 PersistentStateComponent 的 XML 中。
 *
 * 防御场景：未来若有人把 AuthConfig 嵌入到 PluginSettingsState / CollectionService /
 * RequestSpecData 等 @State 状态树（Issue / PR 审查容易遗漏），本测试会兜底拦截，
 * 避免明文 token 跟随 restful-all.xml 进入用户机器 / 同步盘 / git 等持久化通道。
 *
 * 测试不依赖具体上层 State，对底层 XmlSerializer 行为做反射级断言。
 */
class AuthConfigSerializationTest {

    @Test
    fun `secret fields must not appear in serialized XML`() {
        val cfg = AuthConfig(
            id = "abc-123",
            type = AuthConfig.AuthType.BEARER,
            bearerToken = "SUPER_SECRET_BEARER_TOKEN",
            basicUsername = "admin",
            basicPassword = "SUPER_SECRET_BASIC_PASSWORD",
            apiKeyName = "X-API-Key",
            apiKeyValue = "SUPER_SECRET_API_KEY_VALUE",
            apiKeyLocation = AuthConfig.ApiKeyLocation.HEADER,
        )

        val xml = serializeToXml(cfg)

        assertFalse("bearerToken must NOT leak into XML: $xml", xml.contains("SUPER_SECRET_BEARER_TOKEN"))
        assertFalse("basicPassword must NOT leak into XML: $xml", xml.contains("SUPER_SECRET_BASIC_PASSWORD"))
        assertFalse("apiKeyValue must NOT leak into XML: $xml", xml.contains("SUPER_SECRET_API_KEY_VALUE"))

        assertFalse("bearerToken tag must NOT appear: $xml", xml.contains("name=\"bearerToken\""))
        assertFalse("basicPassword tag must NOT appear: $xml", xml.contains("name=\"basicPassword\""))
        assertFalse("apiKeyValue tag must NOT appear: $xml", xml.contains("name=\"apiKeyValue\""))
    }

    @Test
    fun `non-secret fields should still persist in XML`() {
        val cfg = AuthConfig(
            id = "abc-123",
            type = AuthConfig.AuthType.API_KEY,
            bearerToken = "should-be-stripped",
            basicUsername = "alice",
            basicPassword = "should-be-stripped",
            apiKeyName = "X-API-Key",
            apiKeyValue = "should-be-stripped",
            apiKeyLocation = AuthConfig.ApiKeyLocation.COOKIE,
        )

        val xml = serializeToXml(cfg)

        assertTrue("id must persist: $xml", xml.contains("abc-123"))
        assertTrue("type must persist: $xml", xml.contains("API_KEY"))
        assertTrue("basicUsername must persist: $xml", xml.contains("alice"))
        assertTrue("apiKeyName must persist: $xml", xml.contains("X-API-Key"))
        assertTrue("apiKeyLocation must persist: $xml", xml.contains("COOKIE"))
    }

    @Test
    fun `roundtrip should reset secret fields to defaults but keep meta`() {
        val original = AuthConfig(
            id = "round-1",
            type = AuthConfig.AuthType.BEARER,
            bearerToken = "ORIGINAL_TOKEN",
            basicUsername = "alice",
            basicPassword = "ORIGINAL_PWD",
            apiKeyName = "X-API-Key",
            apiKeyValue = "ORIGINAL_API_KEY",
            apiKeyLocation = AuthConfig.ApiKeyLocation.QUERY,
        )

        val element = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(element, AuthConfig::class.java)

        assertEquals("round-1", restored.id)
        assertEquals(AuthConfig.AuthType.BEARER, restored.type)
        assertEquals("alice", restored.basicUsername)
        assertEquals("X-API-Key", restored.apiKeyName)
        assertEquals(AuthConfig.ApiKeyLocation.QUERY, restored.apiKeyLocation)

        assertEquals("bearerToken must reset to default", "", restored.bearerToken)
        assertEquals("basicPassword must reset to default", "", restored.basicPassword)
        assertEquals("apiKeyValue must reset to default", "", restored.apiKeyValue)
    }

    @Test
    fun `isConfigured should still see runtime secret fields after copy`() {
        val cfg = AuthConfig(
            type = AuthConfig.AuthType.BEARER,
            bearerToken = "live-token",
        )
        val copy = cfg.copy()
        assertTrue("copy keeps secret in memory", copy.isConfigured())
        assertEquals("live-token", copy.bearerToken)
    }

    private fun serializeToXml(cfg: AuthConfig): String =
        JDOMUtil.writeElement(XmlSerializer.serialize(cfg))
}
