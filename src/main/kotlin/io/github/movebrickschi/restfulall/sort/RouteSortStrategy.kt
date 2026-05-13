package io.github.movebrickschi.restfulall.sort

import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.model.UserRouteMeta
import io.github.movebrickschi.restfulall.service.PluginSettingsState

/**
 * 智能排序策略：结合 Pinned / Favorite / 访问频次衰减分 / 路径字母序。
 *
 * 权重公式：
 *   pinned   → +10000（置顶绝对优先）
 *   favorite → +500
 *   effectiveScore（30 天半衰期）× 1.0
 *   最后按 fullPath 字母序 tie-break
 */
object RouteSortStrategy {

    fun smartSort(routes: List<RouteInfo>, settings: PluginSettingsState): List<RouteInfo> {
        if (routes.isEmpty()) return routes
        val now = System.currentTimeMillis()
        // 预计算分数避免比较器内 O(n log n) 次重复 score 计算（每次都 keyOf + getMeta）
        val scored = Array(routes.size) { idx ->
            val r = routes[idx]
            ScoredRoute(r, scoreOf(r, settings, now))
        }
        scored.sortWith(SCORED_COMPARATOR)
        return scored.map { it.route }
    }

    private data class ScoredRoute(val route: RouteInfo, val score: Double)

    private val SCORED_COMPARATOR: Comparator<ScoredRoute> =
        compareByDescending<ScoredRoute> { it.score }
            .thenBy { it.route.displayPath }
            .thenBy { it.route.method.name }

    fun scoreOf(route: RouteInfo, settings: PluginSettingsState, now: Long = System.currentTimeMillis()): Double {
        val meta = settings.getMeta(UserRouteMeta.keyOf(route)) ?: return 0.0
        var score = 0.0
        if (meta.pinned) score += 10_000.0
        if (meta.favorite) score += 500.0
        score += meta.effectiveScore(now)
        return score
    }

    /** 今日常用（最多 N 条）：今天访问过的路由，按访问时间倒序。 */
    fun pickTodayFavorites(
        routes: List<RouteInfo>,
        settings: PluginSettingsState,
        limit: Int = 3,
    ): List<RouteInfo> {
        if (routes.isEmpty()) return emptyList()
        val startOfToday = startOfTodayMillis()
        val byKey = routes.associateBy { UserRouteMeta.keyOf(it) }
        return settings.getAllMetas()
            .asSequence()
            .filter { it.lastAccessAt >= startOfToday }
            .sortedByDescending { it.lastAccessAt }
            .mapNotNull { byKey[it.routeKey] }
            .distinct()
            .take(limit)
            .toList()
    }

    private fun startOfTodayMillis(): Long {
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
