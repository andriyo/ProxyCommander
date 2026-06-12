package io.github.andriyo.proxycommander

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

internal object ProxyCommanderActionRunner {

    fun runConnectAll(project: Project) {
        val settings = ProxyCommanderSettingsService.getInstance()
        runWithNotification(
            project = project,
            actionName = "Connect proxy to all devices",
            includeEmulatorSummary = true
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

    fun runDisconnectAll(project: Project) {
        val settings = ProxyCommanderSettingsService.getInstance()
        runWithNotification(
            project = project,
            actionName = "Disconnect proxy from all devices",
            includeEmulatorSummary = true
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

    fun runConnectCurrentAndClearOthersProxy(project: Project, target: StreamingTarget?) {
        val settings = ProxyCommanderSettingsService.getInstance()
        runWithNotification(
            project = project,
            actionName = "Connect proxy to current device and disconnect proxy from other devices"
        ) { controller, log ->
            val activeSerial = resolveActiveDeviceSerial(target, controller, log)
                ?: return@runWithNotification false
            val success = controller.connectDeviceAndClearProxyOnOthers(activeSerial, log)
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

    private fun runWithNotification(
        project: Project,
        actionName: String,
        includeEmulatorSummary: Boolean = false,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean
    ) {
        val config = ProxyCommanderSettingsService.getInstance().getConfig()
        var targetedEmulators: List<ConnectedEmulator> = emptyList()
        var operationRan = false
        ProxyCommanderExecution.runControllerOperation(
            projectBasePath = project.basePath,
            config = config,
            operation = { controller, log ->
                operationRan = true
                if (includeEmulatorSummary) {
                    targetedEmulators = controller.listConnectedEmulators(log)
                }
                operation(controller, log)
            }
        ) { success, logs ->
            val fallback = if (success) "$actionName completed." else "$actionName failed."
            val base = ProxyCommanderExecution.summarize(logs, fallback)
            // The emulator summary only makes sense when adb was reachable and the operation ran.
            val message = if (includeEmulatorSummary && operationRan) {
                "$base ${emulatorSummary(targetedEmulators)}".trim()
            } else {
                base
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
                (duplicateNameCounts[emulator.avdName] ?: 0) > 1 -> "${emulator.avdName} [${emulator.serial}]"
                else -> emulator.avdName
            }
        }
        return "Emulators: ${names.joinToString(", ")}."
    }

    private fun resolveActiveDeviceSerial(
        target: StreamingTarget?,
        controller: ProxyCommanderController,
        log: (String) -> Unit
    ): String? {
        val targetSerial = target?.serial?.trim().orEmpty()
        if (targetSerial.isNotEmpty()) {
            log("[ProxyCommander] Active device from ${target?.source ?: "unknown source"}: $targetSerial")
            return targetSerial
        }

        val devices = controller.listConnectedDevices(log)
        if (devices.size == 1) {
            val device = devices.single()
            log("[ProxyCommander] Active device context unavailable; using the only connected device ${device.serial}.")
            return device.serial
        }

        // With several devices attached, a lone emulator is still an unambiguous "current" target.
        val emulators = devices.filter { it.isEmulator }
        if (emulators.size == 1) {
            val emulator = emulators.single()
            log("[ProxyCommander] Active device context unavailable; using the only connected emulator ${emulator.serial}.")
            return emulator.serial
        }

        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices found.")
        } else {
            log("[ProxyCommander] Unable to determine the active device from context. Connected devices: ${devices.joinToString(", ") { it.serial }}.")
        }
        return null
    }
}
