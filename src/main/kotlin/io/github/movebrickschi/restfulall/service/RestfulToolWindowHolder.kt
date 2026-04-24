package io.github.movebrickschi.restfulall.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.content.Content
import io.github.movebrickschi.restfulall.ui.RestfulToolWindowPanel

@Service(Service.Level.PROJECT)
class RestfulToolWindowHolder {

    @Volatile
    var panel: RestfulToolWindowPanel? = null

    @Volatile
    var interfaceContent: Content? = null

    companion object {
        fun getInstance(project: Project): RestfulToolWindowHolder =
            project.getService(RestfulToolWindowHolder::class.java)
    }
}
