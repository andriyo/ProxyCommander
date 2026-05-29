package io.github.andriyo.proxycommander

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

internal data class RememberedDevice(
    val id: String,
    val name: String
)

@Service(Service.Level.PROJECT)
@State(name = "ProxyCommanderSettings", storages = [Storage("proxyCommander.xml")])
class ProxyCommanderSettingsService : PersistentStateComponent<ProxyCommanderSettingsService.State> {

    data class RememberedDeviceState(
        var id: String = "",
        var name: String = ""
    )

    data class State(
        var port: Int = DEFAULT_PORT,
        var adbPath: String = "",
        var resetTimeOnConnect: Boolean = true,
        var rememberedDeviceIds: MutableList<String> = mutableListOf(),
        var rememberedDevices: MutableList<RememberedDeviceState> = mutableListOf()
    )

    private var state = State()

    @Synchronized
    override fun getState(): State = state

    @Synchronized
    override fun loadState(state: State) {
        this.state = state
        this.state.adbPath = this.state.adbPath.trim()
        if (this.state.rememberedDevices.isEmpty() && this.state.rememberedDeviceIds.isNotEmpty()) {
            this.state.rememberedDevices = normalizeDevices(
                this.state.rememberedDeviceIds.map { RememberedDevice(id = it, name = it) }
            )
        } else {
            this.state.rememberedDevices = normalizeDevices(
                this.state.rememberedDevices.map { RememberedDevice(id = it.id, name = it.name) }
            )
        }
        this.state.rememberedDeviceIds = this.state.rememberedDevices.map { it.id }.toMutableList()
    }

    @Synchronized
    internal fun getConfig(): ProxyCommanderConfig {
        val validPort = state.port.takeIf { it in 1..65535 } ?: DEFAULT_PORT
        return ProxyCommanderConfig(
            port = validPort,
            adbPath = state.adbPath.trim(),
            resetTimeOnConnect = state.resetTimeOnConnect
        )
    }

    @Synchronized
    internal fun updateConfig(port: Int, adbPath: String, resetTimeOnConnect: Boolean) {
        state.port = port
        state.adbPath = adbPath.trim()
        state.resetTimeOnConnect = resetTimeOnConnect
    }

    @Synchronized
    internal fun getRememberedDeviceIds(): Set<String> = state.rememberedDeviceIds.toSet()

    @Synchronized
    internal fun getRememberedDevices(): List<RememberedDevice> =
        state.rememberedDevices.map { RememberedDevice(id = it.id, name = it.name) }

    @Synchronized
    internal fun rememberDevices(devices: Collection<RememberedDevice>) {
        val merged = getRememberedDevices().associateBy { it.id }.toMutableMap()
        devices.forEach { device ->
            merged[device.id] = device
        }
        val normalized = normalizeDevices(merged.values)
        state.rememberedDevices = normalized
        state.rememberedDeviceIds = normalized.map { it.id }.toMutableList()
    }

    @Synchronized
    internal fun replaceRememberedDevices(devices: Collection<RememberedDevice>) {
        val normalized = normalizeDevices(devices)
        state.rememberedDevices = normalized
        state.rememberedDeviceIds = normalized.map { it.id }.toMutableList()
    }

    @Synchronized
    internal fun clearRememberedDevices() {
        state.rememberedDevices = mutableListOf()
        state.rememberedDeviceIds = mutableListOf()
    }

    private fun normalizeDevices(devices: Collection<RememberedDevice>): MutableList<RememberedDeviceState> =
        devices
            .asSequence()
            .map {
                RememberedDevice(
                    id = it.id.trim(),
                    name = it.name.trim().ifBlank { it.id.trim() }
                )
            }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }
            .sortedBy { it.id }
            .map { RememberedDeviceState(id = it.id, name = it.name) }
            .toMutableList()

    companion object {
        const val DEFAULT_PORT = 8888

        fun getInstance(project: Project): ProxyCommanderSettingsService =
            project.getService(ProxyCommanderSettingsService::class.java)
    }
}
