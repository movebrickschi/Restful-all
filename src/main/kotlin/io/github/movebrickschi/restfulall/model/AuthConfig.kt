package io.github.movebrickschi.restfulall.model

import java.util.UUID

/**
 * v1.3 F5 - 鉴权配置模型。
 *
 * 每个请求或 collection 可绑定一个 AuthConfig，
 * 发送时由 AuthService 注入到 headers / query / cookie。
 */
data class AuthConfig(
    var id: String = UUID.randomUUID().toString(),
    var type: AuthType = AuthType.NONE,
    var bearerToken: String = "",
    var basicUsername: String = "",
    var basicPassword: String = "",
    var apiKeyName: String = "",
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
