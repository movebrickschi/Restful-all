package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * 项目启动时调度初次路由扫描。
 *
 * 关键策略：
 * - 等待 IDE 进入 smart 模式（索引就绪）再开始；避免在启动早期与平台索引争抢 ReadAction。
 * - 通过 [RouteService.scanProjectAsync] 将扫描托管到 pooled executor，立即返回；
 *   不阻塞其它 `postStartupActivity`、不拖慢启动指标。
 * - 异常隔离：扫描内部任何抛出都通过 `whenComplete` 捕获，不污染 startup 生命周期。
 */
class RouteScanStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("Scheduling initial route scan for: ${project.name}")
        val service = RouteService.getInstance(project)
        DumbService.getInstance(project).runWhenSmart {
            service.scanProjectAsync().whenComplete { _, error ->
                if (error != null) {
                    LOG.warn("Initial route scan failed for: ${project.name}", error)
                } else {
                    LOG.info("Initial route scan completed for: ${project.name}")
                }
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(RouteScanStartupActivity::class.java)
    }
}
