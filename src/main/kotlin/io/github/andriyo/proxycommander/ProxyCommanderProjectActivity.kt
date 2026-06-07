package io.github.andriyo.proxycommander

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ProxyCommanderProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Fold settings persisted by older project-level builds into the application-level service.
        ProxyCommanderLegacyProjectSettings.getInstance(project).consume()?.let { legacy ->
            ProxyCommanderSettingsService.getInstance().importLegacyState(legacy)
        }
        // Idempotent: a single watcher serves every open project (see refreshTracking).
        ProxyCommanderReconnectService.getInstance().refreshTracking()
    }
}
