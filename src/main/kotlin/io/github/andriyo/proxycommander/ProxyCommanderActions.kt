package io.github.andriyo.proxycommander

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class ConnectAllDevicesAction : DumbAwareAction(
    "Connect All",
    "Enable reverse proxy and HTTP proxy on all connected devices",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runConnectAll(project)
    }
}

class DisconnectAllDevicesAction : DumbAwareAction(
    "Disconnect All",
    "Disable reverse proxy and clear HTTP proxy on all connected devices",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runDisconnectAll(project)
    }
}

class KeepSelectedDeviceAction : DumbAwareAction(
    "Select Device and Disconnect Others",
    "Choose one connected device and disconnect all others",
    null
) {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ProxyCommanderActionRunner.runKeepSelected(project)
    }
}

class ConnectActiveEmulatorClearOthersProxyAction : DumbAwareAction(
    "Connect One and Disconnect Others",
    "Enable reverse+proxy for active emulator and clear proxy on all other connected devices",
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

class StreamingToolbarContextTestAction : DumbAwareAction(
    "Show Current Device Context (Test)",
    "Show the serial/device resolved from Running Devices toolbar context",
    AllIcons.Actions.Help
) {
    override fun update(event: AnActionEvent) {
        event.presentation.isVisible = true
        event.presentation.isEnabled = ProxyCommanderStreamingContextResolver.extract(event) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val target = ProxyCommanderStreamingContextResolver.extract(event)
        val message = if (target != null) {
            "Resolved from ${target.source}: serial=${target.serial}, kind=${target.kind}"
        } else {
            "No device context found in place='${event.place}'."
        }
        Notifications.Bus.notify(
            Notification("ProxyCommander", "ProxyCommander Test", message, NotificationType.INFORMATION),
            project
        )
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
        runBulkWithEmulatorSummary(
            project = project,
            actionName = "Connect all devices"
        ) { controller, log ->
            controller.connectAllDevices(log)
        }
    }

    fun runDisconnectAll(project: Project) {
        runBulkWithEmulatorSummary(
            project = project,
            actionName = "Disconnect all devices"
        ) { controller, log ->
            controller.disconnectAllDevices(log)
        }
    }

    fun runKeepSelected(project: Project) {
        val config = ProxyCommanderSettingsService.getInstance(project).getConfig()
        ApplicationManager.getApplication().invokeLater {
            val dialog = KeepEmulatorDialog(project, config)
            if (!dialog.showAndGet()) {
                return@invokeLater
            }

            val selectedSerial = dialog.selectedSerial ?: return@invokeLater
            runInBackground(
                project = project,
                actionName = "Disconnect all devices except $selectedSerial"
            ) { controller, log ->
                controller.keepOnlyDevice(selectedSerial, log)
            }
        }
    }

    fun runConnectActiveEmulatorAndClearOthersProxy(project: Project, target: StreamingTarget?) {
        runInBackground(
            project = project,
            actionName = "Connect active emulator and clear proxy on other devices"
        ) { controller, log ->
            val activeSerial = resolveActiveEmulatorSerial(target, controller, log)
                ?: return@runInBackground false
            controller.connectEmulatorAndClearProxyOnOthers(activeSerial, log)
        }
    }

    fun runSettings(project: Project) {
        val settings = ProxyCommanderSettingsService.getInstance(project)
        val currentConfig = settings.getConfig()
        ApplicationManager.getApplication().invokeLater {
            val dialog = ProxyCommanderSettingsDialog(project, currentConfig)
            if (!dialog.showAndGet()) {
                return@invokeLater
            }

            settings.updateConfig(
                port = dialog.selectedPort(),
                adbPath = dialog.selectedAdbPath(),
                resetTimeOnConnect = dialog.isResetTimeOnConnect()
            )
            val adbSummary = dialog.selectedAdbPath().ifBlank { "<PATH or \$ADB>" }
            val resetSummary = if (dialog.isResetTimeOnConnect()) "on" else "off"
            notify(
                project = project,
                message = "Settings saved (port=${dialog.selectedPort()}, adb=$adbSummary, reset-time=$resetSummary).",
                type = NotificationType.INFORMATION
            )
        }
    }

    private fun runInBackground(
        project: Project,
        actionName: String,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        val config = ProxyCommanderSettingsService.getInstance(project).getConfig()
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, config)
            val success = runCatching {
                operation(controller, logs::add)
            }.getOrElse { error ->
                logs += "[ProxyCommander] Error: ${error.message}"
                false
            }

            val fallback = if (success) {
                "$actionName completed."
            } else {
                "$actionName failed."
            }
            notify(
                project = project,
                message = summarize(logs, fallback),
                type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
            )
        }
    }

    private fun runBulkWithEmulatorSummary(
        project: Project,
        actionName: String,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        val config = ProxyCommanderSettingsService.getInstance(project).getConfig()
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, config)
            val targetedEmulators = controller.listConnectedEmulators(logs::add)
            val success = runCatching {
                operation(controller, logs::add)
            }.getOrElse { error ->
                logs += "[ProxyCommander] Error: ${error.message}"
                false
            }

            val actionSummary = if (success) {
                "$actionName completed."
            } else {
                "$actionName failed."
            }
            val emulatorSummary = emulatorSummary(targetedEmulators)
            val base = summarize(logs, actionSummary)
            val message = "$base $emulatorSummary".trim()
            notify(
                project = project,
                message = message,
                type = if (success) NotificationType.INFORMATION else NotificationType.ERROR
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
            if (EMULATOR_SERIAL_REGEX.matches(targetSerial)) {
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

    private fun notify(project: Project, message: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification("ProxyCommander", "ProxyCommander", message, type),
                project
            )
        }
    }

    private val EMULATOR_SERIAL_REGEX = Regex("^emulator-[0-9]+$")
}

