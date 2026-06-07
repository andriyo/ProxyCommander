package io.github.andriyo.proxycommander

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
class ProxyCommanderReconnectService : Disposable {
    private val disposed = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val lastErrorMessage = AtomicReference<String?>(null)
    private val knownConnectedSerials = AtomicReference<Set<String>>(emptySet())
    private val baselineEstablished = AtomicBoolean(false)

    private val latestSnapshot = AtomicReference<Set<String>>(emptySet())
    private val pendingSnapshot = AtomicReference<Set<String>?>(null)
    private val listeners = CopyOnWriteArrayList<DevicesListener>()
    private val autoConnectInFlight = Collections.synchronizedSet(HashSet<String>())

    // The track-devices read loop must never block, so all adb-heavy work runs off it:
    // snapshots are processed one-at-a-time (newest wins) and auto-connect — which can wait up to
    // a minute for a booting device — is serialized on its own executor.
    private val snapshotExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
        "ProxyCommander-Snapshot", 1
    )
    private val connectExecutor = AppExecutorUtil.createBoundedApplicationPoolExecutor(
        "ProxyCommander-AutoConnect", 1
    )

    /** Notified (on a background thread) whenever the set of connected device serials changes. */
    fun interface DevicesListener {
        fun onConnectedDevicesChanged(connectedSerials: Set<String>)
    }

    fun addListener(listener: DevicesListener) {
        listeners.add(listener)
        listener.onConnectedDevicesChanged(latestSnapshot.get())
    }

    fun removeListener(listener: DevicesListener) {
        listeners.remove(listener)
    }

    fun connectedSerials(): Set<String> = latestSnapshot.get()

    fun refreshTracking() {
        val token = generation.incrementAndGet()
        if (disposed.get()) {
            return
        }

        // A single application-wide watcher auto-reconnects remembered devices and offers to connect
        // newly-appeared devices. Calling this again simply supersedes the previous loop via the
        // generation token, so opening multiple projects never spins up competing watchers.
        ApplicationManager.getApplication().executeOnPooledThread {
            runTrackingLoop(token)
        }
    }

    private fun runTrackingLoop(token: Int) {
        while (isCurrent(token)) {
            val settings = ProxyCommanderSettingsService.getInstance()
            val config = settings.getConfig()
            val controller = ProxyCommanderController(currentBasePath(), config)
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
                        ProxyCommanderSettingsService.getInstance().getConfig() != config
                },
                onSnapshot = { snapshot ->
                    onSnapshotFast(token, snapshot)
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

    private fun onSnapshotFast(token: Int, snapshot: Set<String>) {
        if (!isCurrent(token)) {
            return
        }
        latestSnapshot.set(snapshot)
        fireDevicesChanged(snapshot)

        pendingSnapshot.set(snapshot)
        if (snapshotExecutor.isShutdown) {
            return
        }
        runCatching {
            snapshotExecutor.execute {
                val pending = pendingSnapshot.getAndSet(null) ?: return@execute
                if (isCurrent(token)) {
                    processSnapshot(token, pending)
                }
            }
        }
    }

    private fun processSnapshot(token: Int, snapshotSerials: Set<String>) {
        if (!isCurrent(token)) {
            return
        }

        val previouslyKnown = knownConnectedSerials.getAndSet(snapshotSerials)
        val isBaseline = !baselineEstablished.getAndSet(true)
        // Devices already connected when tracking first starts form the baseline and are not
        // treated as "newly appeared", so the IDE does not flood the user with offers at startup.
        val newSerials = if (isBaseline) emptySet() else snapshotSerials - previouslyKnown

        val settings = ProxyCommanderSettingsService.getInstance()
        val remembered = settings.getRememberedDeviceIds()
        if (remembered.isEmpty() && newSerials.isEmpty()) {
            return
        }

        val logs = mutableListOf<String>()
        val controller = ProxyCommanderController(currentBasePath(), settings.getConfig())
        if (!controller.ensureAdbAvailable(logs::add)) {
            notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
            return
        }

        val details = controller.listConnectedDeviceDetails(logs::add)

        details.filter { it.identifier in remembered && !it.isProxyConnected }
            .forEach { device ->
                scheduleAutoConnect(device.serial, token)
            }

        if (newSerials.isNotEmpty()) {
            details.filter { it.serial in newSerials && it.identifier !in remembered && !it.isProxyConnected }
                .forEach { device ->
                    offerConnectToDevice(device)
                }
        }
    }

    private fun scheduleAutoConnect(serial: String, token: Int) {
        if (connectExecutor.isShutdown || !autoConnectInFlight.add(serial)) {
            return
        }
        runCatching {
            connectExecutor.execute {
                try {
                    if (isCurrent(token)) {
                        autoConnectRememberedDevice(serial, token)
                    }
                } finally {
                    autoConnectInFlight.remove(serial)
                }
            }
        }.onFailure { autoConnectInFlight.remove(serial) }
    }

    private fun offerConnectToDevice(device: ConnectedDeviceDetails) {
        val identifier = device.identifier
        val serial = device.serial
        val name = device.name.ifBlank { identifier }

        ApplicationManager.getApplication().invokeLater {
            val notification = ProxyCommanderNotifications.create(
                "New device available: $name. Connect it to the proxy?",
                NotificationType.INFORMATION
            )
            notification.addAction(NotificationAction.createSimple("Connect to Proxy") {
                notification.expire()
                connectAndRememberDevice(serial, identifier, name)
            })
            Notifications.Bus.notify(notification)
        }
    }

    private fun connectAndRememberDevice(serial: String, identifier: String, name: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = ProxyCommanderSettingsService.getInstance()
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(currentBasePath(), settings.getConfig())
            if (!controller.ensureAdbAvailable(logs::add)) {
                notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
                return@executeOnPooledThread
            }

            if (controller.connectDevice(serial, logs::add)) {
                settings.rememberDevices(listOf(RememberedDevice(identifier, name)))
                clearAutoReconnectError()
                fireDevicesChanged(latestSnapshot.get())
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
            val settings = ProxyCommanderSettingsService.getInstance()
            val controller = ProxyCommanderController(currentBasePath(), settings.getConfig())
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
                fireDevicesChanged(latestSnapshot.get())
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

    private fun fireDevicesChanged(connectedSerials: Set<String>) {
        listeners.forEach { listener ->
            runCatching { listener.onConnectedDevicesChanged(connectedSerials) }
        }
    }

    private fun currentBasePath(): String? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }?.basePath

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
        ProxyCommanderNotifications.notify(message, NotificationType.ERROR)
    }

    private fun clearAutoReconnectError() {
        lastErrorMessage.set(null)
    }

    private fun notifyAutoReconnectSuccess(message: String) {
        ProxyCommanderNotifications.notify(message, NotificationType.INFORMATION)
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
        !disposed.get() && generation.get() == token

    override fun dispose() {
        disposed.set(true)
        generation.incrementAndGet()
        snapshotExecutor.shutdownNow()
        connectExecutor.shutdownNow()
        listeners.clear()
    }

    companion object {
        private const val AUTO_CONNECT_ATTEMPTS = 3
        private const val AUTO_CONNECT_RETRY_DELAY_MS = 1_000L
        private const val ADB_UNAVAILABLE_RETRY_DELAY_MS = 5_000L
        private const val DEVICE_READY_TIMEOUT_MS = 60_000L
        private const val WATCH_RETRY_DELAY_MS = 1_000L

        fun getInstance(): ProxyCommanderReconnectService =
            ApplicationManager.getApplication().getService(ProxyCommanderReconnectService::class.java)
    }
}
