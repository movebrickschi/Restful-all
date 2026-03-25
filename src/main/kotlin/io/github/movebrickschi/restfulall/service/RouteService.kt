package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.scanner.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@Service(Service.Level.PROJECT)
class RouteService(private val project: Project) : Disposable {

    private val scanners: List<RouteScanner> = listOf(
        NestJsRouteScanner(),
        SpringRouteScanner(),
        ExpressRouteScanner(),
        PythonRouteScanner(),
    )

    private val supportedExtensions: Set<String> =
        scanners.flatMap { it.supportedExtensions() }.toSet()

    private val routesByFile = ConcurrentHashMap<String, List<RouteInfo>>()
    private val lock = ReentrantReadWriteLock()

    @Volatile
    private var sortedCache: List<RouteInfo> = emptyList()
    private val initialScanDone = AtomicBoolean(false)
    private val scanning = AtomicBoolean(false)

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: MutableList<out VFileEvent>) {
                    if (!initialScanDone.get()) return
                    val affectedFiles = mutableSetOf<VirtualFile>()
                    val deletedPaths = mutableSetOf<String>()

                    for (event in events) {
                        when (event) {
                            is VFileContentChangeEvent -> event.file.let { affectedFiles.add(it) }
                            is VFileCreateEvent -> event.file?.let { affectedFiles.add(it) }
                            is VFileMoveEvent -> {
                                deletedPaths.add(event.oldPath)
                                event.file.let { affectedFiles.add(it) }
                            }
                            is VFileDeleteEvent -> deletedPaths.add(event.path)
                            is VFileCopyEvent -> event.file?.let { affectedFiles.add(it) }
                        }
                    }

                    var changed = false
                    for (path in deletedPaths) {
                        if (routesByFile.remove(path) != null) changed = true
                    }
                    for (file in affectedFiles) {
                        if (file.isDirectory || !shouldScan(file)) continue
                        val newRoutes = scanSingleFile(file)
                        val old = routesByFile.put(file.path, newRoutes)
                        if (old != newRoutes) changed = true
                    }
                    if (changed) rebuildSortedCache()
                }
            }
        )
    }

    val isInitialScanDone: Boolean get() = initialScanDone.get()
    val isScanning: Boolean get() = scanning.get()

    fun scanProject(): List<RouteInfo> {
        if (!scanning.compareAndSet(false, true)) {
            LOG.info("Scan already in progress, skipping")
            return getCachedRoutes()
        }

        try {
            val newRoutesByFile = ConcurrentHashMap<String, List<RouteInfo>>()

            ReadAction.run<Throwable> {
                var totalFiles = 0
                var scannedFiles = 0

                val fileIndex = ProjectFileIndex.getInstance(project)
                fileIndex.iterateContent { file ->
                    totalFiles++
                    if (!file.isDirectory && shouldScan(file)) {
                        scannedFiles++
                        val routes = scanSingleFile(file)
                        if (routes.isNotEmpty()) {
                            newRoutesByFile[file.path] = routes
                        }
                    }
                    true
                }

                LOG.info("ProjectFileIndex: iterated $totalFiles files, scanned $scannedFiles matching files")

                if (scannedFiles == 0) {
                    LOG.info("ProjectFileIndex found 0 scannable files, falling back to VFS recursive scan...")
                    val baseDir = project.basePath?.let {
                        LocalFileSystem.getInstance().findFileByPath(it)
                    }
                    if (baseDir != null) {
                        scanDirectoryRecursively(baseDir, newRoutesByFile)
                    } else {
                        LOG.warn("Cannot determine project base directory for VFS fallback")
                    }
                }
            }

            lock.write {
                routesByFile.clear()
                routesByFile.putAll(newRoutesByFile)
            }
            rebuildSortedCache()
            initialScanDone.set(true)

            LOG.info("Route scan complete: ${sortedCache.size} unique routes from ${routesByFile.size} files")
            return sortedCache
        } finally {
            scanning.set(false)
        }
    }

    fun getCachedRoutes(): List<RouteInfo> = lock.read { sortedCache }

    fun updateFile(file: VirtualFile) {
        if (!shouldScan(file)) {
            if (routesByFile.remove(file.path) != null) rebuildSortedCache()
            return
        }
        val routes = scanSingleFile(file)
        routesByFile[file.path] = routes
        rebuildSortedCache()
    }

    fun removeFile(path: String) {
        if (routesByFile.remove(path) != null) rebuildSortedCache()
    }

    private fun scanSingleFile(file: VirtualFile): List<RouteInfo> {
        val routes = mutableListOf<RouteInfo>()
        try {
            for (scanner in scanners) {
                if (file.extension in scanner.supportedExtensions()) {
                    routes.addAll(scanner.scanFile(file))
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to scan file: ${file.path}", e)
        }
        return routes
    }

    private fun rebuildSortedCache() {
        lock.write {
            sortedCache = routesByFile.values.flatten()
                .distinctBy { "${it.method}:${it.fullPath}:${it.file.path}:${it.lineNumber}" }
                .sortedWith(compareBy({ it.fullPath }, { it.method.name }))
        }
    }

    private fun scanDirectoryRecursively(dir: VirtualFile, result: ConcurrentHashMap<String, List<RouteInfo>>) {
        val children = dir.children ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name !in SKIP_DIRECTORIES) {
                    scanDirectoryRecursively(child, result)
                }
            } else if (shouldScan(child)) {
                val routes = scanSingleFile(child)
                if (routes.isNotEmpty()) {
                    result[child.path] = routes
                }
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

    override fun dispose() {}

    companion object {
        private val LOG = Logger.getInstance(RouteService::class.java)

        private const val MAX_FILE_SIZE = 512L * 1024

        private val SKIP_DIRECTORIES = setOf(
            "node_modules", "dist", "build", ".git", ".gradle",
            ".idea", "target", "__pycache__", ".next", ".nuxt",
            "vendor", "venv", ".venv", "env",
        )

        fun getInstance(project: Project): RouteService =
            project.getService(RouteService::class.java)
    }
}
