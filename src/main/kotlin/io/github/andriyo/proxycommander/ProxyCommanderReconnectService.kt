package io.github.andriyo.proxycommander

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class ProxyCommanderReconnectService(private val project: Project) {
    private val disposed = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val lastErrorMessage = AtomicReference<String?>(null)
    private val knownConnectedSerials = AtomicReference<Set<String>>(emptySet())
    private val baselineEstablished = AtomicBoolean(false)

    init {
        Disposer.register(project, DisposableHandle())
    }

    fun refreshTracking() {
        val token = generation.incrementAndGet()
        if (disposed.get() || project.isDisposed) {
            return
        }

        // The watcher always runs while the project is open: it auto-reconnects remembered
        // devices and offers to connect newly-appeared devices that are not yet remembered.
        ApplicationManager.getApplication().executeOnPooledThread {
            runTrackingLoop(token)
        }
    }

    private fun runTrackingLoop(token: Int) {
        while (isCurrent(token)) {
            val settings = ProxyCommanderSettingsService.getInstance(project)
            val config = settings.getConfig()
            val controller = ProxyCommanderController(project, config)
            val logs = mutableListOf<String>()
            if (!controller.ensureAdbAvailable(logs::add)) {
                notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
                if (!sleepWhileCurrent(token, ADB_UNAVAILABLE_RETRY_DELAY_MS)) {
                    return
                }
                continue
            }

            clearAutoReconnectError()
            val finished = controller.watchConnectedDevices(
                log = logs::add,
                shouldStop = {
                    !isCurrent(token) ||
                        ProxyCommanderSettingsService.getInstance(project).getConfig() != config
                },
                onSnapshot = { snapshot ->
                    onDevicesSnapshot(token, snapshot)
                }
            )

            if (!finished) {
                notifyAutoReconnectError(summarize(logs, "Stopped watching connected devices."))
            }

            if (!sleepWhileCurrent(token, WATCH_RETRY_DELAY_MS)) {
                return
            }
        }
    }

    private fun onDevicesSnapshot(token: Int, snapshotSerials: Set<String>) {
        if (!isCurrent(token)) {
            return
        }

        val previouslyKnown = knownConnectedSerials.getAndSet(snapshotSerials)
        val isBaseline = !baselineEstablished.getAndSet(true)
        // Devices already connected when tracking first starts form the baseline and are not
        // treated as "newly appeared", so the IDE does not flood the user with offers at startup.
        val newSerials = if (isBaseline) emptySet() else snapshotSerials - previouslyKnown

        val settings = ProxyCommanderSettingsService.getInstance(project)
        val remembered = settings.getRememberedDeviceIds()
        if (remembered.isEmpty() && newSerials.isEmpty()) {
            return
        }

        val logs = mutableListOf<String>()
        val controller = ProxyCommanderController(project, settings.getConfig())
        if (!controller.ensureAdbAvailable(logs::add)) {
            notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
            return
        }

        val details = controller.listConnectedDeviceDetails(logs::add)

        details.filter { it.identifier in remembered && !it.isProxyConnected }
            .forEach { device ->
                autoConnectRememberedDevice(device.serial, token)
            }

        if (newSerials.isNotEmpty()) {
            details.filter { it.serial in newSerials && it.identifier !in remembered && !it.isProxyConnected }
                .forEach { device ->
                    offerConnectToDevice(device)
                }
        }
    }

    private fun offerConnectToDevice(device: ConnectedDeviceDetails) {
        val identifier = device.identifier
        val serial = device.serial
        val name = device.name.ifBlank { identifier }

        ApplicationManager.getApplication().invokeLater {
            val notification = Notification(
                "ProxyCommander",
                "ProxyCommander",
                "New device available: $name. Connect it to the proxy?",
                NotificationType.INFORMATION
            )
            notification.addAction(NotificationAction.createSimple("Connect to Proxy") {
                notification.expire()
                connectAndRememberDevice(serial, identifier, name)
            })
            Notifications.Bus.notify(notification, project)
        }
    }

    private fun connectAndRememberDevice(serial: String, identifier: String, name: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = ProxyCommanderSettingsService.getInstance(project)
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(project, settings.getConfig())
            if (!controller.ensureAdbAvailable(logs::add)) {
                notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
                return@executeOnPooledThread
            }

            if (controller.connectDevice(serial, logs::add)) {
                settings.rememberDevices(listOf(RememberedDevice(identifier, name)))
                clearAutoReconnectError()
                refreshTracking()
                notifyAutoReconnectSuccess("Connected proxy to $name and enabled auto-connect.")
            } else {
                notifyAutoReconnectError("Failed to connect proxy to $name: ${summarize(logs, "Unknown error.")}")
            }
        }
    }

    private fun autoConnectRememberedDevice(serial: String, token: Int) {
        repeat(AUTO_CONNECT_ATTEMPTS) { attempt ->
            if (!isCurrent(token)) {
                return
            }

            val logs = mutableListOf<String>()
            val settings = ProxyCommanderSettingsService.getInstance(project)
            val controller = ProxyCommanderController(project, settings.getConfig())
            if (!controller.ensureAdbAvailable(logs::add)) {
                notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
                return
            }

            if (!controller.waitForDeviceReady(serial, DEVICE_READY_TIMEOUT_MS, logs::add)) {
                notifyAutoReconnectError("Auto-connect failed for $serial: ${summarize(logs, "Device was not ready in time.")}")
                return
            }

            val resolvedDevice = controller.listConnectedDeviceDetails(logs::add).firstOrNull { it.serial == serial }
            val identifier = resolvedDevice?.identifier ?: serial
            if (identifier !in settings.getRememberedDeviceIds()) {
                return
            }

            if (controller.connectDevice(serial, logs::add)) {
                clearAutoReconnectError()
                val testPassed = controller.testProxyConnection(serial, logs::add)
                if (testPassed) {
                    notifyAutoReconnectSuccess("Auto-connected proxy to $identifier and verified host proxy connection.")
                } else {
                    notifyAutoReconnectError("Auto-connect succeeded for $identifier, but connection test failed: ${summarize(logs, "Unknown error.")}")
                }
                return
            }

            if (attempt == AUTO_CONNECT_ATTEMPTS - 1) {
                notifyAutoReconnectError("Auto-connect failed for $identifier: ${summarize(logs, "Unknown error.")}")
            } else if (!sleepWhileCurrent(token, AUTO_CONNECT_RETRY_DELAY_MS)) {
                return
            }
        }
    }

    private fun summarize(logs: List<String>, fallback: String): String {
        val lastLog = logs.lastOrNull { it.isNotBlank() }?.removePrefix("[ProxyCommander] ")?.trim()
        return lastLog.takeUnless { it.isNullOrBlank() } ?: fallback
    }

    private fun notifyAutoReconnectError(message: String) {
        if (message.isBlank()) {
            return
        }
        val previous = lastErrorMessage.getAndSet(message)
        if (previous == message) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification("ProxyCommander", "ProxyCommander", message, NotificationType.ERROR),
                project
            )
        }
    }

    private fun clearAutoReconnectError() {
        lastErrorMessage.set(null)
    }

    private fun notifyAutoReconnectSuccess(message: String) {
        if (message.isBlank()) {
            return
        }

        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(
                Notification("ProxyCommander", "ProxyCommander", message, NotificationType.INFORMATION),
                project
            )
        }
    }

    private fun sleepWhileCurrent(token: Int, delayMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + delayMs
        while (isCurrent(token) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return isCurrent(token)
    }

    private fun isCurrent(token: Int): Boolean =
        !disposed.get() && !project.isDisposed && generation.get() == token

    private inner class DisposableHandle : com.intellij.openapi.Disposable {
        override fun dispose() {
            disposed.set(true)
            generation.incrementAndGet()
        }
    }

    companion object {
        private const val AUTO_CONNECT_ATTEMPTS = 3
        private const val AUTO_CONNECT_RETRY_DELAY_MS = 1_000L
        private const val ADB_UNAVAILABLE_RETRY_DELAY_MS = 5_000L
        private const val DEVICE_READY_TIMEOUT_MS = 60_000L
        private const val WATCH_RETRY_DELAY_MS = 1_000L

        fun getInstance(project: Project): ProxyCommanderReconnectService =
            project.getService(ProxyCommanderReconnectService::class.java)
    }
}
