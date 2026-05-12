package io.github.movebrickschi.restfulall.service

import io.github.movebrickschi.restfulall.model.AuthConfig
import io.github.movebrickschi.restfulall.model.AuthConfig.ApiKeyLocation
import io.github.movebrickschi.restfulall.model.AuthConfig.AuthType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthServiceTest {

    private val baseSpec = RequestSpec(method = "GET", url = "http://localhost/api")

    @Test
    fun `NONE auth should not modify spec`() {
        val result = AuthService.applyAuth(baseSpec, AuthConfig(type = AuthType.NONE))
        assertEquals(baseSpec, result)
    }

    @Test
    fun `null auth should not modify spec`() {
        val result = AuthService.applyAuth(baseSpec, null)
        assertEquals(baseSpec, result)
    }

    @Test
    fun `Bearer should add Authorization header`() {
        val auth = AuthConfig(type = AuthType.BEARER, bearerToken = "my-token-123")
        val result = AuthService.applyAuth(baseSpec, auth)
        val authHeader = result.headers.first { it.first == "Authorization" }
        assertEquals("Bearer my-token-123", authHeader.second)
    }

    @Test
    fun `Basic should add Base64 encoded header`() {
        val auth = AuthConfig(type = AuthType.BASIC, basicUsername = "admin", basicPassword = "pass")
        val result = AuthService.applyAuth(baseSpec, auth)
        val authHeader = result.headers.first { it.first == "Authorization" }
        assertTrue(authHeader.second.startsWith("Basic "))
        val decoded = String(java.util.Base64.getDecoder().decode(authHeader.second.removePrefix("Basic ")))
        assertEquals("admin:pass", decoded)
    }

    @Test
    fun `API_KEY in header`() {
        val auth = AuthConfig(type = AuthType.API_KEY, apiKeyName = "X-API-Key", apiKeyValue = "abc", apiKeyLocation = ApiKeyLocation.HEADER)
        val result = AuthService.applyAuth(baseSpec, auth)
        assertEquals("abc", result.headers.first { it.first == "X-API-Key" }.second)
    }

    @Test
    fun `API_KEY in query`() {
        val auth = AuthConfig(type = AuthType.API_KEY, apiKeyName = "api_key", apiKeyValue = "xyz", apiKeyLocation = ApiKeyLocation.QUERY)
        val result = AuthService.applyAuth(baseSpec, auth)
        assertEquals("xyz", result.queryParams.first { it.first == "api_key" }.second)
    }

    @Test
    fun `API_KEY in cookie`() {
        val auth = AuthConfig(type = AuthType.API_KEY, apiKeyName = "token", apiKeyValue = "tok123", apiKeyLocation = ApiKeyLocation.COOKIE)
        val result = AuthService.applyAuth(baseSpec, auth)
        assertEquals("tok123", result.cookies.first { it.first == "token" }.second)
    }

    @Test
    fun `existing Authorization header should not be overridden`() {
        val specWithAuth = baseSpec.copy(headers = listOf("Authorization" to "Bearer existing"))
        val auth = AuthConfig(type = AuthType.BEARER, bearerToken = "new-token")
        val result = AuthService.applyAuth(specWithAuth, auth)
        assertEquals(1, result.headers.count { it.first == "Authorization" })
        assertEquals("Bearer existing", result.headers.first { it.first == "Authorization" }.second)
    }

    @Test
    fun `unconfigured auth should not modify spec`() {
        val auth = AuthConfig(type = AuthType.BEARER, bearerToken = "")
        val result = AuthService.applyAuth(baseSpec, auth)
        assertEquals(baseSpec, result)
    }
}
