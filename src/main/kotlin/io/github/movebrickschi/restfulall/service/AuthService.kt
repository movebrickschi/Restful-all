package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.AuthConfig
import io.github.movebrickschi.restfulall.model.AuthConfig.ApiKeyLocation
import io.github.movebrickschi.restfulall.model.AuthConfig.AuthType
import java.util.Base64

/**
 * v1.3 F5 - 鉴权注入服务。
 *
 * 根据 [AuthConfig] 将认证信息注入到 [RequestSpec] 的 headers / queryParams / cookies。
 * 不修改原 spec，返回新副本。
 */
object AuthService {

    /**
     * 将 [auth] 配置注入到 [spec]，返回增强后的 RequestSpec。
     *
     * 如果 auth.type == NONE 或 auth 未配置，直接返回原 spec。
     * 如果 spec.headers 中已存在 Authorization，不覆盖（用户手动优先）。
     */
    fun applyAuth(spec: RequestSpec, auth: AuthConfig?): RequestSpec {
        if (auth == null || auth.type == AuthType.NONE || !auth.isConfigured()) return spec

        return when (auth.type) {
            AuthType.BASIC -> {
                val existing = spec.headers.any { it.first.equals("Authorization", ignoreCase = true) }
                if (existing) return spec
                val encoded = Base64.getEncoder().encodeToString("${auth.basicUsername}:${auth.basicPassword}".toByteArray())
                spec.copy(headers = spec.headers + ("Authorization" to "Basic $encoded"))
            }
            AuthType.BEARER -> {
                val existing = spec.headers.any { it.first.equals("Authorization", ignoreCase = true) }
                if (existing) return spec
                spec.copy(headers = spec.headers + ("Authorization" to "Bearer ${auth.bearerToken}"))
            }
            AuthType.API_KEY -> when (auth.apiKeyLocation) {
                ApiKeyLocation.HEADER -> spec.copy(headers = spec.headers + (auth.apiKeyName to auth.apiKeyValue))
                ApiKeyLocation.QUERY -> spec.copy(queryParams = spec.queryParams + (auth.apiKeyName to auth.apiKeyValue))
                ApiKeyLocation.COOKIE -> spec.copy(cookies = spec.cookies + (auth.apiKeyName to auth.apiKeyValue))
            }
            AuthType.NONE -> spec
        }
    }
}
