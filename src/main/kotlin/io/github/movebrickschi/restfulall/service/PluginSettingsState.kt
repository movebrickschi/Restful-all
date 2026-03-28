package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.BaseUrlEntry
import io.github.movebrickschi.restfulall.model.GlobalParamsData
import io.github.movebrickschi.restfulall.model.RequestHistoryEntry

@State(name = "RestfulAllSettings", storages = [Storage("restful-all.xml")])
@Service(Service.Level.PROJECT)
class PluginSettingsState : PersistentStateComponent<PluginSettingsState.State> {

    private var myState = State()

    data class State(
        var baseUrls: MutableList<BaseUrlEntry> = mutableListOf(),
        var globalParams: GlobalParamsData = GlobalParamsData(),
        var requestHistory: MutableList<RequestHistoryEntry> = mutableListOf(),
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
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

    companion object {
        private const val MAX_HISTORY_SIZE = 500

        fun getInstance(project: Project): PluginSettingsState =
            project.getService(PluginSettingsState::class.java)
    }
}
