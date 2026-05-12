package io.github.movebrickschi.restfulall.scanner

import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.RouteInfo

interface RouteScanner {

    fun supportedExtensions(): Set<String>

    /**
     * 兼容旧调用方（含单元测试）：自行读取文件内容后委托给 [scanFile] 重载。
     * RouteService 等高频调用方应优先使用带 content 参数的重载，
     * 在外部一次性读完文件后由多个 scanner 共享，避免对同一文件重复 I/O 与 UTF-8 解码。
     */
    fun scanFile(file: VirtualFile): List<RouteInfo> {
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Throwable) {
            return emptyList()
        }
        return scanFile(file, content)
    }

    fun scanFile(file: VirtualFile, content: String): List<RouteInfo>
}
