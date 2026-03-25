package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class RouteScanStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        LOG.info("Starting initial route scan for project: ${project.name}")
        val service = RouteService.getInstance(project)
        service.scanProject()
        LOG.info("Initial route scan completed for project: ${project.name}")
    }

    companion object {
        private val LOG = Logger.getInstance(RouteScanStartupActivity::class.java)
    }
}
