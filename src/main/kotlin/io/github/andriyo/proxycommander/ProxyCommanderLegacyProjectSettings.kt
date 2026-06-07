package io.github.andriyo.proxycommander

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Reads the settings that older builds persisted per-project in `.idea/proxyCommander.xml` so they
 * can be folded into the application-level [ProxyCommanderSettingsService] the first time a migrated
 * project opens. Once consumed, the legacy state is dropped and the file is no longer rewritten.
 */
@Service(Service.Level.PROJECT)
@State(name = "ProxyCommanderSettings", storages = [Storage("proxyCommander.xml")])
internal class ProxyCommanderLegacyProjectSettings :
    PersistentStateComponent<ProxyCommanderSettingsService.State> {

    private var state: ProxyCommanderSettingsService.State? = null

    override fun getState(): ProxyCommanderSettingsService.State? = state

    override fun loadState(state: ProxyCommanderSettingsService.State) {
        this.state = state
    }

    /** Returns the legacy state once, or null if there is nothing to migrate. */
    @Synchronized
    fun consume(): ProxyCommanderSettingsService.State? {
        val current = state ?: return null
        val hasData = current.rememberedDevices.isNotEmpty() ||
            current.rememberedDeviceIds.isNotEmpty() ||
            current.adbPath.isNotBlank() ||
            current.port != ProxyCommanderSettingsService.DEFAULT_PORT
        // Reset to defaults so the framework clears the legacy `.idea/proxyCommander.xml` entry on
        // the next save and we never re-import (which would resurrect devices removed app-wide).
        state = ProxyCommanderSettingsService.State()
        return if (hasData) current else null
    }

    companion object {
        fun getInstance(project: Project): ProxyCommanderLegacyProjectSettings =
            project.getService(ProxyCommanderLegacyProjectSettings::class.java)
    }
}
