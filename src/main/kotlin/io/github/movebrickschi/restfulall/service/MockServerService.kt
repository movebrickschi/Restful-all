package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpServer
import io.github.movebrickschi.restfulall.model.CollectionItem
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.3.3 P1-6 - 内置 Mock Server（Team）。
 *
 * - 用 [com.sun.net.httpserver.HttpServer]（IDEA bundled JDK 自带）起本地 HTTP 服务
 * - 路由匹配规则：把请求 path 与 [CollectionItem.spec.url] 末段（path-only）精确比较；
 *   未匹配时尝试 prefix（例如 spec 是 `/api/v1/users` 请求是 `/api/v1/users/42` 命中）
 * - 响应：取 item.spec.bodyContent 作为 body；content-type 根据 spec.bodyType 推断
 * - 单 IDE 同时只能运行一个实例（避免端口冲突）
 *
 * Pro / Team gate 由 UI 层在调用前判定（[ProFeatureGate.MOCK_SERVER]）。
 */
@Service(Service.Level.PROJECT)
class MockServerService(private val project: Project) : Disposable {

    companion object {
        const val DEFAULT_PORT: Int = 4523
        private val LOG = Logger.getInstance(MockServerService::class.java)

        fun getInstance(project: Project): MockServerService =
            project.getService(MockServerService::class.java)
    }

    private val current = AtomicReference<RunningServer?>()

    data class RunningServer(
        val server: HttpServer,
        val port: Int,
        val itemsSnapshot: List<CollectionItem>,
    )

    fun isRunning(): Boolean = current.get() != null

    fun runningPort(): Int? = current.get()?.port

    /**
     * 启动一个 mock server。重复调用会先 stop 旧实例再启动新的。
     *
     * @param port  本地端口（建议 1024-65535）
     * @param items 要 mock 的接口列表（典型来源：用户选中的 [io.github.movebrickschi.restfulall.model.CollectionEntry.items]）
     */
    fun start(port: Int = DEFAULT_PORT, items: List<CollectionItem>): RunningServer {
        stop()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.executor = Executors.newFixedThreadPool(8) { r ->
            Thread(r, "RestfulAll-Mock").apply { isDaemon = true }
        }
        server.createContext("/") { exchange ->
            try {
                val reqPath = exchange.requestURI.rawPath ?: "/"
                val match = matchItem(items, reqPath)
                if (match == null) {
                    exchange.sendResponseHeaders(404, 0)
                    exchange.responseBody.use { it.write("""{"mock":"not-found","path":"$reqPath"}""".toByteArray()) }
                    return@createContext
                }
                val body = match.spec.bodyContent.ifBlank { "{}" }
                val contentType = when (match.spec.bodyType) {
                    "json" -> "application/json; charset=utf-8"
                    "xml" -> "application/xml; charset=utf-8"
                    "raw" -> "text/plain; charset=utf-8"
                    else -> "application/json; charset=utf-8"
                }
                exchange.responseHeaders.add("Content-Type", contentType)
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (e: Exception) {
                LOG.warn("MockServer handler failed", e)
                try {
                    exchange.sendResponseHeaders(500, 0)
                    exchange.responseBody.use { it.write("internal error: ${e.message}".toByteArray()) }
                } catch (_: Exception) { /* swallow */ }
            }
        }
        server.start()
        val running = RunningServer(server, port, items.toList())
        current.set(running)
        LOG.info("MockServer started on :$port with ${items.size} items")
        return running
    }

    fun stop() {
        val rs = current.getAndSet(null) ?: return
        try {
            rs.server.stop(1)
            LOG.info("MockServer stopped (was on :${rs.port})")
        } catch (e: Exception) {
            LOG.warn("MockServer stop failed", e)
        }
    }

    override fun dispose() {
        stop()
    }

    /**
     * 路径匹配优先级：精确 = > prefix。
     * 仅取 [CollectionItem.spec.url] 中的 path 部分（去除可能包含的 host）。
     */
    private fun matchItem(items: List<CollectionItem>, reqPath: String): CollectionItem? {
        val sameMethod = items.filter { !it.disabled }
        val pathOf = { url: String ->
            try {
                val u = java.net.URI(url)
                u.rawPath.ifBlank { url }
            } catch (_: Exception) {
                url
            }
        }
        val exact = sameMethod.firstOrNull { pathOf(it.spec.url) == reqPath }
        if (exact != null) return exact
        return sameMethod.firstOrNull {
            val p = pathOf(it.spec.url)
            p.isNotEmpty() && reqPath.startsWith(p)
        }
    }
}
