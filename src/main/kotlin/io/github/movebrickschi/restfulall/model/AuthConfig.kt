package io.github.movebrickschi.restfulall.model

import com.intellij.util.xmlb.annotations.Transient
import java.util.UUID

/**
 * v1.3 F5 - 鉴权配置模型。
 *
 * 每个请求或 collection 可绑定一个 AuthConfig，
 * 发送时由 AuthService 注入到 headers / query / cookie。
 *
 * ## 安全：密文字段不可被 IntelliJ XmlSerializer 落盘
 *
 * `bearerToken` / `basicPassword` / `apiKeyValue` 用 `@get:Transient` 标注，
 * 即便本对象被嵌入到任何 `PersistentStateComponent`（PluginSettingsState /
 * CollectionService / 任意 `@State` 子树）的状态树中，这三个字段也不会被序列化到
 * `restful-all.xml` / `workspace.xml` 等明文 XML。
 *
 * 调用方如需持久化密文，**必须**通过
 * [io.github.movebrickschi.restfulall.service.SecretStorageService] 配合
 * [io.github.movebrickschi.restfulall.service.SecretStorageService.authKey] 写入
 * 操作系统密钥链（macOS Keychain / Windows Credential Manager / KeePass）。
 *
 * 反序列化后这三个字段会回到默认空串，调用方负责从 SecretStorageService 重新装载。
 */
data class AuthConfig(
    var id: String = UUID.randomUUID().toString(),
    var type: AuthType = AuthType.NONE,
    @get:Transient
    var bearerToken: String = "",
    var basicUsername: String = "",
    @get:Transient
    var basicPassword: String = "",
    var apiKeyName: String = "",
    @get:Transient
    var apiKeyValue: String = "",
    var apiKeyLocation: ApiKeyLocation = ApiKeyLocation.HEADER,
) {
    enum class AuthType { NONE, BASIC, BEARER, API_KEY }
    enum class ApiKeyLocation { HEADER, QUERY, COOKIE }

    fun isConfigured(): Boolean = when (type) {
        AuthType.NONE -> false
        AuthType.BASIC -> basicUsername.isNotBlank()
        AuthType.BEARER -> bearerToken.isNotBlank()
        AuthType.API_KEY -> apiKeyName.isNotBlank() && apiKeyValue.isNotBlank()
    }
}
