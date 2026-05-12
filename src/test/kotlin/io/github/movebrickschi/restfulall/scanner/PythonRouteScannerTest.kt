package io.github.movebrickschi.restfulall.scanner

import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonRouteScannerTest {

    @Test
    fun `supportedExtensions returns py`() {
        val exts = PythonRouteScanner().supportedExtensions()
        assertEquals(setOf("py"), exts)
    }

    @Test
    fun `returns empty when content has no route-like calls`() {
        val file = TestVirtualFile(
            "service.py",
            """
            def add(a, b):
                return a + b
            """.trimIndent(),
        )

        val routes = PythonRouteScanner().scanFile(file)

        assertTrue(routes.isEmpty())
    }

    @Test
    fun `extracts FastAPI decorator route and function name`() {
        val file = TestVirtualFile(
            "users.py",
            """
            from fastapi import FastAPI

            app = FastAPI()

            @app.get("/users/{user_id}")
            def get_user(user_id: int):
                return {"user_id": user_id}
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/users/{user_id}", route.fullPath)
        assertEquals("users", route.className)
        assertEquals("get_user", route.functionName)
        assertEquals(Framework.PYTHON, route.framework)
    }

    @Test
    fun `detects multiple FastAPI HTTP methods`() {
        val file = TestVirtualFile(
            "crud.py",
            """
            from fastapi import FastAPI

            app = FastAPI()

            @app.get("/items")
            def list_items():
                return []

            @app.post("/items")
            def create_item():
                return {}

            @app.put("/items/{id}")
            def update_item(id: int):
                return {}

            @app.delete("/items/{id}")
            def delete_item(id: int):
                return None

            @app.patch("/items/{id}")
            def patch_item(id: int):
                return {}
            """.trimIndent(),
        )

        val routes = PythonRouteScanner().scanFile(file)
        val methods = routes.map { it.method }.toSet()

        assertEquals(
            setOf(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.DELETE,
                HttpMethod.PATCH,
            ),
            methods,
        )
        assertEquals(5, routes.size)
    }

    @Test
    fun `extracts Flask route with default GET method`() {
        val file = TestVirtualFile(
            "index.py",
            """
            from flask import Flask

            app = Flask(__name__)

            @app.route("/hello")
            def hello():
                return "hi"
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/hello", route.fullPath)
        assertEquals("hello", route.functionName)
    }

    @Test
    fun `extracts Flask route methods array preferring POST over GET`() {
        val file = TestVirtualFile(
            "submit.py",
            """
            from flask import Flask

            app = Flask(__name__)

            @app.route("/submit", methods=["POST", "GET"])
            def submit():
                return "submitted"
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.POST, route.method)
        assertEquals("/submit", route.fullPath)
    }

    @Test
    fun `extracts Flask route with explicit DELETE method`() {
        val file = TestVirtualFile(
            "remove.py",
            """
            from flask import Flask
            app = Flask(__name__)

            @app.route("/items/<id>", methods=["DELETE"])
            def remove(id):
                return ""
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.DELETE, route.method)
        assertEquals("/items/<id>", route.fullPath)
    }

    @Test
    fun `extracts Flask route with explicit PUT method`() {
        val file = TestVirtualFile(
            "update.py",
            """
            from flask import Flask
            app = Flask(__name__)

            @app.route("/items/<id>", methods=["PUT"])
            def upd(id):
                return ""
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.PUT, route.method)
    }

    @Test
    fun `extracts Flask route with explicit PATCH method`() {
        val file = TestVirtualFile(
            "patch.py",
            """
            from flask import Flask
            app = Flask(__name__)

            @app.route("/items/<id>", methods=["PATCH"])
            def patch_it(id):
                return ""
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.PATCH, route.method)
    }

    @Test
    fun `ignores decorators inside python comments`() {
        val file = TestVirtualFile(
            "commented.py",
            """
            from fastapi import FastAPI
            app = FastAPI()

            # @app.get("/disabled")
            # def disabled():
            #     return {}

            @app.get("/active")
            def active():
                return {}
            """.trimIndent(),
        )

        val routes = PythonRouteScanner().scanFile(file)

        assertEquals(1, routes.size)
        assertEquals("/active", routes.single().fullPath)
    }

    @Test
    fun `falls back to unknown when no def follows decorator`() {
        val file = TestVirtualFile(
            "dangling.py",
            """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get("/orphan")
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        assertEquals("unknown", route.functionName)
        assertEquals("/orphan", route.fullPath)
    }

    @Test
    fun `tracks decorator lineNumber`() {
        val file = TestVirtualFile(
            "lines.py",
            """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get("/x")
            def x():
                return {}
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()

        // line 0: from fastapi
        // line 1: app = ...
        // line 2: (blank)
        // line 3: @app.get("/x")
        assertEquals(3, route.lineNumber)
        assertEquals("lines", route.className)
    }

    @Test
    fun `extracts route with single quoted path`() {
        val file = TestVirtualFile(
            "single.py",
            """
            from fastapi import FastAPI
            app = FastAPI()

            @app.get('/single-quoted')
            def s():
                return {}
            """.trimIndent(),
        )

        val route = PythonRouteScanner().scanFile(file).single()
        assertEquals("/single-quoted", route.fullPath)
    }
}
