package io.github.movebrickschi.restfulall.model

import com.intellij.util.xmlb.annotations.OptionTag
import java.util.UUID

/**
 * v1.3.3 P2-9 - 测试用例编排（Pro）。
 *
 * 一个 CaseChain 是若干 [ChainStep] 的顺序执行链：
 * - 每步带请求 spec、断言、变量提取
 * - 变量提取使用 JSONPath；提取出的值注入下一步 spec 的 `${key}` 占位
 * - 任意一步断言失败：链终止，记录 stopAtStep
 *
 * 上限：单链 ≤ [MAX_STEPS] 步。
 */
data class CaseChain(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    @get:OptionTag(tag = "steps")
    var steps: MutableList<ChainStep> = mutableListOf(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun touch() { updatedAt = System.currentTimeMillis() }

    companion object {
        const val MAX_STEPS: Int = 30
    }
}

/**
 * v1.3.3 P2-9 - CaseChain 中的单步。
 *
 * - [extractions] 是 JSONPath → 变量名 映射；例如 `$.data.token` → `token` 意味着把上一步
 *   响应里 data.token 提取出来注入下一步 `${token}` 占位
 * - [continueOnFailure] 默认 false：断言失败终止链；true 允许跳过失败步继续
 */
data class ChainStep(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    @get:OptionTag(tag = "spec")
    var spec: RequestSpecData = RequestSpecData(),
    @get:OptionTag(tag = "assertions")
    var assertionsJson: String = "[]",
    @get:OptionTag(tag = "extractions")
    var extractions: MutableMap<String, String> = mutableMapOf(),
    var continueOnFailure: Boolean = false,
)
