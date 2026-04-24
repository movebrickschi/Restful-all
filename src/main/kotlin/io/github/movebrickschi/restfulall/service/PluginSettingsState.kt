package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.OptionTag
import io.github.movebrickschi.restfulall.model.BaseUrlEntry
import io.github.movebrickschi.restfulall.model.GlobalParamsData
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry
import io.github.movebrickschi.restfulall.model.UserRouteMeta

@State(name = "RestfulAllSettings", storages = [Storage("restful-all.xml")])
@Service(Service.Level.PROJECT)
class PluginSettingsState : PersistentStateComponent<PluginSettingsState.State> {

    private var myState = State()

    data class State(
        var baseUrls: MutableList<BaseUrlEntry> = mutableListOf(),
        var globalParams: GlobalParamsData = GlobalParamsData(),
        var requestHistory: MutableList<RequestHistoryEntry> = mutableListOf(),
        var language: String = ApplicationLanguageHolder.LANG_AUTO,

        // v1.0 - 用户元数据（收藏/Pinned/频次/Tag/备注），按 routeKey 索引
        @get:OptionTag(tag = "userRouteMeta")
        var userRouteMeta: MutableMap<String, UserRouteMeta> = mutableMapOf(),

        // v1.0 - 弹框 UI 偏好
        var popupWidth: Int = 700,
        var popupHeight: Int = 400,
        var popupLastSearchQuery: String = "",

        // v1.0 - 欢迎语：上次显示日期 (yyyy-MM-dd)
        var lastWelcomeShownDate: String = "",
        var firstInstallTimestamp: Long = 0L,

        // v1.1 - 主题与配色
        var themePreset: String = "default",
        var customMethodColors: MutableMap<String, String> = mutableMapOf(),
        var displayDensity: String = "standard", // compact | standard | comfortable

        // v1.1 - 角色预设
        var userRole: String = "FULLSTACK",
        var hasCompletedOnboarding: Boolean = false,

        // v1.1 - 搜索行为
        var matchMode: String = "FUZZY", // EXACT | FUZZY | REGEX
        var recentSearchQueries: MutableList<String> = mutableListOf(),
        var pinnedQuickFilters: MutableList<String> = mutableListOf(),

        // v1.1 - License
        var licenseInstalledAt: Long = 0L,

        // v1.2 - 每日小报快照（routeKey -> hash）
        @get:OptionTag(tag = "lastRouteSnapshot")
        var lastRouteSnapshot: MutableMap<String, String> = mutableMapOf(),
        var lastDigestShownDate: String = "",

        // v1.2 - 里程碑
        var totalNavigations: Long = 0L,
        var unlockedBadges: MutableSet<String> = mutableSetOf(),

        // v1.2 - 年报里程碑提示（最近一次触发的阈值，避免重复通知）
        var lastTeaserAt: Int = 0,
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        ApplicationLanguageHolder.set(myState.language)
    }

    fun getBaseUrls(): MutableList<BaseUrlEntry> = myState.baseUrls

    fun setBaseUrls(urls: List<BaseUrlEntry>) {
        myState.baseUrls = urls.toMutableList()
    }

    fun getGlobalParams(): GlobalParamsData = myState.globalParams

    fun setGlobalParams(params: GlobalParamsData) {
        myState.globalParams = params
    }

    fun getRequestHistory(): MutableList<RequestHistoryEntry> = myState.requestHistory

    fun addHistoryEntry(entry: RequestHistoryEntry) {
        myState.requestHistory.add(0, entry)
        if (myState.requestHistory.size > MAX_HISTORY_SIZE) {
            myState.requestHistory = myState.requestHistory.take(MAX_HISTORY_SIZE).toMutableList()
        }
    }

    fun clearHistory() {
        myState.requestHistory.clear()
    }

    fun findBaseUrlForModule(moduleName: String): BaseUrlEntry? {
        return myState.baseUrls.find { it.moduleName == moduleName }
    }

    fun findBaseUrlForModuleOrDefault(moduleName: String): BaseUrlEntry? {
        val urls = myState.baseUrls
        if (urls.isEmpty()) return null
        return urls.find { it.moduleName == moduleName }
            ?: urls.find { it.moduleName.isBlank() }
            ?: if (urls.size == 1) urls.first() else null
    }

    fun getLanguage(): String = myState.language

    fun setLanguage(language: String) {
        val normalized = when (language.uppercase()) {
            ApplicationLanguageHolder.LANG_ZH_CN -> ApplicationLanguageHolder.LANG_ZH_CN
            ApplicationLanguageHolder.LANG_EN    -> ApplicationLanguageHolder.LANG_EN
            else                                 -> ApplicationLanguageHolder.LANG_AUTO
        }
        if (normalized == myState.language) return
        myState.language = normalized
        ApplicationLanguageHolder.set(normalized)
        ApplicationManager.getApplication().messageBus
            .syncPublisher(LanguageChangeListener.TOPIC)
            .languageChanged(normalized)
    }

    // ---------- v1.0 - UserRouteMeta ----------

    fun getMeta(routeKey: String): UserRouteMeta? = myState.userRouteMeta[routeKey]

    fun getOrCreateMeta(routeKey: String): UserRouteMeta =
        myState.userRouteMeta.getOrPut(routeKey) { UserRouteMeta(routeKey = routeKey) }

