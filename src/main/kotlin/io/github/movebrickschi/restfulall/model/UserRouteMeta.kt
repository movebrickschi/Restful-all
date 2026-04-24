package io.github.movebrickschi.restfulall.model

import kotlin.math.exp

/**
 * 用户对某条路由的私人元数据：收藏 / Pinned / 访问频次 / 最近访问。
 *
 * 用 [routeKey] = "${method}:${fullPath}" 作主键而非文件路径，
 * 这样即使后端重命名/重构源文件，用户的偏好仍能跟随路由本身。
 *
 * v1.1 起 [tags] 与 [note] 用于备注/标签系统（Pro 功能）。
 */
data class UserRouteMeta(
    var routeKey: String = "",
    var pinned: Boolean = false,
    var favorite: Boolean = false,
    var accessCount: Int = 0,
    var lastAccessAt: Long = 0L,
    var note: String = "",
    var tags: MutableSet<String> = mutableSetOf(),
) {
    /** 衰减权重：30 天半衰期，避免老路由永久压制新接口。 */
    fun effectiveScore(now: Long = System.currentTimeMillis()): Double {
        if (accessCount <= 0) return 0.0
        val daysSinceLast = (now - lastAccessAt).coerceAtLeast(0L) / DAY_MS.toDouble()
        return accessCount.toDouble() * exp(-daysSinceLast / HALF_LIFE_DAYS)
    }

    fun recordAccess(now: Long = System.currentTimeMillis()) {
        accessCount += 1
        lastAccessAt = now
    }

    val hasAnyMark: Boolean
        get() = pinned || favorite || accessCount > 0 || tags.isNotEmpty() || note.isNotBlank()

    companion object {
        private const val DAY_MS: Long = 24L * 60 * 60 * 1000
        private const val HALF_LIFE_DAYS: Double = 30.0

        fun keyOf(method: HttpMethod, fullPath: String): String =
            "${method.displayName}:$fullPath"

        fun keyOf(route: RouteInfo): String = keyOf(route.method, route.displayPath)
    }
}
