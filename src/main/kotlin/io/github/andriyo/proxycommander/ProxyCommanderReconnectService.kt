package io.github.andriyo.proxycommander

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class ReconnectSnapshotWork(
    val generation: Int,
    val serials: Set<String>
)

internal data class ReconnectAutoConnectWork(
    val generation: Int,
    val serial: String,
    val config: ProxyCommanderConfig
)

/**
 * Keeps reconnect work associated with the generation that produced it.
 *
 * Snapshot publication is serialized with generation changes, so an old callback can never update
 * the connected-count cache, notify listeners, or replace pending work after a new generation has
 * started. Workers take the generation from the pending value rather than from the runnable that
 * happens to drain it. Auto-connect suppression is generation/config-aware so replacement work is
 * not blocked by an obsolete attempt for the same serial.
 */
internal class ReconnectWorkState {
    private val disposed = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val snapshotPublicationLock = Any()
    private val latestSnapshot = AtomicReference<ReconnectSnapshotWork?>(null)
    private val pendingSnapshot = AtomicReference<ReconnectSnapshotWork?>(null)
    private val autoConnectInFlight = ConcurrentHashMap.newKeySet<ReconnectAutoConnectWork>()

    fun beginGeneration(): Int? = synchronized(snapshotPublicationLock) {
        if (disposed.get()) {
            null
        } else {
            generation.incrementAndGet()
        }
    }

    fun isCurrent(token: Int): Boolean =
        !disposed.get() && generation.get() == token

    fun isDisposed(): Boolean = disposed.get()

    fun publishSnapshot(
        token: Int,
        serials: Set<String>,
        onPublished: (Set<String>) -> Unit = {}
    ): Boolean = synchronized(snapshotPublicationLock) {
        if (!isCurrent(token)) {
            return@synchronized false
        }
        val published = ReconnectSnapshotWork(token, serials.toSet())
        latestSnapshot.set(published)
        pendingSnapshot.set(published)
        // Keep delivery in the same critical section. Otherwise an old publisher could pause after
        // committing, let a newer generation publish/deliver, then resume and deliver stale counts.
        onPublished(published.serials)
        true
    }

    fun latestSnapshotSerials(): Set<String> = latestSnapshot.get()?.serials.orEmpty()

    fun deliverLatestSnapshot(onDelivery: (Set<String>) -> Unit): Boolean =
        synchronized(snapshotPublicationLock) {
            if (disposed.get()) {
                return@synchronized false
            }
            onDelivery(latestSnapshot.get()?.serials.orEmpty())
            true
        }

    fun takeLatestSnapshot(): ReconnectSnapshotWork? = pendingSnapshot.getAndSet(null)

    fun tryStartAutoConnect(work: ReconnectAutoConnectWork): Boolean =
        isCurrent(work.generation) && autoConnectInFlight.add(work)

    fun finishAutoConnect(work: ReconnectAutoConnectWork) {
        autoConnectInFlight.remove(work)
    }

    fun dispose(): Boolean = synchronized(snapshotPublicationLock) {
        if (!disposed.compareAndSet(false, true)) {
            return@synchronized false
        }
        generation.incrementAndGet()
        pendingSnapshot.set(null)
        autoConnectInFlight.clear()
        true
    }
}

/** Thread-safe proxy-status state shared by snapshot and auto-connect workers. */
internal class ProxiedSerialState {
    private data class State(val revision: Long, val serials: Set<String>)

    private val nextRevision = AtomicLong(0)
    private val state = AtomicReference(State(revision = 0, serials = emptySet()))

    fun get(): Set<String> = state.get().serials

    /** Capture before starting a potentially slow adb status read. */
    fun beginObservation(): Long = nextRevision.incrementAndGet()

    fun replace(updated: Set<String>): Boolean = replace(beginObservation(), updated)

    fun replace(observationRevision: Long, updated: Set<String>): Boolean {
        val snapshot = updated.toSet()
        while (true) {
            val current = state.get()
            // A successful connect/disconnect that completed after this read began is newer truth.
            if (observationRevision < current.revision) {
                return false
            }
            if (state.compareAndSet(current, State(observationRevision, snapshot))) {
                return current.serials != snapshot
            }
        }
    }

