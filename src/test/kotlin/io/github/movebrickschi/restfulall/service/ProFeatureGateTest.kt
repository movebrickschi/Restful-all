package io.github.movebrickschi.restfulall.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties

/**
 * v1.3 - ProFeatureGate / ProFeature 单测。
 *
 * 不依赖 IntelliJ Platform 测试框架，覆盖纯逻辑：
 * - enum 完整性（11 个 v1.3 功能全部入列）
 * - key 字段唯一性（防止 copy-paste 写重）
 * - key 命名规范（snake_case，禁止破坏 i18n 协议）
 * - Tier 分配正确性（AI 三件套 + 压测编排 diff sign 走 Pro；同步 / 共享 / Mock 走 Team）
 * - fromKey 双向映射正确
 * - i18n bundle 必须存在每个 key 的中英文条目
 */
class ProFeatureGateTest {

    @Test
    fun `enum should contain exactly 11 v1_3 pro features`() {
        assertEquals("v1.3 应有 11 个 Pro/Team 功能", 11, ProFeature.entries.size)
    }

    @Test
    fun `each ProFeature key should be unique`() {
        val keys = ProFeature.entries.map { it.key }
        assertEquals("ProFeature.key 不允许重复", keys.size, keys.toSet().size)
    }

    @Test
    fun `each ProFeature key should follow snake_case`() {
        val pattern = Regex("^[a-z][a-z0-9_]*$")
        ProFeature.entries.forEach {
            assertTrue("key=${it.key} 必须 snake_case", pattern.matches(it.key))
        }
    }

    @Test
    fun `tier assignment should match v1_3 PRD`() {
        val proKeys = setOf(
            "ai_param_fill", "ai_diagnose", "ai_test_case",
            "press_test", "case_chain", "env_diff", "sign_plugin",
        )
        val teamKeys = setOf(
            "workspace_sync", "team_share", "mock_server", "bidir_sync",
        )
        ProFeature.entries.forEach { f ->
            when (f.tier) {
                ProFeature.Tier.PRO -> assertTrue("Pro 档位应包含 ${f.key}", proKeys.contains(f.key))
                ProFeature.Tier.TEAM -> assertTrue("Team 档位应包含 ${f.key}", teamKeys.contains(f.key))
            }
        }
        assertEquals("Pro 档位应 7 个", 7, ProFeature.entries.count { it.tier == ProFeature.Tier.PRO })
        assertEquals("Team 档位应 4 个", 4, ProFeature.entries.count { it.tier == ProFeature.Tier.TEAM })
    }

    @Test
    fun `fromKey should round-trip for valid keys`() {
        ProFeature.entries.forEach { f ->
            assertEquals(f, ProFeature.fromKey(f.key))
        }
    }

    @Test
    fun `fromKey should return null for unknown key`() {
        assertNull(ProFeature.fromKey("not_a_real_feature"))
        assertNull(ProFeature.fromKey(""))
    }

    @Test
    fun `i18n bundle must contain license_feature entry for every ProFeature key`() {
        val enBundle = loadBundle("/messages/MyMessageBundle.properties")
        val zhBundle = loadBundle("/messages/MyMessageBundle_zh_CN.properties")
        ProFeature.entries.forEach { f ->
            val k = "license.feature.${f.key}"
            assertNotNull("英文 bundle 缺少 $k", enBundle.getProperty(k))
            assertNotNull("中文 bundle 缺少 $k", zhBundle.getProperty(k))
        }
    }

    private fun loadBundle(resource: String): Properties {
        val props = Properties()
        val stream = ProFeatureGateTest::class.java.getResourceAsStream(resource)
            ?: error("Resource not found: $resource")
        stream.use { props.load(it.reader(Charsets.UTF_8)) }
        return props
    }
}
