package io.github.andriyo.proxycommander

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.AbstractButton
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class ConnectAllDevicesAction : DumbAwareAction(
    "Connect Proxy to All Devices",
    "Enable reverse proxy and HTTP proxy on all available devices",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runConnectAll(project)
    }
}

class DisconnectAllDevicesAction : DumbAwareAction(
    "Disconnect Proxy from All Devices",
    "Disable reverse proxy and clear HTTP proxy on all available devices",
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
    "Enable reverse proxy for the current emulator and disconnect proxy from all other available devices",
    AllIcons.Actions.Execute
) {
    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = true
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = ProxyCommanderStreamingContextResolver.extract(event)
        ProxyCommanderActionRunner.runConnectActiveEmulatorAndClearOthersProxy(project, target)
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

private data class StreamingTarget(
    val serial: String,
    val source: String,
    val kind: String
)

private object ProxyCommanderStreamingContextResolver {
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

private object ProxyCommanderActionRunner {
    fun runConnectAll(project: Project) {
        val settings = ProxyCommanderSettingsService.getInstance()
        runBulkWithEmulatorSummary(
            project = project,
            actionName = "Connect proxy to all devices"
        ) { controller, log ->
            val success = controller.connectAllDevices(log)
            if (success) {
                settings.rememberDevices(controller.listConnectedDeviceDetails(log).map { RememberedDevice(it.identifier, it.name) })
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    fun runDisconnectAll(project: Project) {
        val settings = ProxyCommanderSettingsService.getInstance()
        runBulkWithEmulatorSummary(
            project = project,
            actionName = "Disconnect proxy from all devices"
        ) { controller, log ->
            val success = controller.disconnectAllDevices(log)
            if (success) {
                settings.clearRememberedDevices()
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    fun runDevices(project: Project) {
        ProxyCommanderUi.openDevicesDialog(project)
    }

    fun runConnectActiveEmulatorAndClearOthersProxy(project: Project, target: StreamingTarget?) {
        val settings = ProxyCommanderSettingsService.getInstance()
        runInBackground(
            project = project,
            actionName = "Connect proxy to current emulator and disconnect proxy from other devices"
        ) { controller, log ->
            val activeSerial = resolveActiveEmulatorSerial(target, controller, log)
                ?: return@runInBackground false
            val success = controller.connectEmulatorAndClearProxyOnOthers(activeSerial, log)
            if (success) {
                val remembered = controller.listConnectedDeviceDetails(log)
                    .firstOrNull { it.serial == activeSerial }
                    ?.let { RememberedDevice(it.identifier, it.name) }
                    ?: RememberedDevice(activeSerial, activeSerial)
                settings.replaceRememberedDevices(listOf(remembered))
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    fun runSettings(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, ProxyCommanderConfigurable::class.java)
        }
    }

    private fun runInBackground(
        project: Project,
        actionName: String,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        val config = ProxyCommanderSettingsService.getInstance().getConfig()
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, config)
            val success = if (!controller.ensureAdbAvailable(logs::add)) {
                false
            } else {
                runCatching {
                    operation(controller, logs::add)
                }.getOrElse { error ->
                    logs += "[ProxyCommander] Error: ${error.message}"
                    false
                }
            }

            val fallback = if (success) {
                "$actionName completed."
            } else {
                "$actionName failed."
            }
            ProxyCommanderNotifications.notify(
                message = summarize(logs, fallback),
                type = if (success) NotificationType.INFORMATION else NotificationType.ERROR,
                project = project
            )
        }
    }

    private fun runBulkWithEmulatorSummary(
        project: Project,
        actionName: String,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        val config = ProxyCommanderSettingsService.getInstance().getConfig()
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, config)
            val adbAvailable = controller.ensureAdbAvailable(logs::add)
            val targetedEmulators = if (adbAvailable) {
                controller.listConnectedEmulators(logs::add)
            } else {
                emptyList()
            }
            val success = if (!adbAvailable) {
                false
            } else {
                runCatching {
                    operation(controller, logs::add)
                }.getOrElse { error ->
                    logs += "[ProxyCommander] Error: ${error.message}"
                    false
                }
            }

            val actionSummary = if (success) {
                "$actionName completed."
            } else {
                "$actionName failed."
            }
            val message = if (adbAvailable) {
                val emulatorSummary = emulatorSummary(targetedEmulators)
                val base = summarize(logs, actionSummary)
                "$base $emulatorSummary".trim()
            } else {
                summarize(logs, actionSummary)
            }
            ProxyCommanderNotifications.notify(
                message = message,
                type = if (success) NotificationType.INFORMATION else NotificationType.ERROR,
                project = project
            )
        }
    }

    private fun emulatorSummary(emulators: List<ConnectedEmulator>): String {
        if (emulators.isEmpty()) {
            return "Emulators: none."
        }

        val duplicateNameCounts = emulators.groupingBy { it.avdName }.eachCount()
        val names = emulators.map { emulator ->
            when {
                duplicateNameCounts[emulator.avdName] ?: 0 > 1 -> "${emulator.avdName} [${emulator.serial}]"
                else -> emulator.avdName
            }
        }
        return "Emulators: ${names.joinToString(", ")}."
    }

    private fun resolveActiveEmulatorSerial(
        target: StreamingTarget?,
        controller: ProxyCommanderController,
        log: (String) -> Unit
    ): String? {
        val targetSerial = target?.serial?.trim().orEmpty()
        if (targetSerial.isNotEmpty()) {
            if (ProxyCommanderParsing.isEmulatorSerial(targetSerial)) {
                val source = target?.source ?: "unknown source"
                log("[ProxyCommander] Active emulator from $source: $targetSerial")
                return targetSerial
            }
            log("[ProxyCommander] Active target is not an emulator: '$targetSerial'.")
            return null
        }

        val connectedEmulators = controller.listConnectedEmulators(log)
        return when (connectedEmulators.size) {
            0 -> {
                log("[ProxyCommander] No connected emulators found.")
                null
            }
            1 -> {
                val emulator = connectedEmulators.first()
                log("[ProxyCommander] Active emulator context unavailable; using the only connected emulator ${emulator.avdName} [${emulator.serial}].")
                emulator.serial
            }
            else -> {
                val list = connectedEmulators.joinToString(", ") { "${it.avdName} [${it.serial}]" }
                log("[ProxyCommander] Unable to determine active emulator from context. Connected emulators: $list.")
                null
            }
        }
    }

    private fun summarize(logs: List<String>, fallback: String): String {
        val lastLog = logs.lastOrNull { it.isNotBlank() }?.removePrefix("[ProxyCommander] ")?.trim()
        return lastLog.takeUnless { it.isNullOrBlank() } ?: fallback
    }
}

internal object ProxyCommanderUi {
    fun openDevicesDialog(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            DevicesDialog(project, ProxyCommanderSettingsService.getInstance()).show()
        }
    }
}

private data class DeviceDialogEntry(
    val id: String,
    val name: String,
    val apiLevel: String,
    val serial: String?,
    val connected: Boolean,
    val proxyConnected: Boolean,
    val remembered: Boolean
)

private class DevicesDialog(
    private val project: Project,
    private val settings: ProxyCommanderSettingsService
) : DialogWrapper(project) {

    private val devicesPanel = JPanel()
    private val statusLabel = JBLabel("Loading devices...")
    private val refreshButton = JButton("Refresh")
    private val connectAllButton = JButton("Proxy All", CONNECT_ALL_ICON)
    private val disconnectAllButton = JButton("Unproxy All", DISCONNECT_ALL_ICON)

    @Volatile
    private var busy = false
    private var lastSignaledSerials: Set<String>? = null

    // Live-update: when the background watcher reports a change in connected devices, refresh the
    // list instead of forcing the user to click Refresh. Skipped while an operation is mid-flight.
    private val devicesListener = ProxyCommanderReconnectService.DevicesListener { serials ->
        ApplicationManager.getApplication().invokeLater(
            {
                if (isDisposed || busy || serials == lastSignaledSerials) {
                    return@invokeLater
                }
                lastSignaledSerials = serials
                reloadDevices()
            },
            ModalityState.any()
        )
    }

    init {
        title = "Devices"
        cancelAction.putValue(Action.NAME, "Close")
        init()
        refreshButton.addActionListener { reloadDevices() }
        connectAllButton.addActionListener { connectAllDevices() }
        disconnectAllButton.addActionListener { disconnectAllDevices() }
        ProxyCommanderReconnectService.getInstance().addListener(devicesListener)
        reloadDevices()
    }

    override fun dispose() {
        ProxyCommanderReconnectService.getInstance().removeListener(devicesListener)
        super.dispose()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(820, 420)
        root.border = JBUI.Borders.empty(8)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        controls.add(JBLabel("Available devices and remembered reconnect targets"))
        controls.add(connectAllButton)
        controls.add(disconnectAllButton)
        controls.add(refreshButton)
        root.add(controls, BorderLayout.NORTH)

        devicesPanel.layout = BoxLayout(devicesPanel, BoxLayout.Y_AXIS)
        devicesPanel.border = JBUI.Borders.empty(6)
        root.add(JBScrollPane(devicesPanel), BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    private fun reloadDevices(statusOverride: String? = null) {
        setLoadingState("Loading devices...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val remembered = settings.getRememberedDevices()
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, settings.getConfig())
            val connectedDevices = if (controller.ensureAdbAvailable(logs::add)) {
                controller.listConnectedDeviceDetails(logs::add)
            } else {
                null
            }

            val entries = buildEntries(remembered, connectedDevices)
            val status = statusOverride ?: when {
                connectedDevices == null -> summarizeLogs(logs, "ADB command is not available.")
                connectedDevices.isEmpty() && entries.isEmpty() -> "No devices available."
                else -> "Available: ${connectedDevices.size}. Remembered: ${remembered.size}."
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    renderDevices(entries)
                    refreshButton.isEnabled = true
                    connectAllButton.isEnabled = true
                    disconnectAllButton.isEnabled = true
                    statusLabel.text = status
                },
                ModalityState.any()
            )
        }
    }

    private fun buildEntries(
        remembered: List<RememberedDevice>,
        connectedDevices: List<ConnectedDeviceDetails>?
    ): List<DeviceDialogEntry> {
        val rememberedById = remembered.associateBy { it.id }.toMutableMap()
        val connectedById = (connectedDevices ?: emptyList()).associateBy { it.identifier }
        connectedDevices.orEmpty().forEach { connected ->
            rememberedById.putIfAbsent(connected.identifier, RememberedDevice(connected.identifier, connected.name))
        }

        return rememberedById.values
            .map { device ->
                val connected = connectedById[device.id]
                DeviceDialogEntry(
                    id = device.id,
                    name = connected?.name ?: device.name,
                    apiLevel = connected?.apiLevel ?: "?",
                    serial = connected?.serial,
                    connected = connected != null,
                    proxyConnected = connected?.isProxyConnected == true,
                    remembered = remembered.any { it.id == device.id }
                )
            }
            .plus(
                connectedDevices.orEmpty()
                    .filter { connected -> connected.identifier !in rememberedById }
                    .map {
                        DeviceDialogEntry(
                            id = it.identifier,
                            name = it.name,
                            apiLevel = it.apiLevel,
                            serial = it.serial,
                            connected = true,
                            proxyConnected = it.isProxyConnected,
                            remembered = false
                        )
                    }
            )
            .sortedWith(
                compareByDescending<DeviceDialogEntry> { it.connected }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            )
    }

    private fun setLoadingState(message: String) {
        refreshButton.isEnabled = false
        connectAllButton.isEnabled = false
        disconnectAllButton.isEnabled = false
        devicesPanel.removeAll()
        devicesPanel.add(JBLabel(message))
        devicesPanel.revalidate()
        devicesPanel.repaint()
        statusLabel.text = message
    }

    private fun setOperationInProgress(message: String) {
        refreshButton.isEnabled = false
        connectAllButton.isEnabled = false
        disconnectAllButton.isEnabled = false
        setButtonsEnabled(devicesPanel, false)
        statusLabel.text = message
    }

    private fun connectAllDevices() {
        runOperation(
            busyMessage = "Proxying all devices...",
            fallbackSuccess = "Proxy connected to all available devices.",
            fallbackFailure = "Failed to connect proxy to all available devices."
        ) { controller, log ->
            val success = controller.connectAllDevices(log)
            if (success) {
                settings.rememberDevices(
                    controller.listConnectedDeviceDetails(log).map { RememberedDevice(it.identifier, it.name) }
                )
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    private fun disconnectAllDevices() {
        runOperation(
            busyMessage = "Removing proxy from all devices...",
            fallbackSuccess = "Proxy disconnected from all available devices.",
            fallbackFailure = "Failed to disconnect proxy from all available devices."
        ) { controller, log ->
            val success = controller.disconnectAllDevices(log)
            if (success) {
                settings.clearRememberedDevices()
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    private fun connectDevice(entry: DeviceDialogEntry) {
        runOperation(
            busyMessage = "Connecting proxy to ${entry.id}...",
            fallbackSuccess = "Proxy connected to ${entry.id}.",
            fallbackFailure = "Failed to connect proxy to ${entry.id}."
        ) { controller, log ->
            val serial = entry.serial ?: return@runOperation false
            val success = controller.connectDevice(serial, log)
            if (success) {
                settings.rememberDevices(listOf(RememberedDevice(entry.id, entry.name)))
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    private fun connectOnlyDevice(entry: DeviceDialogEntry) {
        runOperation(
            busyMessage = "Connecting proxy only to ${entry.id}...",
            fallbackSuccess = "Proxy connected only to ${entry.id}.",
            fallbackFailure = "Failed to connect proxy only to ${entry.id}."
        ) { controller, log ->
            val serial = entry.serial ?: return@runOperation false
            val success = controller.keepOnlyDevice(serial, log)
            if (success) {
                settings.replaceRememberedDevices(listOf(RememberedDevice(entry.id, entry.name)))
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    private fun testConnection(entry: DeviceDialogEntry) {
        runOperation(
            busyMessage = "Testing ${entry.id}...",
            fallbackSuccess = "Test connection completed for ${entry.id}.",
            fallbackFailure = "Test connection failed for ${entry.id}.",
            showDialogOnCompletion = true,
            showNotificationOnCompletion = false
        ) { controller, log ->
            val serial = entry.serial ?: return@runOperation false
            controller.testProxyConnection(serial, log)
        }
    }

    private fun runOperation(
        busyMessage: String,
        fallbackSuccess: String,
        fallbackFailure: String,
        reloadAfter: Boolean = true,
        showDialogOnCompletion: Boolean = false,
        showNotificationOnCompletion: Boolean = true,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        busy = true
        setOperationInProgress(busyMessage)
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, settings.getConfig())
            val success = if (!controller.ensureAdbAvailable(logs::add)) {
                false
            } else {
                runCatching {
                    operation(controller, logs::add)
                }.getOrElse { error ->
                    logs += "[ProxyCommander] Error: ${error.message}"
                    false
                }
            }
            val message = summarizeLogs(logs, if (success) fallbackSuccess else fallbackFailure)
            ApplicationManager.getApplication().invokeLater(
                {
                    busy = false
                    if (showNotificationOnCompletion) {
                        ProxyCommanderNotifications.notify(
                            message = message,
                            type = if (success) NotificationType.INFORMATION else NotificationType.ERROR,
                            project = project
                        )
                    }
                    if (showDialogOnCompletion) {
                        if (success) {
                            Messages.showInfoMessage(project, message, "ProxyCommander")
                        } else {
                            Messages.showErrorDialog(project, message, "ProxyCommander")
                        }
                    }
                    if (reloadAfter) {
                        reloadDevices(message)
                    } else {
                        setButtonsEnabled(devicesPanel, true)
                        refreshButton.isEnabled = true
                        connectAllButton.isEnabled = true
                        disconnectAllButton.isEnabled = true
                        statusLabel.text = message
                    }
                },
                ModalityState.any()
            )
        }
    }

    private fun summarizeLogs(logs: List<String>, fallback: String): String {
        val lastLog = logs.lastOrNull { it.isNotBlank() }?.removePrefix("[ProxyCommander] ")?.trim()
        return lastLog.takeUnless { it.isNullOrBlank() } ?: fallback
    }

    private fun renderDevices(entries: List<DeviceDialogEntry>) {
        devicesPanel.removeAll()
        if (entries.isEmpty()) {
            devicesPanel.add(JBLabel("No devices found. Connect a device or use Proxy Commander actions first."))
        } else {
            entries.forEach { entry ->
                val row = JPanel(BorderLayout(8, 0))
                row.border = JBUI.Borders.empty(4, 0)
                row.alignmentX = 0f

                val infoText = buildString {
                    append("<html><b>")
                    append(entry.name)
                    append("</b> &nbsp; <code>")
                    append("API ")
                    append(entry.apiLevel)
                    append("</code> &nbsp; <code>")
                    append(entry.serial ?: "serial unavailable")
                    append("</code>")
                    append("<br/>Status: ")
                    append(if (entry.connected) "Available" else "Unavailable")
                    append(" | Proxy: ")
                    append(if (entry.proxyConnected) "Connected" else "Not Connected")
                    if (entry.remembered) {
                        append(" | Auto-connect enabled")
                    }
                    append("</html>")
                }
                row.add(JBLabel(infoText), BorderLayout.CENTER)

                val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
                val connectButton = JButton("Proxy", CONNECT_SINGLE_ICON)
                connectButton.isEnabled = entry.connected
                connectButton.addActionListener { connectDevice(entry) }
                actions.add(connectButton)

                val connectOnlyButton = JButton("Proxy only this", CONNECT_CURRENT_ICON)
                connectOnlyButton.isEnabled = entry.connected
                connectOnlyButton.addActionListener { connectOnlyDevice(entry) }
                actions.add(connectOnlyButton)

                val testButton = JButton("Test", TEST_ICON)
                testButton.isEnabled = entry.connected
                testButton.addActionListener { testConnection(entry) }
                actions.add(testButton)

                row.add(actions, BorderLayout.EAST)
                row.maximumSize = Dimension(Int.MAX_VALUE, maxOf(row.preferredSize.height, 56))

                devicesPanel.add(row)
                devicesPanel.add(Box.createVerticalStrut(4))
            }
        }
        devicesPanel.revalidate()
        devicesPanel.repaint()
    }

    private fun setButtonsEnabled(component: Component, enabled: Boolean) {
        if (component is AbstractButton) {
            component.isEnabled = enabled
        }
        if (component is Container) {
            component.components.forEach { child ->
                setButtonsEnabled(child, enabled)
            }
        }
    }

    private companion object {
        val CONNECT_ALL_ICON = IconLoader.getIcon("/icons/proxy_connect_all.svg", DevicesDialog::class.java)
        val DISCONNECT_ALL_ICON = IconLoader.getIcon("/icons/proxy_disconnect_all.svg", DevicesDialog::class.java)
        val CONNECT_SINGLE_ICON = IconLoader.getIcon("/icons/proxy_connect_all.svg", DevicesDialog::class.java)
        val CONNECT_CURRENT_ICON = IconLoader.getIcon("/icons/proxy_connect_active.svg", DevicesDialog::class.java)
        val TEST_ICON = AllIcons.Actions.Help
    }
}
