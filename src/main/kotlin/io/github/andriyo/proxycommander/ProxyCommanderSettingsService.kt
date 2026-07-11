package io.github.andriyo.proxycommander

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

internal data class RememberedDevice(
    val id: String,
    val name: String
)

internal val VALID_PORT_RANGE = 1..65535

@Service(Service.Level.APP)
@State(
    name = "ProxyCommanderSettings",
    // ADB paths and device identifiers belong to this workstation and must not be copied to a
    // different OS / SDK installation by Settings Sync.
    storages = [Storage(value = "proxyCommander.xml", roamingType = RoamingType.DISABLED)]
)
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
        var rememberedDevices: MutableList<RememberedDeviceState> = mutableListOf(),
        var ignoredDeviceIds: MutableList<String> = mutableListOf(),
        var previousPorts: MutableList<Int> = mutableListOf()
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
        this.state.ignoredDeviceIds = normalizeIds(this.state.ignoredDeviceIds)
        this.state.previousPorts = sanitizePorts(this.state.previousPorts).toMutableList()
    }

    @Synchronized
    internal fun getConfig(): ProxyCommanderConfig {
        val validPort = state.port.takeIf { it in VALID_PORT_RANGE } ?: DEFAULT_PORT
        return ProxyCommanderConfig(
            port = validPort,
            adbPath = state.adbPath.trim(),
            resetTimeOnConnect = state.resetTimeOnConnect,
            previousPorts = sanitizePorts(state.previousPorts).toSet()
        )
    }

    @Synchronized
    internal fun updateConfig(port: Int, adbPath: String, resetTimeOnConnect: Boolean) {
        if (port != state.port && state.port in VALID_PORT_RANGE) {
            // Keep a short history of earlier ports so connect/disconnect can clean up reverse
            // mappings that were applied before a port change.
            state.previousPorts = sanitizePorts(state.previousPorts + state.port)
                .filter { it != port }
                .takeLast(MAX_PREVIOUS_PORTS)
                .toMutableList()
        }
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
        applyRememberedDevices(normalizeDevices(merged.values))
    }

    @Synchronized
    internal fun replaceRememberedDevices(devices: Collection<RememberedDevice>) {
        applyRememberedDevices(normalizeDevices(devices))
    }

    @Synchronized
    internal fun forgetDevice(id: String) {
        val trimmed = id.trim()
        val remaining = getRememberedDevices().filterNot { it.id == trimmed }
        state.rememberedDevices = normalizeDevices(remaining)
        state.rememberedDeviceIds = state.rememberedDevices.map { it.id }.toMutableList()
    }

    @Synchronized
    internal fun clearRememberedDevices() {
        state.rememberedDevices = mutableListOf()
        state.rememberedDeviceIds = mutableListOf()
    }

    @Synchronized
    internal fun getIgnoredDeviceIds(): Set<String> = state.ignoredDeviceIds.toSet()

    /** Suppresses the "connect this new device?" offer for the device permanently. */
    @Synchronized
    internal fun ignoreDevice(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty() || trimmed in state.ignoredDeviceIds) {
            return
        }
        state.ignoredDeviceIds = normalizeIds(state.ignoredDeviceIds + trimmed)
    }

    private fun applyRememberedDevices(normalized: MutableList<RememberedDeviceState>) {
        state.rememberedDevices = normalized
        state.rememberedDeviceIds = normalized.map { it.id }.toMutableList()
        // Connecting a device is an explicit opt-in, so it stops being ignored.
        val rememberedIds = normalized.map { it.id }.toSet()
        state.ignoredDeviceIds = normalizeIds(state.ignoredDeviceIds.filterNot { it in rememberedIds })
    }

    /**
     * Folds settings persisted by an older, project-level build into the application-level state.
     * Customized application values win, so opening several migrated projects never clobbers a
     * port/adb path the user already adjusted; remembered devices from every project are unioned.
     */
    @Synchronized
    internal fun importLegacyState(legacy: State) {
        if (state.port == DEFAULT_PORT && legacy.port in VALID_PORT_RANGE && legacy.port != DEFAULT_PORT) {
            state.port = legacy.port
        }
        if (state.adbPath.isBlank() && legacy.adbPath.isNotBlank()) {
            state.adbPath = legacy.adbPath.trim()
        }
        if (state.resetTimeOnConnect && !legacy.resetTimeOnConnect) {
            // The default is true, so a legacy `false` is a deliberate user choice worth keeping.
            state.resetTimeOnConnect = false
        }
        val legacyDevices = legacy.rememberedDevices
            .map { RememberedDevice(id = it.id, name = it.name) }
            .ifEmpty { legacy.rememberedDeviceIds.map { RememberedDevice(id = it, name = it) } }
        if (legacyDevices.isNotEmpty()) {
            rememberDevices(legacyDevices)
        }
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

    private fun normalizeIds(ids: Collection<String>): MutableList<String> =
        ids.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .toMutableList()

    private fun sanitizePorts(ports: Collection<Int>): List<Int> =
        ports.filter { it in VALID_PORT_RANGE }.distinct()

    companion object {
        const val DEFAULT_PORT = 8888
        private const val MAX_PREVIOUS_PORTS = 8

        fun getInstance(): ProxyCommanderSettingsService =
            ApplicationManager.getApplication().getService(ProxyCommanderSettingsService::class.java)
    }
}
