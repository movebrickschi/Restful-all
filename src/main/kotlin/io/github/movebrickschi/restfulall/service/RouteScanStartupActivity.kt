package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil

/**
 * 项目启动时调度初次路由扫描。
 *
 * 关键策略：
 * - 等待 IDE 进入 smart 模式（索引就绪）再开始；避免在启动早期与平台索引争抢 ReadAction。
 * - 通过 [RouteService.scanProjectAsync] 将扫描托管到 pooled executor，立即返回；
 *   不阻塞其它 `postStartupActivity`、不拖慢启动指标。
 * - 异常隔离：扫描内部任何抛出都通过 `whenCompleteAsync` 捕获，不污染 startup 生命周期。
 * - 完成回调显式指定 executor，避免任何调用方在 chain 后接 UI 操作时被默认调度到非预期线程。
 */
class RouteScanStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("Scheduling initial route scan for: ${project.name}")
        val service = RouteService.getInstance(project)
        DumbService.getInstance(project).runWhenSmart {
            val t0 = System.nanoTime()
            service.scanProjectAsync().whenCompleteAsync({ _, error ->
                val durationMs = (System.nanoTime() - t0) / 1_000_000
                if (error != null) {
                    LOG.warn("Initial route scan failed for: ${project.name} after ${durationMs}ms", error)
                } else {
                    LOG.info("Initial route scan completed for: ${project.name} in ${durationMs}ms")
                }
            }, AppExecutorUtil.getAppExecutorService())
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RouteScanStartupActivity::class.java)
    }
}
