package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * v1.3.3 P2-8 - 接口压测（Pro）。
 *
 * - 输入：`LoadTestConfig{ url, method, headers, body, concurrency, durationSec, rpsLimit }`
 * - 单次运行最多 [MAX_CONCURRENCY] × [MAX_DURATION_SEC] 秒（防本地 IDE 压崩）
 * - 内置 RPS 限流：使用简单 token-bucket（粒度 100ms 桶）
 * - 实时回调：每 500ms 触发一次 progress callback（cur QPS / 已发请求数 / 已成功 / 错误率）
 * - 完成后返回 [LoadTestReport]，含 latency 直方图（P50/P90/P95/P99）
 *
 * ## 实现选型说明
 *
 * 直接用 [java.net.http.HttpClient]（IDEA bundled JDK 21+ 自带），不引入 wrk / hey / Vert.x 等
 * 重型依赖。`HttpClient` 是 lazy 连接池，单实例可支撑 ~500 并发；> 500 并发 IDE 会被压崩。
 */
@Service(Service.Level.PROJECT)
class LoadTestService(@Suppress("unused") private val project: Project) {

    companion object {
        const val MAX_CONCURRENCY: Int = 500
        const val MAX_DURATION_SEC: Long = 300

        private val LOG = Logger.getInstance(LoadTestService::class.java)

        fun getInstance(project: Project): LoadTestService =
            project.getService(LoadTestService::class.java)
    }

    data class LoadTestConfig(
        val url: String,
        val method: String = "GET",
        val headers: List<Pair<String, String>> = emptyList(),
        val body: String = "",
        val concurrency: Int = 10,
        val durationSec: Long = 30,
        val rpsLimit: Int = 0, // 0 = no cap
        val timeoutMs: Long = 5000,
    )

    data class LoadTestReport(
        val totalRequests: Long,
        val successCount: Long,
        val errorCount: Long,
        val elapsedMs: Long,
        val averageLatencyMs: Long,
        val p50LatencyMs: Long,
        val p90LatencyMs: Long,
        val p95LatencyMs: Long,
        val p99LatencyMs: Long,
        val statusDistribution: Map<Int, Long>,
        val errorMessages: List<String>,
    ) {
        val qps: Double get() = if (elapsedMs == 0L) 0.0 else totalRequests * 1000.0 / elapsedMs
        val errorRate: Double get() = if (totalRequests == 0L) 0.0 else errorCount.toDouble() / totalRequests
    }

    data class Progress(
        val elapsedSec: Long,
        val totalRequests: Long,
        val successCount: Long,
        val errorCount: Long,
        val currentQps: Double,
    )

