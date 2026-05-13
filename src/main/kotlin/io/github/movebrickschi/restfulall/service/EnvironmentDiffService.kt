package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.EnvVariable
import io.github.movebrickschi.restfulall.model.EnvironmentEntry

/**
 * v1.3.3 P2-10 - 环境 diff（Pro）。
 *
 * 无状态纯计算，不需要 @Service。调用方拿到两个 [EnvironmentEntry] 直接计算 [DiffReport]。
 *
 * ## 比较规则
 *
 * - 仅比较 `enabled = true` 且 `key` 非空的变量
 * - **secret 变量 value 视为不可比较**（值在 PasswordSafe，比对会泄露密钥）：
 *   - 两边都是 secret → 视为相等（差异不在明面）
 *   - 一边 secret 一边明文 → 进入 `valueDiffer` 段，但 value 显示为 `••••••`
 * - 大小写敏感
 */
object EnvironmentDiffService {

    fun compare(left: EnvironmentEntry, right: EnvironmentEntry): DiffReport {
        val leftMap = left.activeVariables().associateBy { it.key }
        val rightMap = right.activeVariables().associateBy { it.key }

        val onlyLeft = mutableListOf<DiffRow>()
        val onlyRight = mutableListOf<DiffRow>()
        val valueDiffer = mutableListOf<DiffRow>()

        for ((k, lv) in leftMap) {
            val rv = rightMap[k]
            if (rv == null) {
                onlyLeft.add(DiffRow(k, render(lv), null))
            } else {
                if (lv.secret && rv.secret) continue
                val lText = render(lv)
                val rText = render(rv)
                if (lText != rText) {
                    valueDiffer.add(DiffRow(k, lText, rText))
                }
            }
        }
        for ((k, rv) in rightMap) {
            if (k !in leftMap) {
                onlyRight.add(DiffRow(k, null, render(rv)))
            }
        }

        return DiffReport(
            leftName = left.name,
            rightName = right.name,
            onlyLeft = onlyLeft.sortedBy { it.key },
            onlyRight = onlyRight.sortedBy { it.key },
            valueDiffer = valueDiffer.sortedBy { it.key },
        )
    }

    private fun render(v: EnvVariable): String =
        if (v.secret) "\u2022\u2022\u2022\u2022\u2022\u2022" else v.value

    data class DiffRow(
        val key: String,
        val leftValue: String?,
        val rightValue: String?,
    )

    data class DiffReport(
        val leftName: String,
        val rightName: String,
        val onlyLeft: List<DiffRow>,
        val onlyRight: List<DiffRow>,
        val valueDiffer: List<DiffRow>,
    ) {
        val isEmpty: Boolean
            get() = onlyLeft.isEmpty() && onlyRight.isEmpty() && valueDiffer.isEmpty()
    }
}
