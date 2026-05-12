package io.github.movebrickschi.restfulall.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

/**
 * v1.3 - 密文存储统一入口。
 *
 * 包装 IntelliJ [PasswordSafe]（底层走 macOS Keychain / Windows Credential Manager / KeePass）。
 * 所有需要存密文的 v1.3 模块（F1 env secret / F5 auth token / P0-1 AI key / P1-4 sync token）
 * **必须**通过本服务读写，禁止直接调 PasswordSafe。
 *
 * ## 命名空间（namespace）设计
 *
 * 所有 key 均以 `restful-all:<namespace>:<suffix>` 格式生成，避免跨项目 / 跨功能碰撞。
 *
 * | namespace | suffix 格式 | 用途 |
 * |-----------|------------|------|
 * | `env`     | `<envId>:<varKey>` | 环境变量密文值（F1） |
 * | `auth`    | `<authConfigId>` | 鉴权模板的 token / password（F5） |
 * | `ai`      | `<provider>` | AI API Key（P0-1~3） |
 * | `sync`    | `<accountId>` | 云同步登录 token（P1-4） |
 *
 * ## 线程安全
 *
 * PasswordSafe 自身对 credential store 的访问是同步的。本服务不做额外锁，
 * 但调用方不应在 EDT 大批量调用（> 50 次），否则 KeePass 后端可能卡 UI。
 */
@Service(Service.Level.PROJECT)
class SecretStorageService(private val project: Project) {

    companion object {
        private const val SUBSYSTEM = "Restful-all"

        fun getInstance(project: Project): SecretStorageService =
            project.getService(SecretStorageService::class.java)

        @JvmStatic
        fun envKey(envId: String, varKey: String): String = "env:$envId:$varKey"

        @JvmStatic
        fun authKey(authConfigId: String): String = "auth:$authConfigId"

        @JvmStatic
        fun aiKey(provider: String): String = "ai:$provider"

        @JvmStatic
        fun syncKey(accountId: String): String = "sync:$accountId"
    }

    /**
     * 存入密文。
     *
     * @param namespaceKey 按 [envKey] / [authKey] / [aiKey] / [syncKey] 生成的完整 key。
     * @param value 明文值，存入后仅 PasswordSafe 可读。
     */
    fun setSecret(namespaceKey: String, value: String) {
        try {
            val attr = createAttributes(namespaceKey)
            PasswordSafe.instance.set(attr, Credentials(namespaceKey, value))
        } catch (e: Exception) {
            thisLogger().warn("SecretStorageService.setSecret failed for key=$namespaceKey", e)
        }
    }

    /**
     * 读取密文。
     *
     * @return 明文值，或 null（未存储 / PasswordSafe 不可用 / 解密失败）。
     */
    fun getSecret(namespaceKey: String): String? {
        return try {
            val attr = createAttributes(namespaceKey)
            PasswordSafe.instance.get(attr)?.getPasswordAsString()
        } catch (e: Exception) {
            thisLogger().warn("SecretStorageService.getSecret failed for key=$namespaceKey", e)
            null
        }
    }

    /**
     * 删除密文。
     *
     * 如果该 key 不存在则静默返回。
     */
    fun removeSecret(namespaceKey: String) {
        try {
            val attr = createAttributes(namespaceKey)
            PasswordSafe.instance.set(attr, null)
        } catch (e: Exception) {
            thisLogger().warn("SecretStorageService.removeSecret failed for key=$namespaceKey", e)
        }
    }

    /**
     * 检查密文是否存在。
     *
     * 注意：可能因 PasswordSafe 后端异常返回 false（此时 [getSecret] 也会返回 null）。
     */
    fun hasSecret(namespaceKey: String): Boolean = getSecret(namespaceKey) != null

    /**
     * 批量删除某命名空间下的所有密文。
     *
     * 典型场景：删除整个环境时调用 `removeByPrefix("env:$envId:")`。
     *
     * 限制：PasswordSafe 不支持按前缀扫描，因此需要调用方传入所有 key 列表。
     */
    fun removeAll(namespaceKeys: Collection<String>) {
        namespaceKeys.forEach { removeSecret(it) }
    }

    private fun createAttributes(namespaceKey: String): CredentialAttributes =
        CredentialAttributes(generateServiceName(SUBSYSTEM, namespaceKey))
}
