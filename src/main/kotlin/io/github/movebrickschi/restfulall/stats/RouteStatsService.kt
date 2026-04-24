package io.github.movebrickschi.restfulall.stats

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.github.movebrickschi.restfulall.MyMessageBundle
import io.github.movebrickschi.restfulall.humanize.FunMessageProvider
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.model.UserRouteMeta
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 路由使用统计服务。
 *
 * 底层数据来自 [PluginSettingsState.userRouteMeta]（已存在的 UserRouteMeta 模型），
 * 本服务只是个聚合/查询门面，避免其他模块直接访问原始结构。
 *
 * 同时向外广播 [RouteNavigationListener]，供宠物小猫、状态栏彩蛋等订阅。
 */
@Service(Service.Level.PROJECT)
class RouteStatsService(private val project: Project) {

    private val settings get() = PluginSettingsState.getInstance(project)

    /**
     * 记录一次跳转事件。在 SearchRouteAction/Popup 的 navigate 中调用。
     * 写入后同步广播到 [RouteNavigationListener]。
     */
    fun recordOpen(route: RouteInfo) {
        val key = UserRouteMeta.keyOf(route)
        settings.recordAccess(key)
        project.messageBus
            .syncPublisher(RouteNavigationListener.TOPIC)
            .onNavigate(route)
        maybeTriggerYearReportTeaser()
    }

    /**
     * 跨过 100/500/1000 跳转里程碑时，弹一条带「打开年报」按钮的通知。
     * 用 [PluginSettingsState.lastTeaserAt] 记录上次触发的阈值，避免同一里程碑重复弹。
     */
    private fun maybeTriggerYearReportTeaser() {
        val total = settings.getTotalNavigations().toInt().coerceAtLeast(0)
        val crossed = MILESTONES.firstOrNull { it == total } ?: return
        if (settings.getLastTeaserAt() >= crossed) return
        settings.setLastTeaserAt(crossed)

        val msg = FunMessageProvider.pick("report.teaser").ifBlank {
            "你已经累计跳转 $crossed 次，要不要看看年度回顾？"
        }
        try {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    MyMessageBundle.message("notification.report.teaser.title"),
                    msg,
                    NotificationType.INFORMATION,
                )
                .addAction(
                    NotificationAction.create(
                        MyMessageBundle.message("notification.report.teaser.action"),
                    ) { _, n ->
                        openYearReport()
                        n.expire()
                    },
                )
            notification.notify(project)
        } catch (_: Throwable) {
            // 通知系统未就绪不影响统计
        }
    }

    private fun openYearReport() {
        ApplicationManager.getApplication().invokeLater {
            try {
                io.github.movebrickschi.restfulall.action.ShowYearReportAction.showFor(project)
            } catch (_: Throwable) {
                // 静默
            }
        }
    }

    /** 获取某条路由的累计跳转次数。 */
    fun getOpenCount(routeKey: String): Int =
        settings.getMeta(routeKey)?.accessCount ?: 0

    /** 该路由在最近 N 天内是否被打开过。 */
    fun openedWithinDays(routeKey: String, days: Int): Boolean {
        val meta = settings.getMeta(routeKey) ?: return false
        val threshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days.toLong())
        return meta.lastAccessAt >= threshold
    }

    /**
     * 今日访问过的路由（按最后访问时间倒序）。
     */
    fun getTodayOpened(): List<TodayOpen> {
        val todayStart = startOfToday()
        return settings.getAllMetas()
            .filter { it.lastAccessAt >= todayStart }
            .sortedByDescending { it.lastAccessAt }
            .map { TodayOpen(it.routeKey, it.accessCount, it.lastAccessAt) }
    }

    /** 今天累计跳转总次数（粗略：统计今天访问过的路由 accessCount 之和不准；这里用 lastAccess 判断然后计数 1）。 */
    fun getTodayNavigationCount(): Int = getTodayOpened().size

    /**
     * 年度统计快照。用于年度报告。
     */
    fun getYearStats(year: Int = LocalDate.now().year): YearStats {
        val zoneId = ZoneId.systemDefault()
        val yearStart = LocalDate.of(year, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val yearEnd = LocalDate.of(year + 1, 1, 1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val metas = settings.getAllMetas()
            .filter { it.lastAccessAt in yearStart until yearEnd }

        val total = settings.getTotalNavigations()
        val topRoutes = metas
            .sortedByDescending { it.accessCount }
            .take(10)
            .map { TopRoute(it.routeKey, it.accessCount, it.lastAccessAt) }

        // 按月份统计（使用 lastAccessAt，粗略但足够做趋势图）
        val perMonth = IntArray(12)
        for (meta in metas) {
            val month = Instant.ofEpochMilli(meta.lastAccessAt).atZone(zoneId).monthValue
            perMonth[month - 1] += meta.accessCount
        }

        // 最活跃小时
        val hourBuckets = IntArray(24)
        for (meta in metas) {
            val hour = Instant.ofEpochMilli(meta.lastAccessAt).atZone(zoneId).hour
            hourBuckets[hour] += meta.accessCount
        }
        val mostActiveHour = hourBuckets.withIndex().maxByOrNull { it.value }?.index ?: -1

        return YearStats(
            year = year,
            totalNavigations = total,
            uniqueRoutes = metas.size,
            topRoutes = topRoutes,
            perMonth = perMonth.toList(),
            mostActiveHour = mostActiveHour,
            firstUseTimestamp = settings.getFirstInstallTimestamp(),
            favoriteCount = metas.count { it.favorite },
            notedCount = metas.count { it.note.isNotBlank() },
        )
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    data class TodayOpen(val routeKey: String, val count: Int, val lastAccessAt: Long)
    data class TopRoute(val routeKey: String, val count: Int, val lastAccessAt: Long)
    data class YearStats(
        val year: Int,
        val totalNavigations: Long,
        val uniqueRoutes: Int,
        val topRoutes: List<TopRoute>,
        val perMonth: List<Int>,
        val mostActiveHour: Int,
        val firstUseTimestamp: Long,
        val favoriteCount: Int,
        val notedCount: Int,
    )

    companion object {
        private const val NOTIFICATION_GROUP_ID = "Restful-all.Pro"
        private const val YEAR_REPORT_ACTION_ID = "Restful-all.YearReport"
        private val MILESTONES = listOf(100, 500, 1000)

        fun getInstance(project: Project): RouteStatsService =
            project.getService(RouteStatsService::class.java)
    }
}

/** 路由跳转事件广播接口（供宠物动画、成就解锁等订阅）。 */
fun interface RouteNavigationListener {
    fun onNavigate(route: RouteInfo)

    companion object {
        val TOPIC: Topic<RouteNavigationListener> =
            Topic.create("Restful-all Route Navigation", RouteNavigationListener::class.java)
    }
}
