package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * 共享的轻量 VirtualFile 实现，用于 Scanner 单元测试。
 * 仅支持 contentsToByteArray / inputStream，不接入真实 VFS。
 */
internal class TestVirtualFile(
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
    override fun getOutputStream(
        requestor: Any?,
        newModificationStamp: Long,
        newTimeStamp: Long,
    ): OutputStream = throw UnsupportedOperationException()

    override fun contentsToByteArray(): ByteArray = bytes
    override fun getTimeStamp(): Long = 0L
    override fun getLength(): Long = bytes.size.toLong()
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
}

internal object TestVirtualFileSystem : VirtualFileSystem() {
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
