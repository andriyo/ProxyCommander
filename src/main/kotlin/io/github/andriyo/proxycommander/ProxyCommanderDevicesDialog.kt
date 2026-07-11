package io.github.andriyo.proxycommander

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

internal object ProxyCommanderUi {
    fun openDevicesDialog(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                DevicesDialog(project, ProxyCommanderSettingsService.getInstance()).show()
            }
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
    private val statusLabel = JBLabel()
    private val loadingIcon = AnimatedIcon.Default()
    private val refreshButton = JButton("Refresh")
    private val connectAllButton = JButton("Proxy All", CONNECT_ALL_ICON)
    private val disconnectAllButton = JButton("Unproxy All", DISCONNECT_ALL_ICON)

    /** Row buttons paired with the enabled state they should have while no operation runs. */
    private val rowButtons = mutableListOf<Pair<JButton, Boolean>>()

    @Volatile
    private var busy = false
    private var lastSignaledKey: Pair<Set<String>, Set<String>>? = null
    private var lastRenderedEntries: List<DeviceDialogEntry>? = null

    /** Bumped on every reload (EDT-only); stale background reads are dropped on arrival. */
    private var reloadGeneration = 0

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
        connectAllButton.toolTipText = "Proxy all connected devices and remember them for auto-connect"
        disconnectAllButton.toolTipText = "Unproxy connected devices and disable auto-connect for every device"
        // addListener() immediately publishes the latest snapshot, which performs the initial load.
        ProxyCommanderReconnectService.getInstance().addListener(devicesListener)
    }

    override fun dispose() {
        ProxyCommanderReconnectService.getInstance().removeListener(devicesListener)
        super.dispose()
    }

    /** Lets the IDE remember the dialog's size and position across openings. */
    override fun getDimensionServiceKey(): String = "ProxyCommander.DevicesDialog"

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
        devicesPanel.add(JBLabel("Loading devices..."))
        root.add(JBScrollPane(devicesPanel), BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    /**
     * Refreshes the list in place: the current rows stay visible (and clickable, unless an
     * operation disabled them) while fresh data loads, and rows are only rebuilt when something
     * actually changed — no flashing.
     */
    private fun reloadDevices(statusOverride: String? = null) {
        val generation = ++reloadGeneration
        showStatus(statusOverride ?: "Refreshing devices...", loading = true)
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
                entries.isEmpty() -> "No devices available."
                else -> "Available: ${connectedDevices.size}. Remembered: ${remembered.size}."
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    if (isDisposed || generation != reloadGeneration) {
                        return@invokeLater // superseded by a newer reload
                    }
                    if (busy) {
                        return@invokeLater // an operation started meanwhile; it reloads on completion
                    }
                    renderDevicesIfChanged(entries)
                    setControlsEnabled(true)
                    showStatus(status, loading = false)
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

    private fun showStatus(message: String, loading: Boolean) {
        statusLabel.text = message
        statusLabel.icon = if (loading) loadingIcon else null
    }

    private fun setControlsEnabled(enabled: Boolean) {
        refreshButton.isEnabled = enabled
        connectAllButton.isEnabled = enabled
        disconnectAllButton.isEnabled = enabled
        rowButtons.forEach { (button, enabledWhenIdle) -> button.isEnabled = enabled && enabledWhenIdle }
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
            }
            ProxyCommanderReconnectService.getInstance().refreshTracking()
            success
        }
    }

    private fun disconnectAllDevices() {
        runOperation(
            busyMessage = "Removing proxy from all devices...",
            fallbackSuccess = "Proxy disconnected from all available devices.",
            fallbackFailure = "Failed to disconnect proxy from all available devices.",
            beforeOperation = {
                settings.clearRememberedDevices()
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
        ) { controller, log ->
            val success = controller.disconnectAllDevices(log)
            ProxyCommanderReconnectService.getInstance().refreshTracking()
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
            }
            ProxyCommanderReconnectService.getInstance().refreshTracking()
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
            val outcome = controller.keepOnlyDeviceWithOutcome(serial, log)
            if (outcome.selectedConnected) {
                settings.replaceRememberedDevices(listOf(RememberedDevice(entry.id, entry.name)))
            }
            ProxyCommanderReconnectService.getInstance().refreshTracking()
            outcome.success
        }
    }

    private fun unproxyDevice(entry: DeviceDialogEntry) {
        runOperation(
            busyMessage = "Removing proxy from ${entry.id}...",
            fallbackSuccess = "Proxy removed from ${entry.id}.",
            fallbackFailure = "Failed to remove proxy from ${entry.id}.",
            beforeOperation = {
                settings.forgetDevice(entry.id)
                ProxyCommanderReconnectService.getInstance().refreshTracking()
            }
        ) { controller, log ->
            val serial = entry.serial ?: return@runOperation false
            val success = controller.disconnectDevice(serial, log)
            ProxyCommanderReconnectService.getInstance().refreshTracking()
            success
        }
    }

    private fun forgetDevice(entry: DeviceDialogEntry) {
        busy = true
        setControlsEnabled(false)
        showStatus("Disabling auto-connect for ${entry.name}...", loading = true)
        ProxyCommanderMutationCoordinator.execute {
            settings.forgetDevice(entry.id)
            ProxyCommanderReconnectService.getInstance().refreshTracking()
            ApplicationManager.getApplication().invokeLater(
                {
                    if (isDisposed || project.isDisposed) {
                        return@invokeLater
                    }
                    busy = false
                    reloadDevices("Auto-connect disabled for ${entry.name}.")
                },
                ModalityState.any()
            )
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
        beforeOperation: () -> Unit = {},
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        busy = true
        setControlsEnabled(false)
        showStatus(busyMessage, loading = true)
        ProxyCommanderExecution.runControllerOperation(
            projectBasePath = project.basePath,
            config = settings.getConfig(),
            beforeOperation = beforeOperation,
            operation = operation
        ) { success, logs ->
            val message = ProxyCommanderExecution.summarize(logs, if (success) fallbackSuccess else fallbackFailure)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (isDisposed || project.isDisposed) {
                        return@invokeLater
                    }
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
                        // Controls stay disabled until the reload lands, so no stale row can be clicked.
                        reloadDevices(message)
                    } else {
                        setControlsEnabled(true)
                        showStatus(message, loading = false)
                    }
                },
                ModalityState.any()
            )
        }
    }

    private fun renderDevicesIfChanged(entries: List<DeviceDialogEntry>) {
        if (entries == lastRenderedEntries) {
            return
        }
        lastRenderedEntries = entries
        renderDevices(entries)
    }

    private fun renderDevices(entries: List<DeviceDialogEntry>) {
        devicesPanel.removeAll()
        rowButtons.clear()
        if (entries.isEmpty()) {
            devicesPanel.add(JBLabel("No devices found. Connect a device or use Proxy Commander actions first."))
        } else {
            entries.forEachIndexed { index, entry ->
                devicesPanel.add(deviceRow(entry, isLast = index == entries.lastIndex))
            }
        }
        devicesPanel.revalidate()
        devicesPanel.repaint()
    }

    private fun deviceRow(entry: DeviceDialogEntry, isLast: Boolean): JPanel {
        val row = JPanel(BorderLayout(8, 0))
        val padding = JBUI.Borders.empty(6, 2)
        row.border = if (isLast) {
            padding
        } else {
            BorderFactory.createCompoundBorder(JBUI.Borders.customLineBottom(JBColor.border()), padding)
        }
        row.alignmentX = 0f
        row.add(JBLabel(deviceInfoHtml(entry)), BorderLayout.CENTER)
        row.add(deviceActions(entry), BorderLayout.EAST)
        row.maximumSize = Dimension(Int.MAX_VALUE, maxOf(row.preferredSize.height, 56))
        return row
    }

    private fun deviceInfoHtml(entry: DeviceDialogEntry): String {
        val secondary = ColorUtil.toHtmlColor(UIUtil.getContextHelpForeground())
        val (dot, dotColor, statusText) = when {
            entry.proxyConnected -> Triple("●", ColorUtil.toHtmlColor(PROXIED_COLOR), "Proxied")
            entry.connected -> Triple("○", secondary, "Not proxied")
            else -> Triple("○", secondary, "Offline")
        }
        return buildString {
            append("<html><b>").append(StringUtil.escapeXmlEntities(entry.name)).append("</b>")
            append("&nbsp;&nbsp;<font color='").append(secondary).append("'>")
            append("API ").append(entry.apiLevel)
            append(" &middot; ").append(entry.serial ?: "offline")
            append("</font><br/>")
            append("<font color='").append(dotColor).append("'>").append(dot).append(' ').append(statusText).append("</font>")
            if (entry.remembered) {
                append("<font color='").append(secondary).append("'> &middot; Auto-connect</font>")
            }
            if (entry.ignored) {
                append("<font color='").append(secondary).append("'> &middot; Offers muted</font>")
            }
            append("</html>")
        }
    }

    private fun deviceActions(entry: DeviceDialogEntry): JPanel {
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
        actions.isOpaque = false
        addRowButton(
            actions, "Proxy", CONNECT_SINGLE_ICON,
            tooltip = "Enable reverse mapping + HTTP proxy and remember for auto-connect",
            enabledWhenIdle = entry.connected
        ) { connectDevice(entry) }
        addRowButton(
            actions, "Only This", CONNECT_CURRENT_ICON,
            tooltip = "Proxy this device and disconnect the proxy from every other device",
            enabledWhenIdle = entry.connected
        ) { connectOnlyDevice(entry) }
        addRowButton(
            actions, "Unproxy", DISCONNECT_SINGLE_ICON,
            tooltip = "Remove reverse mapping + HTTP proxy and disable auto-connect",
            enabledWhenIdle = entry.connected
        ) { unproxyDevice(entry) }
        addRowButton(
            actions, "Test", TEST_ICON,
            tooltip = "Verify device proxy, reverse mapping, and that a host proxy is listening",
            enabledWhenIdle = entry.connected
        ) { testConnection(entry) }
        addRowButton(
            actions, "Forget", FORGET_ICON,
            tooltip = "Disable auto-connect for this device (keeps its current proxy state)",
            enabledWhenIdle = entry.remembered
        ) { forgetDevice(entry) }
        return actions
    }

    private fun addRowButton(
        parent: JPanel,
        text: String,
        icon: Icon,
        tooltip: String,
        enabledWhenIdle: Boolean,
        onClick: () -> Unit
    ) {
        val button = JButton(text, icon)
        button.toolTipText = tooltip
        button.isEnabled = enabledWhenIdle && !busy
        button.addActionListener { onClick() }
        rowButtons += button to enabledWhenIdle
        parent.add(button)
    }

    private companion object {
        val CONNECT_ALL_ICON = IconLoader.getIcon("/icons/proxy_connect_all.svg", DevicesDialog::class.java)
        val DISCONNECT_ALL_ICON = IconLoader.getIcon("/icons/proxy_disconnect_all.svg", DevicesDialog::class.java)
        val CONNECT_SINGLE_ICON = IconLoader.getIcon("/icons/proxy_connect_all.svg", DevicesDialog::class.java)
        val CONNECT_CURRENT_ICON = IconLoader.getIcon("/icons/proxy_connect_active.svg", DevicesDialog::class.java)
        val DISCONNECT_SINGLE_ICON = IconLoader.getIcon("/icons/proxy_disconnect_all.svg", DevicesDialog::class.java)
        val TEST_ICON = AllIcons.Actions.Help
        val FORGET_ICON = AllIcons.Actions.Cancel

        /** Green that works on both light and dark themes. */
        val PROXIED_COLOR = JBColor(0x2E7D32, 0x499C54)
    }
}
