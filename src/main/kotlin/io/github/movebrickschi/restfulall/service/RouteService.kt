package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.scanner.*

@Service(Service.Level.PROJECT)
class RouteService(private val project: Project) {

    private val scanners: List<RouteScanner> = listOf(
        NestJsRouteScanner(),
        SpringRouteScanner(),
        ExpressRouteScanner(),
        PythonRouteScanner(),
    )

    private val supportedExtensions: Set<String> =
        scanners.flatMap { it.supportedExtensions() }.toSet()

    @Volatile
    private var cachedRoutes: List<RouteInfo> = emptyList()

    fun scanProject(): List<RouteInfo> {
        val routes = mutableListOf<RouteInfo>()

        ReadAction.run<Throwable> {
            var totalFiles = 0
            var scannedFiles = 0

            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { file ->
                totalFiles++
                if (!file.isDirectory && shouldScan(file)) {
                    scannedFiles++
                    scanFileWithAllScanners(file, routes)
                }
                true
            }

            LOG.info("ProjectFileIndex: iterated $totalFiles files, scanned $scannedFiles matching files, found ${routes.size} routes")

            if (scannedFiles == 0) {
                LOG.info("ProjectFileIndex found 0 scannable files, falling back to VFS recursive scan...")
                val baseDir = project.basePath?.let {
                    LocalFileSystem.getInstance().findFileByPath(it)
                }
                if (baseDir != null) {
                    scanDirectoryRecursively(baseDir, routes)
                    LOG.info("VFS fallback scan found ${routes.size} routes")
                } else {
                    LOG.warn("Cannot determine project base directory for VFS fallback")
                }
            }
        }

        val deduplicated = routes
            .distinctBy { "${it.method}:${it.fullPath}:${it.file.path}:${it.lineNumber}" }
            .sortedWith(compareBy({ it.fullPath }, { it.method.name }))

        cachedRoutes = deduplicated
        LOG.info("Route scan complete: ${deduplicated.size} unique routes (from ${routes.size} total)")
        return deduplicated
    }

    fun getCachedRoutes(): List<RouteInfo> = cachedRoutes

    private fun scanFileWithAllScanners(file: VirtualFile, routes: MutableList<RouteInfo>) {
        try {
            for (scanner in scanners) {
                if (file.extension in scanner.supportedExtensions()) {
                    routes.addAll(scanner.scanFile(file))
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to scan file: ${file.path}", e)
        }
    }

    private fun scanDirectoryRecursively(dir: VirtualFile, routes: MutableList<RouteInfo>) {
        val children = dir.children ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name !in SKIP_DIRECTORIES) {
                    scanDirectoryRecursively(child, routes)
                }
            } else if (child.extension in supportedExtensions) {
                scanFileWithAllScanners(child, routes)
            }
        }
    }

    private fun shouldScan(file: VirtualFile): Boolean {
        if (file.extension !in supportedExtensions) return false
        if (file.length > MAX_FILE_SIZE) return false
        var parent = file.parent
        while (parent != null) {
            if (parent.name in SKIP_DIRECTORIES) return false
            parent = parent.parent
        }
        return true
    }

    companion object {
        private val LOG = Logger.getInstance(RouteService::class.java)

        private const val MAX_FILE_SIZE = 512L * 1024 // 512KB

        private val SKIP_DIRECTORIES = setOf(
            "node_modules", "dist", "build", ".git", ".gradle",
            ".idea", "target", "__pycache__", ".next", ".nuxt",
            "vendor", "venv", ".venv", "env",
        )

        fun getInstance(project: Project): RouteService =
            project.getService(RouteService::class.java)
    }
}
