package io.github.movebrickschi.restfulall.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocCommentExtractorTest {

    @Test
    fun `extracts multi-line javadoc above annotation`() {
        val src = """
            /**
             * 查询用户列表
             * 支持按邮箱模糊搜索。
             * @param keyword 关键词
             * @return 用户分页
             */
            @GetMapping("/users")
            fun list() {}
        """.trimIndent().lines()

        val result = DocCommentExtractor.extractJavaDocAbove(src, annotationLine = 6)

        assertEquals("查询用户列表\n支持按邮箱模糊搜索。", result)
    }

    @Test
    fun `extracts single line javadoc`() {
        val src = """
            /** 查询单条 */
            @GetMapping("/one")
            fun one() {}
        """.trimIndent().lines()

        val result = DocCommentExtractor.extractJavaDocAbove(src, annotationLine = 1)

        assertEquals("查询单条", result)
    }

    @Test
    fun `skips intermediate annotations when scanning upward`() {
        val src = """
            /**
             * 删除用户
             */
            @Operation(summary = "delete user")
            @PreAuthorize("hasRole('ADMIN')")
            @DeleteMapping("/{id}")
            fun delete() {}
        """.trimIndent().lines()

        val result = DocCommentExtractor.extractJavaDocAbove(src, annotationLine = 5)

        assertEquals("删除用户", result)
    }

    @Test
    fun `returns null when no javadoc present`() {
        val src = """
            @GetMapping("/x")
            fun x() {}
        """.trimIndent().lines()

        assertNull(DocCommentExtractor.extractJavaDocAbove(src, annotationLine = 0))
    }

    @Test
    fun `stops when hitting non-annotation code`() {
        val src = """
            fun previous() {}
            @GetMapping("/y")
            fun y() {}
        """.trimIndent().lines()

        assertNull(DocCommentExtractor.extractJavaDocAbove(src, annotationLine = 1))
    }

    @Test
    fun `drops javadoc tags but keeps deprecated marker via description body`() {
        val src = """
            /**
             * 旧接口，请改用 v2。
             * @deprecated since 2.0
             * @see UserControllerV2
             */
            @GetMapping("/legacy")
            fun legacy() {}
        """.trimIndent().lines()

        val result = DocCommentExtractor.extractJavaDocAbove(src, annotationLine = 5)

        assertEquals(true, result?.contains("旧接口"))
        assertEquals(false, result?.contains("@see"))
    }

    @Test
    fun `python triple double quote docstring extracted`() {
        val src = """
            @app.get("/users")
            def list_users():
                ${'"'}${'"'}${'"'}查询用户列表。
                支持按邮箱搜索。${'"'}${'"'}${'"'}
                return []
        """.trimIndent().lines()

        val result = DocCommentExtractor.extractPythonDocstring(src, annotationLine = 0)

        assertTrue("expected docstring captured, got=$result", result?.contains("查询用户列表") == true)
    }

    @Test
    fun `python single line docstring`() {
        val src = """
            @app.get("/ping")
            def ping():
                ${'"'}${'"'}${'"'}心跳检查${'"'}${'"'}${'"'}
                return "pong"
        """.trimIndent().lines()

        val result = DocCommentExtractor.extractPythonDocstring(src, annotationLine = 0)

        assertEquals("心跳检查", result)
    }

    @Test
    fun `python without docstring returns null`() {
        val src = """
            @app.get("/x")
            def x():
                return None
        """.trimIndent().lines()

        assertNull(DocCommentExtractor.extractPythonDocstring(src, annotationLine = 0))
    }
}
