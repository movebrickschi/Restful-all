package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.VirtualFileListener
import io.github.movebrickschi.restfulall.model.HttpMethod
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class SpringRouteScannerTest {

    @Test
    fun `uses package and tag name as route metadata`() {
        val file = TestVirtualFile(
            "BossModelController.java",
            """
            package com.huizhi.inherit.controller;

            import io.swagger.v3.oas.annotations.tags.Tag;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RequestMapping;
            import org.springframework.web.bind.annotation.RestController;

            @Tag(name = "Boss 模型管理")
            @RestController
            @RequestMapping("/api/boss/llm-model")
            class BossModelController {
                @GetMapping("/list")
                public Object list() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val route = SpringRouteScanner().scanFile(file).single()

        assertEquals(HttpMethod.GET, route.method)
        assertEquals("/api/boss/llm-model/list", route.displayPath)
        assertEquals("BossModelController", route.className)
        assertEquals("com.huizhi.inherit.controller", route.packageName)
        assertEquals("Boss 模型管理", route.routeGroupName)
    }

    @Test
    fun `falls back to controller class name when tag is missing`() {
        val file = TestVirtualFile(
            "UserController.kt",
            """
            package com.huizhi.inherit.controller

            import org.springframework.web.bind.annotation.GetMapping
            import org.springframework.web.bind.annotation.RestController

            @RestController
            class UserController {
                @GetMapping("/api/users")
                fun users(): Any {
                    return Unit
                }
            }
            """.trimIndent(),
        )

        val route = SpringRouteScanner().scanFile(file).single()

        assertEquals("com.huizhi.inherit.controller", route.packageName)
        assertEquals("UserController", route.routeGroupName)
    }

    @Test
    fun `uses swagger api tags as route group metadata`() {
        val file = TestVirtualFile(
            "BossChannelController.java",
            """
            package com.huizhi.inherit.controller;

            import io.swagger.annotations.Api;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @Api(tags = "Boss 渠道管理")
            @RestController
            class BossChannelController {
                @GetMapping("/api/boss/channels")
                public Object channels() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val route = SpringRouteScanner().scanFile(file).single()

        assertEquals("Boss 渠道管理", route.routeGroupName)
    }

    @Test
    fun `uses operation summary as route name`() {
        val file = TestVirtualFile(
            "BossModelController.java",
            """
            package com.huizhi.inherit.controller;

            import io.swagger.v3.oas.annotations.Operation;
            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class BossModelController {
                @Operation(summary = "分页查询模型列表")
                @GetMapping("/api/boss/llm-model/list")
                public Object list() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val route = SpringRouteScanner().scanFile(file).single()

        assertEquals("分页查询模型列表", route.routeName)
    }

    @Test
    fun `uses api operation value as route name`() {
        val file = TestVirtualFile(
            "BossApiKeyController.java",
            """
            package com.huizhi.inherit.controller;

            import io.swagger.annotations.ApiOperation;
            import org.springframework.web.bind.annotation.DeleteMapping;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            class BossApiKeyController {
                @ApiOperation(value = "删除 API Key")
                @DeleteMapping("/api/boss/api-key/{keyId}")
                public Object remove() {
                    return null;
                }
            }
            """.trimIndent(),
        )

        val route = SpringRouteScanner().scanFile(file).single()

        assertEquals("删除 API Key", route.routeName)
    }

    private class TestVirtualFile(
        private val fileName: String,
        private val content: String,
    ) : VirtualFile() {
        private val bytes = content.toByteArray(Charsets.UTF_8)

        override fun getName(): String = fileName
        override fun getFileSystem(): VirtualFileSystem = TestVirtualFileSystem
        override fun getPath(): String = "/test/$fileName"
        override fun isWritable(): Boolean = false
        override fun isDirectory(): Boolean = false
        override fun isValid(): Boolean = true
        override fun getParent(): VirtualFile? = null
        override fun getChildren(): Array<VirtualFile> = EMPTY_ARRAY
        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
            throw UnsupportedOperationException()
        override fun contentsToByteArray(): ByteArray = bytes
        override fun getTimeStamp(): Long = 0L
        override fun getLength(): Long = bytes.size.toLong()
        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
            postRunnable?.run()
        }
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private object TestVirtualFileSystem : VirtualFileSystem() {
        override fun getProtocol(): String = "test"
        override fun findFileByPath(path: String): VirtualFile? = null
        override fun refresh(asynchronous: Boolean) = Unit
        override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
        override fun addVirtualFileListener(listener: VirtualFileListener) = Unit
        override fun removeVirtualFileListener(listener: VirtualFileListener) = Unit
        override fun deleteFile(requestor: Any?, vFile: VirtualFile) = throw UnsupportedOperationException()
        override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) =
            throw UnsupportedOperationException()
        override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) =
            throw UnsupportedOperationException()
        override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile =
            throw UnsupportedOperationException()
        override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile =
            throw UnsupportedOperationException()
        override fun copyFile(
            requestor: Any?,
            virtualFile: VirtualFile,
            newParent: VirtualFile,
            copyName: String,
        ): VirtualFile = throw UnsupportedOperationException()
        override fun isReadOnly(): Boolean = true
    }
}
