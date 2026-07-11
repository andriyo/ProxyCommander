package io.github.andriyo.proxycommander

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.project.DumbAwareAction

class ConnectAllDevicesAction : DumbAwareAction(
    "Connect Proxy to All Devices",
    "Enable the proxy and remember all available devices for auto-connect",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runConnectAll(project)
    }
}

class DisconnectAllDevicesAction : DumbAwareAction(
    "Disconnect Proxy from All Devices",
    "Disable reverse proxy, clear HTTP proxy, and turn off auto-connect for all devices",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runDisconnectAll(project)
    }
}

class KeepSelectedDeviceAction : DumbAwareAction(
    "Devices...",
    "Manage device connections and auto-connect behavior",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runDevices(project)
    }
}

class ConnectActiveEmulatorClearOthersProxyAction : DumbAwareAction(
    "Connect Proxy to Current and Disconnect Others",
    "Proxy only the current device and make it the sole auto-connect target",
    AllIcons.Actions.Execute
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = true
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = ProxyCommanderStreamingContextResolver.extract(event)
        ProxyCommanderActionRunner.runConnectCurrentAndClearOthersProxy(project, target)
    }
}

class ProxyCommanderSettingsAction : DumbAwareAction(
    "Settings...",
    "Configure Proxy Commander port and adb path",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runSettings(project)
    }
}

internal data class StreamingTarget(
    val serial: String,
    val source: String,
    val kind: String
)

internal object ProxyCommanderStreamingContextResolver {
    private val serialNumberKey: DataKey<String> = DataKey.create("SerialNumber")
    private val deviceIdKey: DataKey<Any> = DataKey.create("DeviceId")

    fun extract(event: AnActionEvent): StreamingTarget? {
        val context = event.dataContext
        val serialFromRunningDevices = serialNumberKey.getData(context)
        if (!serialFromRunningDevices.isNullOrBlank()) {
            return StreamingTarget(
                serial = serialFromRunningDevices,
                source = "DataKey(\"SerialNumber\")",
                kind = "unknown"
            )
        }

        val deviceId = deviceIdKey.getData(context) ?: return null
        val serial = readSerialNumber(deviceId) ?: return null
        return StreamingTarget(
            serial = serial,
            source = "DataKey(\"DeviceId\")",
            kind = when {
                deviceId.javaClass.simpleName.contains("Emulator", ignoreCase = true) -> "emulator"
                deviceId.javaClass.simpleName.contains("Physical", ignoreCase = true) -> "physical"
                else -> deviceId.javaClass.simpleName
            }
        )
    }

    private fun readSerialNumber(deviceId: Any): String? =
        runCatching {
            val method = deviceId.javaClass.methods.firstOrNull {
                it.name == "getSerialNumber" && it.parameterCount == 0
            } ?: return null
            (method.invoke(deviceId) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
}
