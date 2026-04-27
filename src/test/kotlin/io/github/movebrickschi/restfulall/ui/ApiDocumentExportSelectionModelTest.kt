package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import io.github.movebrickschi.restfulall.export.ApiDocumentFormat
import io.github.movebrickschi.restfulall.export.ApiDocumentOptions
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.service.PluginSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

class ApiDocumentExportSelectionModelTest {

    @Test
    fun `defaults to all routes selected`() {
        val routes = listOf(
            route(HttpMethod.GET, "/api/users", "User list"),
            route(HttpMethod.POST, "/api/orders", "Create order"),
        )

        val model = ApiDocumentExportSelectionModel(routes)

        assertEquals(routes, model.selectedRoutes())
        assertEquals(2, model.selectedCount)
        assertEquals(2, model.totalCount)
    }

    @Test
    fun `select none and invert visible update only selected routes`() {
        val routes = listOf(
            route(HttpMethod.GET, "/api/users", "User list"),
            route(HttpMethod.POST, "/api/orders", "Create order"),
        )
        val model = ApiDocumentExportSelectionModel(routes)

        model.selectNone()
        assertTrue(model.selectedRoutes().isEmpty())

        model.invertVisible("")
        assertEquals(routes, model.selectedRoutes())
    }

    @Test
    fun `select visible keeps existing selection while adding filtered matches`() {
        val users = route(HttpMethod.GET, "/api/users", "User list")
        val orders = route(HttpMethod.POST, "/api/orders", "Create order")
        val model = ApiDocumentExportSelectionModel(listOf(users, orders))

        model.selectNone()
        model.selectVisible("users")

        assertEquals(listOf(users), model.selectedRoutes())
        assertTrue(model.isSelected(users))
        assertFalse(model.isSelected(orders))
    }

    @Test
    fun `export options are persisted into settings state`() {
        val state = PluginSettingsState.State()
        val options = ApiDocumentOptions(
            title = "Project API",
            version = "2.0.0",
            description = "Selected routes only",
        )

        ApiDocumentExportSelectionModel.persistOptions(
            state,
            ApiDocumentFormat.MARKDOWN,
            options,
        )

        assertEquals(ApiDocumentFormat.MARKDOWN.name, state.lastExportFormat)
        assertEquals("Project API", state.lastExportTitle)
        assertEquals("2.0.0", state.lastExportVersion)
        assertEquals("Selected routes only", state.lastExportDescription)
    }

    private fun route(method: HttpMethod, path: String, name: String): RouteInfo =
        RouteInfo(
            method = method,
            fullPath = path,
            className = "${name.filter { it.isLetter() }}Controller",
            functionName = name.replace(" ", "").replaceFirstChar { it.lowercase() },
            file = TestVirtualFile("${name.filter { it.isLetter() }}Controller.kt"),
            lineNumber = 11,
            framework = Framework.SPRING,
            packageName = "com.example.api",
            routeGroupName = "Example",
            routeName = name,
        )

    private class TestVirtualFile(private val fileName: String) : VirtualFile() {
        private val bytes = ByteArray(0)

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