    fun saveMeta(meta: UserRouteMeta) {
        if (meta.hasAnyMark) {
            myState.userRouteMeta[meta.routeKey] = meta
        } else {
            // 没有任何标记则清理，避免存储垃圾
            myState.userRouteMeta.remove(meta.routeKey)
        }
    }

    fun togglePin(routeKey: String): Boolean {
        val meta = getOrCreateMeta(routeKey)
        meta.pinned = !meta.pinned
        saveMeta(meta)
        return meta.pinned
    }

    fun toggleFavorite(routeKey: String): Boolean {
        val meta = getOrCreateMeta(routeKey)
        meta.favorite = !meta.favorite
        saveMeta(meta)
        return meta.favorite
    }

    fun recordAccess(routeKey: String) {
        val meta = getOrCreateMeta(routeKey)
        meta.recordAccess()
        myState.userRouteMeta[meta.routeKey] = meta
        myState.totalNavigations += 1
    }

    fun allKnownTags(): Set<String> =
        myState.userRouteMeta.values.flatMap { it.tags }.toSet()

    fun getAllMetas(): Collection<UserRouteMeta> = myState.userRouteMeta.values

    fun removeMeta(routeKey: String) {
        myState.userRouteMeta.remove(routeKey)
    }

    // ---------- v1.0 - 弹框记忆 ----------

    fun getPopupSize(): Pair<Int, Int> = myState.popupWidth to myState.popupHeight

    fun setPopupSize(width: Int, height: Int) {
        if (width > 100) myState.popupWidth = width
        if (height > 100) myState.popupHeight = height
    }

    fun getLastSearchQuery(): String = myState.popupLastSearchQuery

    fun setLastSearchQuery(query: String) {
        myState.popupLastSearchQuery = query
        if (query.isBlank()) return
        // 同步进搜索历史，去重保留最新 10 条
        val list = myState.recentSearchQueries
        list.remove(query)
        list.add(0, query)
        while (list.size > MAX_RECENT_QUERIES) list.removeAt(list.size - 1)
    }

    fun getRecentSearchQueries(): List<String> = myState.recentSearchQueries.toList()

    // ---------- v1.0 - 欢迎语 / 安装时间 ----------

    fun getLastWelcomeShownDate(): String = myState.lastWelcomeShownDate

    fun setLastWelcomeShownDate(date: String) {
        myState.lastWelcomeShownDate = date
    }

    fun getFirstInstallTimestamp(): Long {
        if (myState.firstInstallTimestamp == 0L) {
            myState.firstInstallTimestamp = System.currentTimeMillis()
        }
        return myState.firstInstallTimestamp
    }

    // ---------- v1.1 - 主题 ----------

    fun getThemePreset(): String = myState.themePreset

    fun setThemePreset(preset: String) {
        myState.themePreset = preset
    }

    fun getCustomMethodColors(): MutableMap<String, String> = myState.customMethodColors

    fun setCustomMethodColor(method: String, hexColor: String) {
        myState.customMethodColors[method] = hexColor
    }

    fun clearCustomMethodColors() {
        myState.customMethodColors.clear()
    }

    fun getDisplayDensity(): String = myState.displayDensity

    fun setDisplayDensity(density: String) {
        myState.displayDensity = density
    }

    // ---------- v1.1 - 角色 / Onboarding ----------

    fun getUserRole(): String = myState.userRole

    fun setUserRole(role: String) {
        myState.userRole = role
    }

    fun isOnboardingCompleted(): Boolean = myState.hasCompletedOnboarding

    fun setOnboardingCompleted(value: Boolean) {
        myState.hasCompletedOnboarding = value
    }

    // ---------- v1.1 - 搜索模式 / Quick Filter ----------

    fun getMatchMode(): String = myState.matchMode

    fun setMatchMode(mode: String) {
        myState.matchMode = mode
    }

    fun getPinnedQuickFilters(): MutableList<String> = myState.pinnedQuickFilters

    fun setPinnedQuickFilters(filters: List<String>) {
        myState.pinnedQuickFilters = filters.toMutableList()
    }

    // ---------- v1.1 - License ----------

    fun getLicenseInstalledAt(): Long {
        if (myState.licenseInstalledAt == 0L) {
            myState.licenseInstalledAt = System.currentTimeMillis()
        }
        return myState.licenseInstalledAt
    }

    // ---------- v1.2 - 每日小报 ----------

    fun getLastRouteSnapshot(): MutableMap<String, String> = myState.lastRouteSnapshot

    fun setLastRouteSnapshot(snapshot: Map<String, String>) {
        myState.lastRouteSnapshot = snapshot.toMutableMap()
    }

    fun getLastDigestShownDate(): String = myState.lastDigestShownDate

    fun setLastDigestShownDate(date: String) {
        myState.lastDigestShownDate = date
    }

    // ---------- v1.2 - 里程碑 ----------

    fun getTotalNavigations(): Long = myState.totalNavigations

    fun getUnlockedBadges(): MutableSet<String> = myState.unlockedBadges

    fun unlockBadge(badge: String): Boolean {
        return myState.unlockedBadges.add(badge)
    }

    fun getLastTeaserAt(): Int = myState.lastTeaserAt

    fun setLastTeaserAt(value: Int) {
        myState.lastTeaserAt = value
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 500
        private const val MAX_RECENT_QUERIES = 10

        fun getInstance(project: Project): PluginSettingsState =
            project.getService(PluginSettingsState::class.java)
    }
}
