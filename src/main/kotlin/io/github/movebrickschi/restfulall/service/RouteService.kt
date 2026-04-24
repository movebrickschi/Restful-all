package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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

                    // Handle deletions synchronously (no I/O, just map removals)
                    var changed = false
                    for (path in deletedPaths) {
                        if (routesByFile.remove(path) != null) changed = true
                    }

                    val filesToRescan = affectedFiles.filter { !it.isDirectory && shouldScan(it) }

                    if (filesToRescan.isEmpty()) {
                        if (changed) rebuildSortedCache()
                        return
                    }

                    // Defer file scanning to pooled thread to avoid blocking EDT
                    val deletionChanged = changed
                    ApplicationManager.getApplication().executeOnPooledThread {
                        var fileChanged = deletionChanged
                        for (file in filesToRescan) {
                            if (!file.isValid) continue
                            ReadAction.run<Throwable> {
                                val newRoutes = scanSingleFile(file)
                                val old = routesByFile.put(file.path, newRoutes)
                                if (old != newRoutes) fileChanged = true
                            }
                        }
                        if (fileChanged) rebuildSortedCache()
                    }
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

            // Phase 1: Collect files to scan (short read action, no file I/O)
            val filesToScan = mutableListOf<VirtualFile>()
            ReadAction.run<Throwable> {
                val fileIndex = ProjectFileIndex.getInstance(project)
                fileIndex.iterateContent { file ->
                    if (!file.isDirectory && shouldScan(file)) {
                        filesToScan.add(file)
                    }
                    true
                }
            }

            LOG.info("ProjectFileIndex: found ${filesToScan.size} files to scan")

            // Fallback if no files found via ProjectFileIndex
            if (filesToScan.isEmpty()) {
                LOG.info("ProjectFileIndex found 0 scannable files, falling back to VFS recursive scan...")
                ReadAction.run<Throwable> {
                    val baseDir = project.basePath?.let {
                        LocalFileSystem.getInstance().findFileByPath(it)
                    }
                    if (baseDir != null) {
                        collectFilesRecursively(baseDir, filesToScan)
                    } else {
                        LOG.warn("Cannot determine project base directory for VFS fallback")
                    }
                }
            }

            // Phase 2: Scan each file with a per-file short ReadAction
            for (file in filesToScan) {
                if (!file.isValid) continue
                ReadAction.run<Throwable> {
                    val routes = scanSingleFile(file)
                    if (routes.isNotEmpty()) {
                        newRoutesByFile[file.path] = routes
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

    fun findRouteAt(file: VirtualFile, line: Int): RouteInfo? {
        val routes = routesByFile[file.path] ?: return null
        return routes
            .filter { it.lineNumber <= line }
            .maxByOrNull { it.lineNumber }
    }

    fun findRouteAtExactLine(file: VirtualFile, line: Int): RouteInfo? {
        val routes = routesByFile[file.path] ?: return null
        return routes.firstOrNull { it.lineNumber == line }
    }

    fun updateFile(file: VirtualFile) {
        if (!shouldScan(file)) {
            if (routesByFile.remove(file.path) != null) rebuildSortedCache()
            return
        }
        ReadAction.run<Throwable> {
            val routes = scanSingleFile(file)
            routesByFile[file.path] = routes
        }
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

    private fun collectFilesRecursively(dir: VirtualFile, result: MutableList<VirtualFile>) {
        val children = dir.children ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name !in SKIP_DIRECTORIES) {
                    collectFilesRecursively(child, result)
                }
            } else if (shouldScan(child)) {
                result.add(child)
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