    fun markConnected(serial: String): Boolean {
        val revision = beginObservation()
        while (true) {
            val current = state.get()
            if (revision < current.revision) {
                return false
            }
            val updated = current.serials + serial
            if (state.compareAndSet(current, State(revision, updated))) {
                return current.serials != updated
            }
        }
    }

    fun markDisconnected(serial: String): Boolean {
        val revision = beginObservation()
        while (true) {
            val current = state.get()
            if (revision < current.revision) {
                return false
            }
            val updated = current.serials - serial
            if (state.compareAndSet(current, State(revision, updated))) {
                return current.serials != updated
            }
        }
    }
}

@Service(Service.Level.APP)
class ProxyCommanderReconnectService : Disposable {
    private val workState = ReconnectWorkState()
    private val lastErrorMessage = AtomicReference<String?>(null)
    private val knownConnectedSerials = AtomicReference<Set<String>>(emptySet())
    private val baselineEstablished = AtomicBoolean(false)

    private val proxiedSerialState = ProxiedSerialState()
    private val listeners = CopyOnWriteArrayList<DevicesListener>()

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
        if (workState.isDisposed()) {
            return
        }
        listeners.add(listener)
        if (!workState.deliverLatestSnapshot(listener::onConnectedDevicesChanged)) {
            listeners.remove(listener)
        }
    }

    fun removeListener(listener: DevicesListener) {
        listeners.remove(listener)
    }

    fun connectedSerials(): Set<String> = workState.latestSnapshotSerials()

    /** Serials whose proxy + reverse mapping were confirmed by a snapshot or successful operation. */
    fun proxiedSerials(): Set<String> = proxiedSerialState.get()

    fun refreshTracking() {
        val token = workState.beginGeneration() ?: return

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
                if (isCurrent(token, config)) {
                    notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
                }
                if (!sleepWhileCurrent(token, ADB_UNAVAILABLE_RETRY_DELAY_MS)) {
                    return
                }
                continue
            }

            if (!isCurrent(token, config)) {
                return
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

            if (!isCurrent(token)) {
                return
            }
            if (!finished) {
                notifyAutoReconnectError(summarize(logs, "Stopped watching connected devices."))
            }

            if (!sleepWhileCurrent(token, WATCH_RETRY_DELAY_MS)) {
                return
            }
        }
    }

    private fun onSnapshotFast(token: Int, snapshot: Set<String>) {
        if (!workState.publishSnapshot(token, snapshot) { fireDevicesChanged() }) {
            return
        }
        if (snapshotExecutor.isShutdown) {
            return
        }
        runCatching {
            snapshotExecutor.execute {
                // Do not capture `token` here: this runnable may have been queued by an older
                // generation but drain a newer generation's coalesced snapshot.
                val pending = workState.takeLatestSnapshot() ?: return@execute
                if (isCurrent(pending.generation)) {
                    processSnapshot(pending.generation, pending.serials)
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

        if (snapshotSerials.isEmpty()) {
            updateProxiedSerials(proxiedSerialState.beginObservation(), emptySet())
            return
        }

        val settings = ProxyCommanderSettingsService.getInstance()
        val remembered = settings.getRememberedDeviceIds()
        val ignored = settings.getIgnoredDeviceIds()
        val config = settings.getConfig()

        val logs = mutableListOf<String>()
        val controller = ProxyCommanderController(currentBasePath(), config)
        if (!controller.ensureAdbAvailable(logs::add)) {
            if (isCurrent(token, config)) {
                notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
            }
            return
        }

        // Details are read on every snapshot (not only when remembered/new devices exist) so the
        // status bar's proxied count stays accurate.
        val proxyObservation = proxiedSerialState.beginObservation()
        val details = controller.listConnectedDeviceDetails(logs::add)
        if (!isCurrent(token, config)) {
            return
        }
        updateProxiedSerials(
            proxyObservation,
            details.filter { it.isProxyConnected }.map { it.serial }.toSet()
        )

        details.filter { it.identifier in remembered && !it.isProxyConnected }
            .forEach { device ->
                scheduleAutoConnect(device.serial, token, config)
            }

        if (newSerials.isNotEmpty()) {
            details.filter {
                it.serial in newSerials &&
                    it.identifier !in remembered &&
                    it.identifier !in ignored &&
                    !it.isProxyConnected
            }.forEach { device ->
                offerConnectToDevice(device, token)
            }
        }
    }

    private fun updateProxiedSerials(observationRevision: Long, serials: Set<String>) {
        if (proxiedSerialState.replace(observationRevision, serials)) {
            fireDevicesChanged()
        }
    }

    private fun markProxyConnected(serial: String) {
        if (proxiedSerialState.markConnected(serial)) {
            fireDevicesChanged()
        }
    }

    private fun markProxyDisconnected(serial: String) {
        if (proxiedSerialState.markDisconnected(serial)) {
            fireDevicesChanged()
        }
    }

    private fun scheduleAutoConnect(serial: String, token: Int, config: ProxyCommanderConfig) {
        val work = ReconnectAutoConnectWork(token, serial, config)
        if (connectExecutor.isShutdown || !workState.tryStartAutoConnect(work)) {
            return
        }
        runCatching {
            connectExecutor.execute {
                try {
                    if (isCurrent(work)) {
                        autoConnectRememberedDevice(work)
                    }
                } finally {
                    workState.finishAutoConnect(work)
                }
            }
        }.onFailure { workState.finishAutoConnect(work) }
    }

    private fun offerConnectToDevice(device: ConnectedDeviceDetails, token: Int) {
        val identifier = device.identifier
        val serial = device.serial
        val name = device.name.ifBlank { identifier }

        ApplicationManager.getApplication().invokeLater {
            if (!isCurrent(token)) {
                return@invokeLater
            }
            val notification = ProxyCommanderNotifications.create(
                "New device available: $name. Connect it to the proxy?",
                NotificationType.INFORMATION
            )
            notification.addAction(NotificationAction.createSimple("Connect to Proxy") {
                notification.expire()
                connectAndRememberDevice(serial, identifier, name)
            })
            notification.addAction(NotificationAction.createSimple("Don't Offer Again") {
                notification.expire()
                ProxyCommanderMutationCoordinator.execute {
                    ProxyCommanderSettingsService.getInstance().ignoreDevice(identifier)
                }
            })
            Notifications.Bus.notify(notification)
        }
    }

    private fun connectAndRememberDevice(serial: String, identifier: String, name: String) {
        if (workState.isDisposed()) {
            return
        }
        ProxyCommanderExecution.runControllerOperation(
            projectBasePath = currentBasePath(),
            config = ProxyCommanderSettingsService.getInstance().getConfig(),
            operation = { controller, log ->
                val connected = controller.connectDevice(serial, log)
                if (connected) {
                    ProxyCommanderSettingsService.getInstance()
                        .rememberDevices(listOf(RememberedDevice(identifier, name)))
                }
                connected
            }
        ) { success, logs ->
            if (workState.isDisposed()) {
                return@runControllerOperation
            }
            if (success) {
                clearAutoReconnectError()
                markProxyConnected(serial)
                refreshTracking()
                notifyAutoReconnectSuccess("Connected proxy to $name and enabled auto-connect.")
            } else {
                notifyAutoReconnectError("Failed to connect proxy to $name: ${summarize(logs, "Unknown error.")}")
            }
        }
    }

    private fun autoConnectRememberedDevice(work: ReconnectAutoConnectWork) {
        repeat(AUTO_CONNECT_ATTEMPTS) { attempt ->
            if (!isCurrent(work)) {
                return
            }

            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(currentBasePath(), work.config)
            if (!controller.ensureAdbAvailable(logs::add)) {
                if (isCurrent(work)) {
                    notifyAutoReconnectError(summarize(logs, "ADB command is not available."))
                }
                return
            }

            if (!isCurrent(work)) {
                return
            }
            if (!controller.waitForDeviceReady(work.serial, DEVICE_READY_TIMEOUT_MS, logs::add)) {
                if (isCurrent(work)) {
                    notifyAutoReconnectError("Auto-connect failed for ${work.serial}: ${summarize(logs, "Device was not ready in time.")}")
                }
                return
            }

            // Device readiness may wait for up to a minute. Re-check both generation and the exact
            // config immediately afterwards so an obsolete port is never applied after a refresh.
            if (!isCurrent(work)) {
                return
            }
            val resolvedDevice = controller.listConnectedDeviceDetails(logs::add)
                .firstOrNull { it.serial == work.serial }
            if (!isCurrent(work)) {
                return
            }
            val identifier = resolvedDevice?.identifier ?: work.serial

            // Re-check desired state inside the same mutation lock used by manual actions. If
            // settings change while adb commands run, roll back before a newer action can apply its
            // own proxy; this prevents stale cleanup from erasing a newer valid connection.
            val connected = ProxyCommanderMutationCoordinator.run {
                if (!isDesired(work, identifier)) {
                    false
                } else if (!controller.connectDevice(work.serial, logs::add)) {
                    false
                } else if (!isDesired(work, identifier)) {
                    val rolledBack = controller.disconnectDevice(
                        work.serial,
                        verifyInternet = false,
                        log = logs::add
                    )
                    if (rolledBack) {
                        markProxyDisconnected(work.serial)
                    } else {
                        // Do not claim the device is disconnected when cleanup could not be verified.
                        refreshTracking()
                    }
                    false
                } else {
                    true
                }
            }
            if (connected) {
                clearAutoReconnectError()
                // Reflect the successful mutation before the optional host-side connection test,
                // which can perform more adb/process work and should not keep the widget stale.
                markProxyConnected(work.serial)
                val testPassed = controller.testProxyConnection(work.serial, logs::add)
                if (!isDesired(work, identifier)) {
                    // A newer generation/manual action owns the desired state now. It is serialized
                    // by the coordinator and must not be undone by this older worker.
                    return
                }
                if (testPassed) {
                    notifyAutoReconnectSuccess("Auto-connected proxy to $identifier and verified host proxy connection.")
                } else {
                    notifyAutoReconnectError("Auto-connect succeeded for $identifier, but connection test failed: ${summarize(logs, "Unknown error.")}")
                }
                return
            }

            if (attempt == AUTO_CONNECT_ATTEMPTS - 1) {
                if (isDesired(work, identifier)) {
                    notifyAutoReconnectError("Auto-connect failed for $identifier: ${summarize(logs, "Unknown error.")}")
                }
            } else if (!sleepWhileDesired(work, identifier, AUTO_CONNECT_RETRY_DELAY_MS)) {
                return
            }
        }
    }

    private fun fireDevicesChanged() {
        workState.deliverLatestSnapshot { connectedSerials ->
            listeners.forEach { listener ->
                runCatching { listener.onConnectedDevicesChanged(connectedSerials) }
            }
        }
    }

    private fun currentBasePath(): String? =
        ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }?.basePath

    private fun summarize(logs: List<String>, fallback: String): String =
        ProxyCommanderExecution.summarize(logs, fallback)

    private fun notifyAutoReconnectError(message: String) {
        if (workState.isDisposed() || message.isBlank()) {
            return
        }
        val previous = lastErrorMessage.getAndSet(message)
        if (previous == message) {
            return
        }
        if (workState.isDisposed()) {
            return
        }
        ProxyCommanderNotifications.notify(message, NotificationType.ERROR)
    }

    private fun clearAutoReconnectError() {
        lastErrorMessage.set(null)
    }

    private fun notifyAutoReconnectSuccess(message: String) {
        if (workState.isDisposed()) {
            return
        }
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

    private fun sleepWhileDesired(work: ReconnectAutoConnectWork, identifier: String, delayMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + delayMs
        while (isDesired(work, identifier) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return isDesired(work, identifier)
    }

    private fun isCurrent(token: Int): Boolean =
        workState.isCurrent(token)

    private fun isCurrent(token: Int, config: ProxyCommanderConfig): Boolean =
        isCurrent(token) && ProxyCommanderSettingsService.getInstance().getConfig() == config

    private fun isCurrent(work: ReconnectAutoConnectWork): Boolean =
        isCurrent(work.generation, work.config)

    private fun isDesired(work: ReconnectAutoConnectWork, identifier: String): Boolean =
        isCurrent(work) && identifier in ProxyCommanderSettingsService.getInstance().getRememberedDeviceIds()

    override fun dispose() {
        if (!workState.dispose()) {
            return
        }
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
