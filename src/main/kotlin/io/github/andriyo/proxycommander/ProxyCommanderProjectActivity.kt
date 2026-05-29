package io.github.andriyo.proxycommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ProxyCommanderProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ProxyCommanderReconnectService.getInstance(project).refreshTracking()
    }
}
