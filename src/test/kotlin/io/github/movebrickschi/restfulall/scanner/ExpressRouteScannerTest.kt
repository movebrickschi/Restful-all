package io.github.movebrickschi.restfulall.scanner

import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressRouteScannerTest {

    @Test
    fun `supportedExtensions covers ts tsx js jsx`() {
        val exts = ExpressRouteScanner().supportedExtensions()
        assertEquals(setOf("ts", "tsx", "js", "jsx"), exts)
    }

    @Test
    fun `returns empty when content has no router or app prefix`() {
        val file = TestVirtualFile(
            "service.js",
            """
            module.exports = function () {
                return 42;
            };
            """.trimIndent(),
        )

        val routes = ExpressRouteScanner().scanFile(file)

        assertTrue(routes.isEmpty())
    }

    @Test
    fun `extracts router get with handler name`() {
        val file = TestVirtualFile(
            "users.js",
            """
            const express = require('express');
            const router = express.Router();

            router.get('/users', listUsers);

            module.exports = router;
            """.trimIndent(),
        )

        val route = ExpressRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/users", route.fullPath)
        assertEquals("users", route.className)
        assertEquals("listUsers", route.functionName)
        assertEquals(Framework.EXPRESS, route.framework)
    }

    @Test
    fun `extracts app post and prefixes leading slash when missing`() {
        val file = TestVirtualFile(
            "auth.js",
            """
            const app = require('express')();
            app.post('login', handleLogin);
            """.trimIndent(),
        )

        val route = ExpressRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.POST, route.method)
        assertEquals("/login", route.fullPath)
        assertEquals("handleLogin", route.functionName)
    }

    @Test
    fun `inline arrow handler is treated as anonymous`() {
        val file = TestVirtualFile(
            "inline.js",
            """
            const app = require('express')();
            app.get('/health', (req, res) => res.send('ok'));
            """.trimIndent(),
        )

        val route = ExpressRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/health", route.fullPath)
        assertEquals("anonymous", route.functionName)
    }

    @Test
    fun `inline function expression handler is treated as anonymous`() {
        val file = TestVirtualFile(
            "inline-func.js",
            """
            const app = require('express')();
            app.post('/submit', function (req, res) { res.send('ok'); });
            """.trimIndent(),
        )

        val route = ExpressRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.POST, route.method)
        assertEquals("anonymous", route.functionName)
    }

    @Test
    fun `detects multiple HTTP methods`() {
        val file = TestVirtualFile(
            "crud.js",
            """
            const router = require('express').Router();
            router.get('/items', list);
            router.post('/items', create);
            router.put('/items/:id', update);
            router.delete('/items/:id', remove);
            router.patch('/items/:id', touch);
            router.head('/items', head);
            router.options('/items', opt);
            router.all('/items/any', any);
            """.trimIndent(),
        )

        val routes = ExpressRouteScanner().scanFile(file)
        val methods = routes.map { it.method }.toSet()

        assertEquals(
            setOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.DELETE,
                HttpMethod.PATCH,
                HttpMethod.HEAD,
                HttpMethod.OPTIONS,
                HttpMethod.ALL,
            ),
            methods,
        )
        assertEquals(8, routes.size)
    }

    @Test
    fun `ignores routes in line comments`() {
        val file = TestVirtualFile(
            "commented.js",
            """
            const router = require('express').Router();
            // router.get('/disabled', disabled);
            router.get('/active', active);
            """.trimIndent(),
        )

        val routes = ExpressRouteScanner().scanFile(file)

        assertEquals(1, routes.size)
        assertEquals("/active", routes.single().fullPath)
    }

    @Test
    fun `ignores routes in block comments`() {
        val file = TestVirtualFile(
            "block-commented.js",
            """
            const router = require('express').Router();
            /*
             * router.get('/dead', dead);
             */
            router.post('/alive', alive);
            """.trimIndent(),
        )

        val routes = ExpressRouteScanner().scanFile(file)

        assertEquals(1, routes.size)
        assertEquals(HttpMethod.POST, routes.single().method)
        assertEquals("/alive", routes.single().fullPath)
    }

    @Test
    fun `case insensitive HTTP method names`() {
        val file = TestVirtualFile(
            "mixed-case.js",
            """
            const router = require('express').Router();
            router.GET('/loud', loud);
            """.trimIndent(),
        )

        val route = ExpressRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/loud", route.fullPath)
    }

    @Test
    fun `tracks lineNumber correctly`() {
        val file = TestVirtualFile(
            "line-tracked.js",
            """
            const router = require('express').Router();

            router.get('/where', here);
            """.trimIndent(),
        )

        val route = ExpressRouteScanner().scanFile(file).single()

        // router.get(...) is on the 3rd line, zero-based index 2.
        assertEquals(2, route.lineNumber)
        assertEquals("line-tracked", route.className)
    }
}
