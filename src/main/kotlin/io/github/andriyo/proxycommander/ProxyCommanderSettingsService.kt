package io.github.andriyo.proxycommander

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "ProxyCommanderSettings", storages = [Storage("proxyCommander.xml")])
class ProxyCommanderSettingsService : PersistentStateComponent<ProxyCommanderSettingsService.State> {

    data class State(
        var port: Int = DEFAULT_PORT,
        var adbPath: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    internal fun getConfig(): ProxyCommanderConfig {
        val validPort = state.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        return ProxyCommanderConfig(
            port = validPort,
            adbPath = state.adbPath.trim()
        )
    }

    internal fun updateConfig(port: Int, adbPath: String) {
        state.port = port
        state.adbPath = adbPath.trim()
    }

    companion object {
        const val DEFAULT_PORT = 8888

        fun getInstance(project: Project): ProxyCommanderSettingsService =
            project.getService(ProxyCommanderSettingsService::class.java)
    }
}
