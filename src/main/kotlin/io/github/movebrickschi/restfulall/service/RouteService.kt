package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.movebrickschi.restfulall.model.RouteInfo
import io.github.movebrickschi.restfulall.scanner.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    private val scannersByExtension: Map<String, List<RouteScanner>> = buildMap {
        for (ext in supportedExtensions) {
            put(ext, scanners.filter { ext in it.supportedExtensions() })
        }
    }

    /**
     * 唯一权威路由状态：file path → routes 的不可变快照。
     * 写操作通过 [AtomicReference.set] / [AtomicReference.updateAndGet] 原子替换整张 map；
     * 读操作单次 `get()` 拿到当时的不可变视图，无需加锁。
     */
    private val routesRef = AtomicReference<Map<String, List<RouteInfo>>>(emptyMap())
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

                    val filesToRescan = affectedFiles.filter { !it.isDirectory && shouldScan(it) }

                    if (filesToRescan.isEmpty() && deletedPaths.isEmpty()) return

                    if (filesToRescan.isEmpty()) {
                        applyIncrementalUpdate(emptyMap(), deletedPaths)
                        return
                    }

                    ApplicationManager.getApplication().executeOnPooledThread {
                        val updates = HashMap<String, List<RouteInfo>>(filesToRescan.size)
                        for (file in filesToRescan) {
                            if (!file.isValid) continue
                            val newRoutes = ReadAction.compute<List<RouteInfo>, Throwable> {
                                scanSingleFile(file)
                            }
                            updates[file.path] = newRoutes
                        }
                        applyIncrementalUpdate(updates, deletedPaths)
                    }
                }
            }
        )
    }

    val isInitialScanDone: Boolean get() = initialScanDone.get()
    val isScanning: Boolean get() = scanning.get()

    /**
     * Schedules a full project scan on the IDE pooled executor and returns a future
     * that completes with the scanned routes. Callers can chain `.thenAccept`/`.whenComplete`
     * to drive UI updates without manually handling thread offloading.
     *
     * If a scan is already in progress, the future completes immediately with the
     * currently cached routes (mirroring [scanProject] semantics).
     */
    fun scanProjectAsync(): CompletableFuture<List<RouteInfo>> =
        CompletableFuture.supplyAsync({ scanProject() }, AppExecutorUtil.getAppExecutorService())

    fun scanProject(): List<RouteInfo> {
        if (!scanning.compareAndSet(false, true)) {
            LOG.info("Scan already in progress, skipping")
            return getCachedRoutes()
        }

        try {
            val filesToScan = collectFilesToScan()
            LOG.info("Scanning ${filesToScan.size} files...")

            val metrics = ScanMetrics()
            metrics.start()
            val newRoutesByFile = scanFilesParallel(filesToScan, metrics)
            metrics.stop()
            routesRef.set(newRoutesByFile)
            initialScanDone.set(true)

            val sorted = computeSortedRoutes(newRoutesByFile)
            LOG.info(metrics.summary(totalFiles = newRoutesByFile.size, totalRoutes = sorted.size))
            return sorted
        } finally {
            scanning.set(false)
        }
    }

    fun getCachedRoutes(): List<RouteInfo> = computeSortedRoutes(routesRef.get())

    fun findRouteAt(file: VirtualFile, line: Int): RouteInfo? {
        val routes = routesRef.get()[file.path] ?: return null
        return routes
            .filter { it.lineNumber <= line }
            .maxByOrNull { it.lineNumber }
    }

    fun findRouteAtExactLine(file: VirtualFile, line: Int): RouteInfo? {
        val routes = routesRef.get()[file.path] ?: return null
        return routes.firstOrNull { it.lineNumber == line }
    }

    fun updateFile(file: VirtualFile) {
        if (!shouldScan(file)) {
            applyIncrementalUpdate(emptyMap(), setOf(file.path))
            return
        }
        val routes = ReadAction.compute<List<RouteInfo>, Throwable> { scanSingleFile(file) }
        applyIncrementalUpdate(mapOf(file.path to routes), emptySet())
    }

    fun removeFile(path: String) {
        applyIncrementalUpdate(emptyMap(), setOf(path))
    }

    private fun applyIncrementalUpdate(
        upserts: Map<String, List<RouteInfo>>,
        deletes: Set<String>,
    ) {
        if (upserts.isEmpty() && deletes.isEmpty()) return
        routesRef.updateAndGet { current ->
            val next = HashMap(current)
            for (path in deletes) next.remove(path)
            for ((path, routes) in upserts) next[path] = routes
            next
        }
    }

    private fun collectFilesToScan(): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        ReadAction.run<Throwable> {
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { file ->
                if (!file.isDirectory && shouldScan(file)) {
                    files.add(file)
                }
                true
            }
        }

        if (files.isEmpty()) {
            LOG.info("ProjectFileIndex found 0 scannable files, falling back to VFS recursive scan...")
            ReadAction.run<Throwable> {
                val baseDir = project.basePath?.let {
                    LocalFileSystem.getInstance().findFileByPath(it)
                }
                if (baseDir != null) {
                    collectFilesRecursively(baseDir, files)
                } else {
                    LOG.warn("Cannot determine project base directory for VFS fallback")
                }
            }
        }
        return files
    }

    private fun scanFilesParallel(
        files: List<VirtualFile>,
        metrics: ScanMetrics? = null,
    ): Map<String, List<RouteInfo>> {
        if (files.isEmpty()) return emptyMap()
        if (files.size <= SINGLE_THREAD_THRESHOLD) {
            val result = HashMap<String, List<RouteInfo>>(files.size)
            for (file in files) {
                if (!file.isValid) {
                    metrics?.recordSkip()
                    continue
                }
                val routes = ReadAction.compute<List<RouteInfo>, Throwable> { scanSingleFile(file, metrics) }
                if (routes.isNotEmpty()) result[file.path] = routes
            }
            return result
        }

        val chunkSize = (files.size / PARALLELISM)
            .coerceAtLeast(MIN_CHUNK_SIZE)
            .coerceAtMost(MAX_CHUNK_SIZE)
        val executor = AppExecutorUtil.getAppExecutorService()
        val futures = files.chunked(chunkSize).map { chunk ->
            CompletableFuture.supplyAsync({
                val partial = HashMap<String, List<RouteInfo>>(chunk.size)
                for (file in chunk) {
                    if (!file.isValid) {
                        metrics?.recordSkip()
                        continue
                    }
                    val routes = ReadAction.compute<List<RouteInfo>, Throwable> { scanSingleFile(file, metrics) }
                    if (routes.isNotEmpty()) partial[file.path] = routes
                }
                partial
            }, executor)
        }
        val merged = HashMap<String, List<RouteInfo>>(files.size)
        for (future in futures) merged.putAll(future.join())
        return merged
    }

    private fun scanSingleFile(file: VirtualFile, metrics: ScanMetrics? = null): List<RouteInfo> {
        val ext = file.extension ?: return emptyList()
        val matching = scannersByExtension[ext] ?: return emptyList()
        val content = readFileContent(file) ?: return emptyList()
        val routes = mutableListOf<RouteInfo>()
        for (scanner in matching) {
            try {
                val scannerRoutes = scanner.scanFile(file, content)
                routes.addAll(scannerRoutes)
                metrics?.record(scanner.javaClass.simpleName, scannerRoutes.size)
            } catch (e: Exception) {
                LOG.warn("Failed to scan ${file.path} with ${scanner.javaClass.simpleName}", e)
            }
        }
        return routes
    }

    private fun readFileContent(file: VirtualFile): String? {
        return try {
            LoadTextUtil.loadText(file).toString()
        } catch (_: Throwable) {
            try {
                String(file.contentsToByteArray(), Charsets.UTF_8)
            } catch (e: Throwable) {
                LOG.debug("Failed to read content: ${file.path}", e)
                null
            }
        }
    }

    /**
     * 从 file-path 索引表派生出排序后的路由列表。
     * 每次调用都是纯计算，不依赖任何缓存字段；
     * routes 总量通常 < 1000，O(n log n) 排序在 ms 级。
     */
    private fun computeSortedRoutes(source: Map<String, List<RouteInfo>>): List<RouteInfo> =
        source.values.flatten()
            .distinctBy { "${it.method}:${it.fullPath}:${it.file.path}:${it.lineNumber}" }
            .sortedWith(compareBy({ it.fullPath }, { it.method.name }))

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

    override fun dispose() {
        LOG.debug("RouteService disposed for project: ${project.name}")
    }

    /**
     * 项目扫描的可观测性容器。
     *
     * - 并发安全：`scannerStats` 使用 [ConcurrentHashMap.compute] 原子合并，
     *   多 chunk 并行 scan 时 record 不需要外部加锁。
     * - 零开销路径：所有 metrics 调用在 [scanSingleFile] / [scanFilesParallel] 中传入
     *   nullable 引用，未启用时 `null?.record(...)` 直接短路、无热点 cost。
     */
    private class ScanMetrics {
        private val scannerStats = ConcurrentHashMap<String, ScannerStat>()
        private val skippedFiles = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        private var startNanos: Long = 0L

        @Volatile
        private var endNanos: Long = 0L

        fun start() {
            startNanos = System.nanoTime()
        }

        fun stop() {
            endNanos = System.nanoTime()
        }

        fun record(scannerName: String, routesFound: Int) {
            scannerStats.compute(scannerName) { _, old ->
                if (old == null) {
                    ScannerStat(filesScanned = 1, routesFound = routesFound)
                } else {
                    ScannerStat(
                        filesScanned = old.filesScanned + 1,
                        routesFound = old.routesFound + routesFound,
                    )
                }
            }
        }

        fun recordSkip() {
            skippedFiles.incrementAndGet()
        }

        fun summary(totalFiles: Int, totalRoutes: Int): String {
            val durationMs = ((endNanos - startNanos) / 1_000_000).coerceAtLeast(0)
            val skipped = skippedFiles.get()
            val skippedSuffix = if (skipped > 0) ", skipped $skipped invalid" else ""
            val builder = StringBuilder()
            builder.append("Route scan complete: $totalRoutes routes from $totalFiles files in ${durationMs}ms$skippedSuffix")
            val perScanner = scannerStats.entries.sortedBy { it.key }
            for ((name, stat) in perScanner) {
                builder.append("\n  - $name: scanned ${stat.filesScanned} files, found ${stat.routesFound} routes")
            }
            return builder.toString()
        }
    }

    private data class ScannerStat(val filesScanned: Int, val routesFound: Int)

    companion object {
        private val LOG = Logger.getInstance(RouteService::class.java)

        private const val MAX_FILE_SIZE = 512L * 1024

        private const val SINGLE_THREAD_THRESHOLD = 200
        private const val MIN_CHUNK_SIZE = 25
        private const val MAX_CHUNK_SIZE = 200
        private val PARALLELISM: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2)

        private val SKIP_DIRECTORIES = setOf(
            "node_modules", "dist", "build", ".git", ".gradle",
            ".idea", "target", "__pycache__", ".next", ".nuxt",
            "vendor", "venv", ".venv", "env",
        )

        fun getInstance(project: Project): RouteService =
            project.getService(RouteService::class.java)
    }
}