private class ProxyCommanderSettingsDialog(
    project: Project,
    currentConfig: ProxyCommanderConfig
) : DialogWrapper(project) {

    private val portField = JBTextField(currentConfig.port.toString())
    private val adbPathField = TextFieldWithBrowseButton()
    private val resetTimeCheckbox = JCheckBox(
        "Reset device clock on connect (forces NTP resync)",
        currentConfig.resetTimeOnConnect
    )
    private val resetDefaultsAction = object : AbstractAction("Reset to Defaults") {
        override fun actionPerformed(event: ActionEvent) {
            portField.text = ProxyCommanderSettingsService.DEFAULT_PORT.toString()
            adbPathField.text = ""
            resetTimeCheckbox.isSelected = true
        }
    }

    init {
        title = "Proxy Commander Settings"
        adbPathField.text = currentConfig.adbPath
        adbPathField.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                    .withTitle("Select adb Executable")
                    .withDescription("Choose adb executable from Android SDK platform-tools (optional)"),
                project
            )
        )
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Port:"), portField)
            .addLabeledComponent(JBLabel("ADB Path (optional):"), adbPathField)
            .addComponent(JBLabel("Leave ADB Path empty to use \$ADB or adb from PATH."))
            .addComponent(resetTimeCheckbox)
            .panel
        panel.preferredSize = Dimension(620, panel.preferredSize.height)
        panel.border = JBUI.Borders.empty(8)
        return panel
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(resetDefaultsAction)

    override fun doValidate(): ValidationInfo? {
        val port = portField.text.trim().toIntOrNull()
            ?: return ValidationInfo("Port must be a number.", portField)
        if (port !in 1..65535) {
            return ValidationInfo("Port must be between 1 and 65535.", portField)
        }

        val adbPath = adbPathField.text.trim()
        if (adbPath.isNotEmpty()) {
            val adbFile = File(adbPath)
            if (!adbFile.exists()) {
                return ValidationInfo("ADB path does not exist.", adbPathField)
            }
            if (!adbFile.isFile) {
                return ValidationInfo("ADB path must point to the adb executable file.", adbPathField)
            }
        }
        return null
    }

    fun selectedPort(): Int = portField.text.trim().toInt()

    fun selectedAdbPath(): String = adbPathField.text.trim()

    fun isResetTimeOnConnect(): Boolean = resetTimeCheckbox.isSelected
}

