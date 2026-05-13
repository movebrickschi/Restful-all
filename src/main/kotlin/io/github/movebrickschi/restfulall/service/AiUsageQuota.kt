package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.3.1 P0-1 - AI 免费试用配额。
 *
 * Free 档每天 [DAILY_FREE_QUOTA] 次（含 AI_PARAM_FILL / AI_DIAGNOSE / AI_TEST_CASE 三件套合计）。
 * Pro 档不走本配额（[tryConsume] 由调用方在 Pro 用户上跳过）。
 *
 * - Application 级持久化到 `restful-all-ai.xml`
 * - 跨项目共用（Free 用户开多个项目也共享每天 5 次）
 * - 日切按本地时区 `yyyy-MM-dd`
 */
@State(name = "RestfulAll.AiUsage", storages = [Storage("restful-all-ai.xml")])
@Service(Service.Level.APP)
class AiUsageQuota : PersistentStateComponent<AiUsageQuota.State> {

    data class State(
        var dailyCount: Int = 0,
        var lastResetDate: String = "",
    )

    @Volatile
    private var myState = State()

    companion object {
        const val DAILY_FREE_QUOTA: Int = 5

        fun getInstance(): AiUsageQuota =
            ApplicationManager.getApplication().getService(AiUsageQuota::class.java)

        private fun today(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * 尝试消费 1 次配额。
     *
     * @return true 表示扣配额成功（调用方可继续调 LLM）；false 表示当日已达上限。
     */
    @Synchronized
    fun tryConsume(): Boolean {
        val today = today()
        if (myState.lastResetDate != today) {
            myState.dailyCount = 0
            myState.lastResetDate = today
        }
        if (myState.dailyCount >= DAILY_FREE_QUOTA) return false
        myState.dailyCount += 1
        return true
    }

    @Synchronized
    fun remaining(): Int {
        val today = today()
        if (myState.lastResetDate != today) return DAILY_FREE_QUOTA
        return (DAILY_FREE_QUOTA - myState.dailyCount).coerceAtLeast(0)
    }

    @Synchronized
    fun reset() {
        myState.dailyCount = 0
        myState.lastResetDate = today()
    }
}
