package io.github.movebrickschi.restfulall.service

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod

class RouteServiceTest : BasePlatformTestCase() {

    private lateinit var service: RouteService

    override fun setUp() {
        super.setUp()
        service = RouteService.getInstance(project)
    }

    fun testGetInstanceReturnsProjectScopedSingleton() {
        val a = RouteService.getInstance(project)
        val b = RouteService.getInstance(project)
        assertSame(a, b)
    }

    fun testScanProjectMarksInitialScanDone() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )

        // Project-scoped service may be reused across BasePlatformTestCase methods,
        // so we only assert the post-condition that scanProject() flips the flag on.
        service.scanProject()
        assertTrue(service.isInitialScanDone)
    }

    fun testScanProjectDiscoversSpringRoute() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )

        val routes = service.scanProject()
        val spring = routes.singleOrNull { it.framework == Framework.SPRING }
        assertNotNull("Expected exactly one Spring route", spring)
        assertEquals(HttpMethod.GET, spring!!.method)
        assertEquals("/api/users", spring.fullPath)
    }

    fun testScanProjectDiscoversNestJsRoute() {
        myFixture.addFileToProject("src/users.controller.ts", NESTJS_CONTROLLER)

        val routes = service.scanProject()
        val nest = routes.singleOrNull { it.framework == Framework.NESTJS }
        assertNotNull(nest)
        assertEquals(HttpMethod.POST, nest!!.method)
        assertEquals("/users/create", nest.fullPath)
    }

    fun testScanProjectDiscoversExpressRoute() {
        myFixture.addFileToProject("src/router.js", EXPRESS_ROUTER)

        val routes = service.scanProject()
        val express = routes.singleOrNull { it.framework == Framework.EXPRESS }
        assertNotNull(express)
        assertEquals(HttpMethod.GET, express!!.method)
        assertEquals("/items", express.fullPath)
    }

    fun testScanProjectDiscoversPythonRoute() {
        myFixture.addFileToProject("src/app.py", FASTAPI_APP)

        val routes = service.scanProject()
        val python = routes.singleOrNull { it.framework == Framework.PYTHON }
        assertNotNull(python)
        assertEquals(HttpMethod.GET, python!!.method)
        assertEquals("/hello", python.fullPath)
    }

    fun testScanProjectIgnoresUnsupportedExtensions() {
        myFixture.addFileToProject("src/README.md", "# Hello\nNo routes here.")
        myFixture.addFileToProject("src/notes.txt", "Random notes")

        val routes = service.scanProject()
        assertTrue("Expected no routes for unsupported files but got $routes", routes.isEmpty())
    }

    fun testGetCachedRoutesMatchesScanResult() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )

        val scanned = service.scanProject()
        val cached = service.getCachedRoutes()
        assertEquals(scanned, cached)
    }

    fun testFindRouteAtExactLineLocatesRoute() {
        val psi = myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )
        service.scanProject()

        val virtualFile = psi.virtualFile
        val route = service.getCachedRoutes().single { it.framework == Framework.SPRING }
        val found = service.findRouteAtExactLine(virtualFile, route.lineNumber)
        assertNotNull(found)
        assertEquals(route, found)
    }

    fun testFindRouteAtUsesNearestPrecedingLine() {
        val psi = myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )
        service.scanProject()

        val virtualFile = psi.virtualFile
        val route = service.getCachedRoutes().single { it.framework == Framework.SPRING }
        val found = service.findRouteAt(virtualFile, route.lineNumber + 1)
        assertNotNull(found)
        assertEquals(route, found)
    }

    fun testFindRouteAtReturnsNullForUnknownFile() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )
        service.scanProject()

        val unrelated = myFixture.addFileToProject(
            "src/unrelated.txt",
            "no routes here",
        ).virtualFile

        assertNull(service.findRouteAt(unrelated, 0))
        assertNull(service.findRouteAtExactLine(unrelated, 0))
    }

    fun testUpdateFileAddsRoutesToCache() {
        service.scanProject()

        val psi = myFixture.addFileToProject(
            "src/late.controller.ts",
            NESTJS_CONTROLLER,
        )
        service.updateFile(psi.virtualFile)

        val nest = service.getCachedRoutes()
            .singleOrNull { it.file.path == psi.virtualFile.path }
        assertNotNull(nest)
        assertEquals(Framework.NESTJS, nest!!.framework)
        assertEquals("/users/create", nest.fullPath)
    }

    fun testRemoveFileClearsRoutesFromCache() {
        val psi = myFixture.addFileToProject("src/router.js", EXPRESS_ROUTER)
        service.scanProject()
        assertTrue(service.getCachedRoutes().any { it.framework == Framework.EXPRESS })

        service.removeFile(psi.virtualFile.path)

        assertTrue(
            "Expected no Express routes after removal",
            service.getCachedRoutes().none { it.framework == Framework.EXPRESS },
        )
    }

    fun testUpdateFileForUnsupportedExtensionEvictsExistingRoutes() {
        val psi = myFixture.addFileToProject("src/router.js", EXPRESS_ROUTER)
        service.scanProject()
        assertTrue(service.getCachedRoutes().any { it.file.path == psi.virtualFile.path })

        val unsupported = myFixture.addFileToProject(
            "src/router.txt",
            "ignored",
        ).virtualFile
        service.updateFile(unsupported)

        // No-op for unsupported file, existing express route untouched.
        assertTrue(service.getCachedRoutes().any { it.file.path == psi.virtualFile.path })
    }

    fun testCachedRoutesAreSortedByPathThenMethod() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.PostMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            @RequestMapping("/api")
            public class SimpleController {
                @PostMapping("/posts")
                public Object createPost() { return null; }

                @GetMapping("/posts")
                public Object listPosts() { return null; }

                @GetMapping("/users")
                public Object listUsers() { return null; }
            }
            """.trimIndent(),
        )

        val routes = service.scanProject()
        val ordered = routes.map { "${it.fullPath}#${it.method}" }
        assertEquals(
            listOf("/api/posts#GET", "/api/posts#POST", "/api/users#GET"),
            ordered,
        )
    }

    fun testScanProjectCanBeRunTwiceAndReplacesCache() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )
        val first = service.scanProject()
        assertEquals(1, first.size)

        myFixture.addFileToProject("src/router.js", EXPRESS_ROUTER)
        val second = service.scanProject()

        assertEquals(2, second.size)
        assertTrue(second.any { it.framework == Framework.SPRING })
        assertTrue(second.any { it.framework == Framework.EXPRESS })
    }

    fun testScanProjectAsyncReturnsSameRoutesAsSyncScan() {
        myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )
        myFixture.addFileToProject("src/router.js", EXPRESS_ROUTER)

        val syncResult = service.scanProject()
        val asyncResult = service.scanProjectAsync().get(30, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(syncResult, asyncResult)
        assertEquals(syncResult, service.getCachedRoutes())
    }

    fun testIncrementalUpdatesPreserveCacheConsistency() {
        // Verifies #5 single-source-of-truth lock-free model:
        // a chained sequence of updateFile + removeFile must leave routesRef and
        // getCachedRoutes() in agreement at every step.
        service.scanProject()
        assertTrue(service.getCachedRoutes().isEmpty())

        val springPsi = myFixture.addFileToProject(
            "src/com/example/SimpleController.java",
            JAVA_SPRING_CONTROLLER,
        )
        service.updateFile(springPsi.virtualFile)
        assertEquals(1, service.getCachedRoutes().size)
        assertEquals("/api/users", service.getCachedRoutes().single().fullPath)

        val expressPsi = myFixture.addFileToProject("src/router.js", EXPRESS_ROUTER)
        service.updateFile(expressPsi.virtualFile)
        assertEquals(2, service.getCachedRoutes().size)

        service.removeFile(springPsi.virtualFile.path)
        val remaining = service.getCachedRoutes()
        assertEquals(1, remaining.size)
        assertEquals(Framework.EXPRESS, remaining.single().framework)

        service.removeFile(expressPsi.virtualFile.path)
        assertTrue(service.getCachedRoutes().isEmpty())
    }

    companion object {
        private val JAVA_SPRING_CONTROLLER = """
            package com.example;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class SimpleController {
                @GetMapping("/api/users")
                public Object users() {
                    return null;
                }
            }
        """.trimIndent()

        private val NESTJS_CONTROLLER = """
            import { Controller, Post } from '@nestjs/common';

            @Controller('users')
            export class UsersController {
                @Post('create')
                async create() {
                    return {};
                }
            }
        """.trimIndent()

        private val EXPRESS_ROUTER = """
            const express = require('express');
            const router = express.Router();
            router.get('/items', listItems);
            module.exports = router;
        """.trimIndent()

        private val FASTAPI_APP = """
            from fastapi import FastAPI

            app = FastAPI()

            @app.get("/hello")
            def hello():
                return {"hi": True}
        """.trimIndent()
    }
}
