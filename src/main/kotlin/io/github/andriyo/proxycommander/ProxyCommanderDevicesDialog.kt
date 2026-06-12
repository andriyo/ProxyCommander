package io.github.andriyo.proxycommander

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
    val remembered: Boolean,
    val ignored: Boolean
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
    private var lastSignaledKey: Pair<Set<String>, Set<String>>? = null

    // Live-update: when the background watcher reports a change in connected devices or their
    // proxy state, refresh the list instead of forcing the user to click Refresh. Skipped while
    // an operation is mid-flight.
    private val devicesListener = ProxyCommanderReconnectService.DevicesListener { serials ->
        val proxied = ProxyCommanderReconnectService.getInstance().proxiedSerials()
        ApplicationManager.getApplication().invokeLater(
            {
                val key = serials to proxied
                if (isDisposed || busy || key == lastSignaledKey) {
                    return@invokeLater
                }
                lastSignaledKey = key
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
        root.preferredSize = Dimension(920, 420)
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
            val ignored = settings.getIgnoredDeviceIds()
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, settings.getConfig())
            val connectedDevices = if (controller.ensureAdbAvailable(logs::add)) {
                controller.listConnectedDeviceDetails(logs::add)
            } else {
                null
            }

            val entries = buildEntries(remembered, ignored, connectedDevices)
            val status = statusOverride ?: when {
                connectedDevices == null -> ProxyCommanderExecution.summarize(logs, "ADB command is not available.")
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
        ignored: Set<String>,
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
                    remembered = remembered.any { it.id == device.id },
                    ignored = device.id in ignored
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
                            remembered = false,
                            ignored = it.identifier in ignored
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

    private fun unproxyDevice(entry: DeviceDialogEntry) {
        runOperation(
            busyMessage = "Removing proxy from ${entry.id}...",
            fallbackSuccess = "Proxy removed from ${entry.id}.",
            fallbackFailure = "Failed to remove proxy from ${entry.id}."
        ) { controller, log ->
            val serial = entry.serial ?: return@runOperation false
            val success = controller.disconnectDevice(serial, log)
            if (success) {
                // Also stop auto-connect; otherwise the watcher would re-apply the proxy moments later.
                settings.forgetDevice(entry.id)
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
            success
        }
    }

    private fun forgetDevice(entry: DeviceDialogEntry) {
        settings.forgetDevice(entry.id)
        ProxyCommanderReconnectService.getInstance().refreshTracking()
        reloadDevices("Auto-connect disabled for ${entry.name}.")
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
        ProxyCommanderExecution.runControllerOperation(
            projectBasePath = project.basePath,
            config = settings.getConfig(),
            operation = operation
        ) { success, logs ->
            val message = ProxyCommanderExecution.summarize(logs, if (success) fallbackSuccess else fallbackFailure)
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
                    if (entry.ignored) {
                        append(" | Offers muted")
                    }
                    append("</html>")
                }
                row.add(JBLabel(infoText), BorderLayout.CENTER)

                val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
                val connectButton = JButton("Proxy", CONNECT_SINGLE_ICON)
                connectButton.toolTipText = "Enable reverse mapping + HTTP proxy and remember for auto-connect"
                connectButton.isEnabled = entry.connected
                connectButton.addActionListener { connectDevice(entry) }
                actions.add(connectButton)

                val connectOnlyButton = JButton("Only This", CONNECT_CURRENT_ICON)
                connectOnlyButton.toolTipText = "Proxy this device and disconnect the proxy from every other device"
                connectOnlyButton.isEnabled = entry.connected
                connectOnlyButton.addActionListener { connectOnlyDevice(entry) }
                actions.add(connectOnlyButton)

                val unproxyButton = JButton("Unproxy", DISCONNECT_SINGLE_ICON)
                unproxyButton.toolTipText = "Remove reverse mapping + HTTP proxy and disable auto-connect"
                unproxyButton.isEnabled = entry.connected
                unproxyButton.addActionListener { unproxyDevice(entry) }
                actions.add(unproxyButton)

                val testButton = JButton("Test", TEST_ICON)
                testButton.toolTipText = "Verify device proxy, reverse mapping, and that a host proxy is listening"
                testButton.isEnabled = entry.connected
                testButton.addActionListener { testConnection(entry) }
                actions.add(testButton)

                val forgetButton = JButton("Forget", FORGET_ICON)
                forgetButton.toolTipText = "Disable auto-connect for this device (keeps its current proxy state)"
                forgetButton.isEnabled = entry.remembered
                forgetButton.addActionListener { forgetDevice(entry) }
                actions.add(forgetButton)

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
        val DISCONNECT_SINGLE_ICON = IconLoader.getIcon("/icons/proxy_disconnect_all.svg", DevicesDialog::class.java)
        val TEST_ICON = AllIcons.Actions.Help
        val FORGET_ICON = AllIcons.Actions.Cancel
    }
}