private class KeepEmulatorDialog(
    private val project: Project,
    private val config: ProxyCommanderConfig
) : DialogWrapper(project) {

    private val emulatorsPanel = JPanel()
    private val statusLabel = JBLabel("Loading connected emulators...")
    private val refreshButton = JButton("Refresh")

    var selectedSerial: String? = null
        private set

    init {
        title = "Select Emulator and Disconnect Others"
        init()
        reloadEmulators()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8))
        root.preferredSize = Dimension(640, 320)
        root.border = JBUI.Borders.empty(8)

        val topRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        topRow.add(JBLabel("Choose emulator to keep connected:"))
        topRow.add(refreshButton)
        refreshButton.addActionListener { reloadEmulators() }
        root.add(topRow, BorderLayout.NORTH)

        emulatorsPanel.layout = BoxLayout(emulatorsPanel, BoxLayout.Y_AXIS)
        emulatorsPanel.border = JBUI.Borders.empty(6)
        root.add(JBScrollPane(emulatorsPanel), BorderLayout.CENTER)
        root.add(statusLabel, BorderLayout.SOUTH)
        return root
    }

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    private fun reloadEmulators() {
        refreshButton.isEnabled = false
        statusLabel.text = "Loading connected emulators..."
        emulatorsPanel.removeAll()
        emulatorsPanel.add(JBLabel("Loading..."))
        emulatorsPanel.revalidate()
        emulatorsPanel.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            val emulators = runCatching {
                val controller = ProxyCommanderController(project, config)
                controller.listConnectedEmulators()
            }.getOrElse { error ->
                ApplicationManager.getApplication().invokeLater(
                    {
                        emulatorsPanel.removeAll()
                        emulatorsPanel.add(JBLabel("Failed to load emulators: ${error.message.orEmpty()}"))
                        emulatorsPanel.revalidate()
                        emulatorsPanel.repaint()
                        refreshButton.isEnabled = true
                        statusLabel.text = "Failed to load connected emulators."
                    },
                    ModalityState.any()
                )
                return@executeOnPooledThread
            }

            ApplicationManager.getApplication().invokeLater(
                {
                    renderEmulators(emulators)
                    refreshButton.isEnabled = true
                    statusLabel.text = when {
                        emulators.isEmpty() -> "No connected emulators."
                        else -> "Connected emulators: ${emulators.size}"
                    }
                },
                ModalityState.any()
            )
        }
    }

    private fun renderEmulators(emulators: List<ConnectedEmulator>) {
        emulatorsPanel.removeAll()
        if (emulators.isEmpty()) {
            emulatorsPanel.add(JBLabel("No connected emulators."))
        } else {
            emulators.forEach { emulator ->
                val row = JPanel(BorderLayout(8, 0))
                row.border = JBUI.Borders.empty(2, 0)
                row.alignmentX = 0f

                val labelText = "${emulator.avdName}  (${emulator.model})  [${emulator.serial}]"
                row.add(JBLabel(labelText), BorderLayout.CENTER)

                val keepButton = JButton("Keep This (Disconnect Others)")
                keepButton.addActionListener {
                    selectedSerial = emulator.serial
                    close(OK_EXIT_CODE)
                }
                row.add(keepButton, BorderLayout.EAST)
                row.maximumSize = Dimension(Int.MAX_VALUE, maxOf(row.preferredSize.height, 28))

                emulatorsPanel.add(row)
                emulatorsPanel.add(Box.createVerticalStrut(4))
            }
        }
        emulatorsPanel.revalidate()
        emulatorsPanel.repaint()
    }
}
