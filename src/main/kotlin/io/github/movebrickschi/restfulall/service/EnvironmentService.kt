package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.movebrickschi.restfulall.model.EnvironmentEntry
import io.github.movebrickschi.restfulall.model.EnvVariable

/**
 * v1.3 F1 - 环境变量管理。
 *
 * 提供多环境 CRUD + `${var}` 模板替换 + 密文变量（委托 [SecretStorageService]）。
 * 持久化到项目级 XML：`.idea/restful-all-env.xml`。
 *
 * ## 变量替换规则
 *
 * 1. 语法：`${varName}` — 贪婪匹配最短 `}`
 * 2. 优先从当前 active 环境取值，找不到则保留原文 `${varName}`
 * 3. 支持嵌套：`${host}:${port}` 拆成两次替换
 * 4. 循环引用检测：`${a}` → 含 `${b}` → 含 `${a}` 抛 [CircularReferenceException]
 * 5. 密文变量（`secret=true`）：value 走 PasswordSafe，XML 中只存占位
 *
 * ## 线程模型
 *
 * 状态修改（[upsert] / [delete] / [setActive]）在 EDT 上调用。
 * [resolve] 可在任意线程调用，内部拷贝 snapshot 避免并发问题。
 */
@Service(Service.Level.PROJECT)
@State(
    name = "RestfulAll.Environments",
    storages = [Storage("restful-all-env.xml")],
)
class EnvironmentService(private val project: Project) : PersistentStateComponent<EnvironmentService.EnvState> {

    data class EnvState(
        var environments: MutableList<EnvironmentEntry> = mutableListOf(),
        var activeId: String? = null,
    )

    @Volatile
    private var myState: EnvState = EnvState()

    companion object {
        private const val MAX_ENVS = 50
        private const val MAX_VARS_PER_ENV = 200
        private const val MAX_RESOLVE_DEPTH = 10
        private val VAR_PATTERN = Regex("""\$\{([^}]+)}""")

        fun getInstance(project: Project): EnvironmentService =
            project.getService(EnvironmentService::class.java)
    }

    override fun getState(): EnvState = myState

    override fun loadState(state: EnvState) {
        myState = state
        if (myState.environments.isEmpty()) {
            myState.environments.add(EnvironmentEntry.newDefault())
            myState.activeId = EnvironmentEntry.DEFAULT_ID
        }
    }

    fun listEnvironments(): List<EnvironmentEntry> = myState.environments.toList()

    fun getActive(): EnvironmentEntry? {
        val id = myState.activeId ?: return myState.environments.firstOrNull()
        return myState.environments.firstOrNull { it.id == id }
    }

    fun setActive(envId: String) {
        if (myState.environments.none { it.id == envId }) {
            thisLogger().warn("setActive: envId=$envId not found")
            return
        }
        myState.activeId = envId
    }

    fun findById(envId: String): EnvironmentEntry? =
        myState.environments.firstOrNull { it.id == envId }

    fun upsert(env: EnvironmentEntry) {
        require(env.name.isNotBlank()) { "Environment name must not be blank" }
        require(env.variables.size <= MAX_VARS_PER_ENV) {
            "Too many variables (max=$MAX_VARS_PER_ENV)"
        }
        val idx = myState.environments.indexOfFirst { it.id == env.id }
        if (idx >= 0) {
            env.touch()
            myState.environments[idx] = env
        } else {
            require(myState.environments.size < MAX_ENVS) {
                "Too many environments (max=$MAX_ENVS)"
            }
            myState.environments.add(env)
        }
        if (myState.activeId == null) {
            myState.activeId = env.id
        }
    }

    fun delete(envId: String) {
        val secrets = myState.environments
            .firstOrNull { it.id == envId }
            ?.variables
            ?.filter { it.secret }
            ?.map { SecretStorageService.envKey(envId, it.key) }
            .orEmpty()
        if (secrets.isNotEmpty()) {
            try {
                SecretStorageService.getInstance(project).removeAll(secrets)
            } catch (e: Exception) {
                thisLogger().warn("Failed to clean secrets for env=$envId", e)
            }
        }
        myState.environments.removeIf { it.id == envId }
        if (myState.activeId == envId) {
            myState.activeId = myState.environments.firstOrNull()?.id
        }
    }

    /**
     * 将模板字符串中的 `${var}` 替换为当前 active 环境（或指定 [envId]）的变量值。
     *
     * @param template 含 `${var}` 占位的字符串
     * @param envId 指定环境 ID，null 时使用当前 active
     * @return 替换后的字符串
     * @throws CircularReferenceException 循环引用
     * @throws MissingVariableException 变量未定义且 [strict] = true
     */
    fun resolve(template: String, envId: String? = null, strict: Boolean = false): String {
        if (!template.contains("\${")) return template
        val env = if (envId != null) findById(envId) else getActive() ?: return template
        val varMap = buildVarMap(env)
        return resolveInternal(template, varMap, strict, mutableSetOf(), 0)
    }

    private fun buildVarMap(env: EnvironmentEntry): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (v in env.activeVariables()) {
            val value = if (v.secret) {
                val ref = v.secretRefKey() ?: SecretStorageService.envKey(env.id, v.key)
                try {
                    SecretStorageService.getInstance(project).getSecret(ref) ?: ""
                } catch (e: Exception) {
                    thisLogger().warn("Failed to read secret for key=${v.key}", e)
                    ""
                }
            } else {
                v.value
            }
            map[v.key] = value
        }
        return map
    }

    private fun resolveInternal(
        input: String,
        varMap: Map<String, String>,
        strict: Boolean,
        visiting: MutableSet<String>,
        depth: Int,
    ): String {
        if (depth > MAX_RESOLVE_DEPTH) {
            throw CircularReferenceException("Resolve depth exceeded $MAX_RESOLVE_DEPTH")
        }
        return VAR_PATTERN.replace(input) { match ->
            val key = match.groupValues[1]
            if (key in visiting) {
                throw CircularReferenceException("Circular reference detected: $key")
            }
            val raw = varMap[key]
            if (raw == null) {
                if (strict) throw MissingVariableException(key)
                match.value
            } else {
                visiting.add(key)
                val resolved = resolveInternal(raw, varMap, strict, visiting, depth + 1)
                visiting.remove(key)
                resolved
            }
        }
    }

    class CircularReferenceException(message: String) : RuntimeException(message)
    class MissingVariableException(val variableName: String) :
        RuntimeException("Environment variable not found: $variableName")
}
