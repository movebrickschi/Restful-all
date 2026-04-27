package io.github.movebrickschi.restfulall.ui

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightPlatformTestCase
import io.github.movebrickschi.restfulall.model.Framework
import io.github.movebrickschi.restfulall.model.HttpMethod
import io.github.movebrickschi.restfulall.model.RouteInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode

class RouteListPanelRendererTest : LightPlatformTestCase() {

    fun testLeafRendererDoesNotReenterBoundsCalculation() {
        runOnEdtAndThrow {
            val panel = RouteListPanel(project, onDebugRoute = {}, loadInitialRoutes = false)
            updateRoutes(panel, listOf(route()))

            val tree = routeTree(panel)
            val leafRow = firstLeafRow(tree)

            tree.setSize(900, 300)
            tree.doLayout()

            val bounds = tree.getRowBounds(leafRow)

            assertNotNull(bounds)
            assertTrue(bounds.width > 0)
        }
    }

    fun testLeafRowHasFixedShallowIndent() {
        runOnEdtAndThrow {
            val panel = RouteListPanel(project, onDebugRoute = {}, loadInitialRoutes = false)
            updateRoutes(panel, listOf(route()))

            val tree = routeTree(panel)
            val leafRow = firstLeafRow(tree)

            tree.setSize(900, 300)
            tree.doLayout()

            val leafBounds = tree.getRowBounds(leafRow)

            assertNotNull(leafBounds)
            assertTrue("Leaf row should have positive width", leafBounds.width > 0)
            val expectedX = com.intellij.util.ui.JBUI.scale(leafLeftIndent())
            assertTrue(
                "Leaf row x (${leafBounds.x}) should equal LEAF_LEFT_INDENT scaled ($expectedX)",
                leafBounds.x == expectedX,
            )
        }
    }

    private fun leafLeftIndent(): Int {
        val companionField = RouteListPanel::class.java.getDeclaredField("Companion")
        companionField.isAccessible = true
        val companion = companionField.get(null)
        val field = companion.javaClass.getDeclaredField("LEAF_LEFT_INDENT")
        field.isAccessible = true
        return field.get(companion) as Int
    }

    private fun updateRoutes(panel: RouteListPanel, routes: List<RouteInfo>) {
        val method = RouteListPanel::class.java.getDeclaredMethod("updateRoutes", List::class.java)
        method.isAccessible = true
        method.invoke(panel, routes)
    }

    private fun routeTree(panel: RouteListPanel): JTree {
        val field = RouteListPanel::class.java.getDeclaredField("routeTree")
        field.isAccessible = true
        return field.get(panel) as JTree
    }

    private fun firstLeafRow(tree: JTree): Int {
        for (row in 0 until tree.rowCount) {
            val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
            if (node?.userObject is RouteTreeItem.Leaf) {
                return row
            }
        }
        error("Expected at least one leaf route row")
    }

    private fun runOnEdtAndThrow(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
            return
        }

        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private fun route(): RouteInfo =
        RouteInfo(
            method = HttpMethod.GET,
            fullPath = "/api/users",
            className = "UserController",
            functionName = "listUsers",
            file = TestVirtualFile("UserController.kt"),
            lineNumber = 11,
            framework = Framework.SPRING,
            packageName = "com.example.api",
            routeGroupName = "User API",
            routeName = "User list",
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