    /**
     * 同步阻塞运行压测。**调用方必须在 pooled thread 上运行**（IDE EDT 会卡死）。
     *
     * @param cancelToken 外部传入的取消信号；UI 上的 "停止" 按钮设置为 true 可立即终止
     * @param onProgress 每 ~500ms 回调一次（默认实现 = no-op）；回调发生在压测线程，不要更新 Swing
     */
    fun run(
        config: LoadTestConfig,
        cancelToken: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Progress) -> Unit = {},
    ): LoadTestReport {
        require(config.concurrency in 1..MAX_CONCURRENCY) { "concurrency out of [1, $MAX_CONCURRENCY]" }
        require(config.durationSec in 1..MAX_DURATION_SEC) { "duration out of [1, $MAX_DURATION_SEC]s" }

        val http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        val latencies = ConcurrentLinkedQueue<Long>()
        val statusCounts = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val totalRequests = AtomicLong(0)
        val successCount = AtomicLong(0)
        val errorCount = AtomicLong(0)
        val errorSamples = ConcurrentLinkedQueue<String>()
        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + Duration.ofSeconds(config.durationSec).toNanos()
        val rpsTokens = if (config.rpsLimit > 0) AtomicLong(config.rpsLimit / 10L) else AtomicLong(Long.MAX_VALUE)

        val executor = Executors.newFixedThreadPool(config.concurrency) { r ->
            Thread(r, "RestfulAll-LoadTest").apply { isDaemon = true }
        }

        val progressThread = Thread({
            var lastReportedTotal = 0L
            while (!cancelToken.get() && System.nanoTime() < deadlineNanos) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                val nowTotal = totalRequests.get()
                val curQps = (nowTotal - lastReportedTotal) * 2.0
                lastReportedTotal = nowTotal
                onProgress(Progress(
                    elapsedSec = elapsedMs / 1000,
                    totalRequests = nowTotal,
                    successCount = successCount.get(),
                    errorCount = errorCount.get(),
                    currentQps = curQps,
                ))
            }
        }, "RestfulAll-LoadTest-Progress").apply { isDaemon = true }
        progressThread.start()

        val refillThread = if (config.rpsLimit > 0) {
            Thread({
                val perBucket = config.rpsLimit / 10
                while (!cancelToken.get() && System.nanoTime() < deadlineNanos) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    rpsTokens.set(perBucket.toLong())
                }
            }, "RestfulAll-LoadTest-Refill").apply { isDaemon = true }
        } else null
        refillThread?.start()

        repeat(config.concurrency) {
            executor.submit {
                while (!cancelToken.get() && System.nanoTime() < deadlineNanos) {
                    if (config.rpsLimit > 0) {
                        while (rpsTokens.get() <= 0 && !cancelToken.get() && System.nanoTime() < deadlineNanos) {
                            try { Thread.sleep(2) } catch (_: InterruptedException) { return@submit }
                        }
                        if (cancelToken.get() || System.nanoTime() >= deadlineNanos) return@submit
                        rpsTokens.decrementAndGet()
                    }
                    val req = try {
                        buildRequest(config)
                    } catch (e: Exception) {
                        errorCount.incrementAndGet()
                        totalRequests.incrementAndGet()
                        errorSamples.offer(e.message ?: "build failed")
                        continue
                    }
                    val started = System.nanoTime()
                    try {
                        val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        latencies.offer(elapsed)
                        totalRequests.incrementAndGet()
                        statusCounts.merge(resp.statusCode(), 1L, Long::plus)
                        if (resp.statusCode() in 200..399) successCount.incrementAndGet() else errorCount.incrementAndGet()
                    } catch (e: Exception) {
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        latencies.offer(elapsed)
                        totalRequests.incrementAndGet()
                        errorCount.incrementAndGet()
                        val msg = e.message ?: e.javaClass.simpleName
                        if (errorSamples.size < 10) errorSamples.offer(msg)
                    }
                }
            }
        }

        executor.shutdown()
        try {
            val ok = executor.awaitTermination(config.durationSec + 30, TimeUnit.SECONDS)
            if (!ok) LOG.warn("LoadTest: workers did not stop in time, forcing shutdownNow")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        executor.shutdownNow()
        progressThread.interrupt()
        refillThread?.interrupt()

        val sorted = latencies.toLongArray().sortedArray()
        val total = totalRequests.get()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val avg = if (sorted.isEmpty()) 0L else sorted.average().toLong()
        return LoadTestReport(
            totalRequests = total,
            successCount = successCount.get(),
            errorCount = errorCount.get(),
            elapsedMs = elapsedMs,
            averageLatencyMs = avg,
            p50LatencyMs = percentile(sorted, 50),
            p90LatencyMs = percentile(sorted, 90),
            p95LatencyMs = percentile(sorted, 95),
            p99LatencyMs = percentile(sorted, 99),
            statusDistribution = statusCounts.toMap(),
            errorMessages = errorSamples.distinct().take(10),
        )
    }

    private fun buildRequest(config: LoadTestConfig): HttpRequest {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(config.url))
            .timeout(Duration.ofMillis(config.timeoutMs))
        for ((k, v) in config.headers) builder.header(k, v)
        val method = config.method.uppercase()
        val publisher = if (config.body.isNotEmpty())
            HttpRequest.BodyPublishers.ofString(config.body, Charsets.UTF_8)
        else HttpRequest.BodyPublishers.noBody()
        return when (method) {
            "GET" -> builder.GET()
            "DELETE" -> builder.DELETE()
            "POST", "PUT", "PATCH" -> builder.method(method, publisher)
            else -> builder.method(method, publisher)
        }.build()
    }

    private fun percentile(sorted: LongArray, pct: Int): Long {
        if (sorted.isEmpty()) return 0
        val idx = ((sorted.size - 1) * pct / 100).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
