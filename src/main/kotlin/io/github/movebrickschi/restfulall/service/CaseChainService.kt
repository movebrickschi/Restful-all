package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.CaseChain

/**
 * v1.3.3 P2-9 - 用例编排链持久化（Pro）。
 *
 * - Project 级，存储到 `.idea/restful-all-case-chains.xml`
 * - 上限 100 条 chain；单 chain 步数上限见 [CaseChain.MAX_STEPS]
 */
@Service(Service.Level.PROJECT)
@State(
    name = "RestfulAll.CaseChains",
    storages = [Storage("restful-all-case-chains.xml")],
)
class CaseChainService(@Suppress("unused") private val project: Project) :
    PersistentStateComponent<CaseChainService.State> {

    data class State(var chains: MutableList<CaseChain> = mutableListOf())

    @Volatile
    private var myState = State()

    companion object {
        const val MAX_CHAINS: Int = 100

        fun getInstance(project: Project): CaseChainService =
            project.getService(CaseChainService::class.java)
    }

    override fun getState(): State = myState

    override fun loadState(state: State) { myState = state }

    fun list(): List<CaseChain> = myState.chains.toList()

    fun findById(id: String): CaseChain? = myState.chains.firstOrNull { it.id == id }

    fun upsert(chain: CaseChain) {
        require(chain.name.isNotBlank()) { "chain name must not be blank" }
        require(chain.steps.size <= CaseChain.MAX_STEPS) {
            "chain steps exceed ${CaseChain.MAX_STEPS}"
        }
        val idx = myState.chains.indexOfFirst { it.id == chain.id }
        if (idx >= 0) {
            chain.touch()
            myState.chains[idx] = chain
        } else {
            check(myState.chains.size < MAX_CHAINS) { "too many chains (max=$MAX_CHAINS)" }
            myState.chains.add(chain)
        }
    }

    fun delete(id: String) {
        if (!myState.chains.removeIf { it.id == id }) {
            thisLogger().warn("CaseChainService.delete: chain $id not found")
        }
    }
}
