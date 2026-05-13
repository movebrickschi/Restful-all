package io.github.movebrickschi.restfulall.export

import io.github.movebrickschi.restfulall.model.ExtractedFormParam
import io.github.movebrickschi.restfulall.model.ExtractedParam
import io.github.movebrickschi.restfulall.model.FormFieldType
import io.github.movebrickschi.restfulall.model.ParamLocation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDocumentExporterTest {

    @Test
    fun `exports openapi json with grouped paths and operations`() {
        val routes = listOf(
            endpoint("GET", "/api/users", "User list", "UserController", "listUsers", "User API"),
            endpoint("POST", "/api/users", "Create user", "UserController", "createUser", "User API"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_JSON, options())

        assertTrue(document.contains("\"openapi\": \"3.0.3\""))
        assertTrue(document.contains("\"/api/users\""))
        assertTrue(document.contains("\"get\""))
        assertTrue(document.contains("\"post\""))
        assertTrue(document.contains("\"summary\": \"User list\""))
        assertTrue(document.contains("\"tags\": [\"User API\"]"))
        assertTrue(document.contains("\"x-restful-all-source\""))
    }

    @Test
    fun `exports swagger json without shortening delete method`() {
        val routes = listOf(
            endpoint("DELETE", "api/users/{id}", "Delete user", "UserController", "deleteUser", "User API"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.SWAGGER_JSON, options())

        assertTrue(document.contains("\"swagger\": \"2.0\""))
        assertTrue(document.contains("\"/api/users/{id}\""))
        assertTrue(document.contains("\"delete\""))
        assertFalse(document.contains("\"del\""))
    }

    @Test
    fun `exports path parameters for templated paths`() {
        val routes = listOf(
            endpoint("GET", "/api/users/{id}", "Get user", "UserController", "getUser", "User API"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_JSON, options())

        assertTrue(document.contains("\"parameters\""))
        assertTrue(document.contains("\"name\": \"id\""))
        assertTrue(document.contains("\"in\": \"path\""))
        assertTrue(document.contains("\"required\": true"))
    }

    @Test
    fun `exports all method as valid openapi operations`() {
        val routes = listOf(
            endpoint("ALL", "/api/callback", "Callback", "CallbackController", "callback", "Webhook API"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_JSON, options())

        assertFalse(document.contains("\"all\""))
        assertTrue(document.contains("\"get\""))
        assertTrue(document.contains("\"post\""))
        assertTrue(document.contains("\"delete\""))
    }

    @Test
    fun `exports markdown chapters with source info`() {
        val routes = listOf(
            endpoint("GET", "/api/a|b", "A | B", "PipeController", "find", "Pipe API"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.MARKDOWN, options())

        assertTrue(document.contains("# Restful-all API"))
        assertTrue(document.contains("## Pipe API"))
        assertTrue(document.contains("### A | B"))
        assertTrue(document.contains("`GET /api/a|b`"))
        assertTrue(document.contains("PipeController#find"))
        assertTrue(document.contains("PipeController.kt:12"))
    }

    @Test
    fun `exports yaml variants`() {
        val routes = listOf(
            endpoint("GET", "/api/health", "Health", "HealthController", "health", "System"),
        )

        val openApiYaml = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_YAML, options())
        val swaggerYaml = ApiDocumentExporter.export(routes, ApiDocumentFormat.SWAGGER_YAML, options())

        assertTrue(openApiYaml.contains("openapi: 3.0.3"))
        assertTrue(openApiYaml.contains("/api/health:"))
        assertTrue(swaggerYaml.contains("swagger: '2.0'"))
        assertTrue(swaggerYaml.contains("/api/health:"))
    }

    @Test
    fun `quotes yaml flow list strings that contain commas`() {
        val routes = listOf(
            endpoint("GET", "/api/admin", "Admin", "AdminController", "admin", "Admin, User"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_YAML, options())

        assertTrue(document.contains("tags: ['Admin, User']"))
    }

    @Test
    fun `safe defaults hide absolute source path in openapi extension`() {
        val routes = listOf(
            endpoint("GET", "/api/users", "User list", "UserController", "listUsers", "User API"),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_JSON, options())

        assertTrue(document.contains("\"file\": \"UserController.kt\""))
        assertFalse(document.contains("/project/src/UserController.kt"))
    }

    @Test
    fun `safe defaults redact sensitive parameter examples`() {
        val routes = listOf(
            endpoint("GET", "/api/users", "User list", "UserController", "listUsers", "User API").copy(
                queryParams = listOf(ExtractedParam("api_key", ParamLocation.QUERY, "raw-api-key")),
                headerParams = listOf(ExtractedParam("Authorization", ParamLocation.HEADER, "Bearer raw-token")),
                cookieParams = listOf(ExtractedParam("session", ParamLocation.COOKIE, "raw-session")),
                formParams = listOf(ExtractedFormParam("password", FormFieldType.TEXT, "raw-password")),
                bodyJson = """{"username":"alice","password":"raw-password"}""",
                responseJson = """{"access_token":"raw-token","ok":true}""",
            ),
        )

        val document = ApiDocumentExporter.export(routes, ApiDocumentFormat.OPENAPI_JSON, options())

        assertFalse(document.contains("raw-api-key"))
        assertFalse(document.contains("Bearer raw-token"))
        assertFalse(document.contains("raw-session"))
        assertFalse(document.contains("raw-password"))
        assertTrue(document.contains("***REDACTED***"))
    }

    @Test
    fun `unsafe options can include source path and examples`() {
        val routes = listOf(
            endpoint("GET", "/api/users", "User list", "UserController", "listUsers", "User API").copy(
                queryParams = listOf(ExtractedParam("api_key", ParamLocation.QUERY, "raw-api-key")),
            ),
        )

        val document = ApiDocumentExporter.export(
            routes,
            ApiDocumentFormat.OPENAPI_JSON,
            options(includeSourcePath = true, redactExamples = false),
        )

        assertTrue(document.contains("/project/src/UserController.kt"))
        assertTrue(document.contains("raw-api-key"))
    }

    private fun endpoint(
        method: String,
        path: String,
        summary: String,
        className: String,
        functionName: String,
        group: String,
    ): ApiExportEndpoint =
        ApiExportEndpoint(
            method = method,
            path = path,
            summary = summary,
            group = group,
            className = className,
            functionName = functionName,
            framework = "Spring",
            sourceFileName = "$className.kt",
            sourcePath = "/project/src/$className.kt",
            sourceLine = 12,
        )

    private fun options(
        includeSourcePath: Boolean = false,
        redactExamples: Boolean = true,
    ): ApiDocumentOptions =
        ApiDocumentOptions(
            title = "Restful-all API",
            version = "1.0.0",
            description = "Generated by Restful-all",
            includeSourcePath = includeSourcePath,
            redactExamples = redactExamples,
        )
}
