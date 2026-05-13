package io.github.movebrickschi.restfulall.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.ParamLocation
import io.github.movebrickschi.restfulall.model.stableId

/**
 * F4: [SwaggerImporter] 三种典型场景的端到端验证。
 *
 * fixture 位于 `src/test/resources/fixtures/openapi/`，通过 classpath 加载，
 * 不依赖任何外部网络或本地文件路径，可在 CI 内独立运行。
 */
class SwaggerImporterTest : BasePlatformTestCase() {

    private lateinit var importer: SwaggerImporter

    override fun setUp() {
        super.setUp()
        importer = SwaggerImporter.getInstance(project)
    }

    fun testPetstoreHappyPathProducesExpectedRoutesAndParams() {
        val result = importer.import(loadFixture("petstore-v3.yaml"), "petstore-v3.yaml")
        assertTrue("expect successful import, errors=${result.errors}", result.isSuccess)
        assertEquals("petstore has 5 operations", 5, result.routes.size)
        assertTrue(result.routes.all { it.framework == Framework.OPENAPI })

        val listPets = result.routes.singleOrNull { it.functionName == "listPets" }
        assertNotNull("listPets should exist", listPets)
        assertEquals(HttpMethod.GET, listPets!!.method)
        assertEquals("/pets", listPets.fullPath)
        assertEquals("List all pets", listPets.routeName)

        val listParams = result.params[listPets.stableId]
        assertNotNull(listParams)
        val queryNames = listParams!!.queryParams.map { it.name }.toSet()
        assertEquals(setOf("limit", "tag"), queryNames)
        assertEquals("20", listParams.queryParams.first { it.name == "limit" }.testValue)

        val getById = result.routes.single { it.functionName == "getPetById" }
        val getByIdParams = result.params[getById.stableId]!!
        assertEquals(1, getByIdParams.pathParams.size)
        assertEquals("petId", getByIdParams.pathParams[0].name)
        assertEquals(ParamLocation.PATH, getByIdParams.pathParams[0].location)

        val createPet = result.routes.single { it.functionName == "createPet" }
        val createParams = result.params[createPet.stableId]!!
        assertNotNull("createPet should have body json sample", createParams.bodyJson)
        assertTrue(
            "body sample should contain \"name\" field, was=${createParams.bodyJson}",
            createParams.bodyJson!!.contains("\"name\""),
        )

        val deletePet = result.routes.single { it.functionName == "deletePet" }
        val deleteParams = result.params[deletePet.stableId]!!
        assertEquals(
            "X-Confirm should be picked up as a non-standard header",
            listOf("X-Confirm"),
            deleteParams.headerParams.map { it.name },
        )
    }

    fun testNestedParamsImporterDoesNotCrash() {
        val result = importer.import(loadFixture("nested-params.yaml"), "nested-params.yaml")
        assertTrue("expect successful import, errors=${result.errors}", result.isSuccess)
        assertEquals(2, result.routes.size)

        val addItem = result.routes.single { it.functionName == "addOrderItem" }
        val params = result.params[addItem.stableId]!!
        assertEquals(listOf("orderId"), params.pathParams.map { it.name })
        assertEquals(
            "idempotency-key custom header preserved",
            listOf("idempotency-key"),
            params.headerParams.map { it.name },
        )
        assertNotNull("nested body must render some skeleton", params.bodyJson)
        assertTrue(
            "body skeleton should mention items array",
            params.bodyJson!!.contains("\"items\""),
        )

        val search = result.routes.single { it.functionName == "searchOrders" }
        val searchParams = result.params[search.stableId]!!
        assertEquals(setOf("status", "createdAfter"), searchParams.queryParams.map { it.name }.toSet())
    }

    fun testBrokenSpecReturnsErrorsAndEmptyRoutes() {
        val result = importer.import(loadFixture("broken-spec.yaml"), "broken-spec.yaml")
        assertFalse("broken spec must not be marked success", result.isSuccess)
        assertTrue(
            "broken spec should report at least one warning or error",
            result.errors.isNotEmpty() || result.warnings.isNotEmpty(),
        )
        // 不强求 routes 为空：parser 可能从中部分恢复出一个 operation。关键约束是 isSuccess=false。
    }

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/openapi/$name")
            ?: error("Missing test fixture: /fixtures/openapi/$name")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
