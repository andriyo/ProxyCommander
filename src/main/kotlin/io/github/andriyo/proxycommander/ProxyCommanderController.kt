package io.github.andriyo.proxycommander

import com.intellij.openapi.project.Project
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

internal data class ProxyCommanderConfig(
    val port: Int = ProxyCommanderSettingsService.DEFAULT_PORT,
    val adbPath: String = "",
    val resetTimeOnConnect: Boolean = true,
    /** Ports the plugin used before the current one; their reverse mappings get cleaned up. */
    val previousPorts: Set<Int> = emptySet()
)

internal data class ConnectedDevice(
    val serial: String,
    val isEmulator: Boolean
)

internal data class ConnectedDeviceDetails(
    val identifier: String,
    val serial: String,
    val name: String,
    val apiLevel: String,
    val isEmulator: Boolean,
    val isProxyConnected: Boolean
)

internal data class ConnectedEmulator(
    val serial: String,
    val avdName: String,
    val model: String
)

internal data class DeviceIsolationResult(
    val selectedConnected: Boolean,
    val cleanupSucceeded: Boolean
) {
    val success: Boolean
        get() = selectedConnected && cleanupSucceeded
}

private data class DeviceDisconnectResult(
    val cleanupSucceeded: Boolean,
    val internetVerified: Boolean?
)

internal class ProxyCommanderController internal constructor(
    private val adbClient: AdbCommander,
    private val config: ProxyCommanderConfig
) {
    constructor(project: Project, config: ProxyCommanderConfig) : this(
        adbClient = AdbClient(project.basePath?.let(::File), config.adbPath),
        config = config
    )

    internal constructor(projectBasePath: String?, config: ProxyCommanderConfig) : this(
        adbClient = AdbClient(projectBasePath?.let(::File), config.adbPath),
        config = config
    )

    private val reverseToken = "tcp:${config.port}"
    private val desiredProxy = "localhost:${config.port}"

    private enum class ReverseEnableStatus {
        ALREADY_ENABLED,
        NEWLY_ENABLED,
        FAILED
    }

    fun ensureAdbAvailable(log: (String) -> Unit): Boolean {
        val result = adbClient.checkAvailability()
        if (!result.success) {
            log("[ProxyCommander] ADB availability check failed (exit ${result.exitCode}): ${result.briefOutput()}")
            return false
        }
        return true
    }

    fun listConnectedDevices(log: (String) -> Unit = {}): List<ConnectedDevice> {
        return queryConnectedDevices(log).devices
    }

    private fun queryConnectedDevices(log: (String) -> Unit): ConnectedDeviceQuery {
        startAdbServer()
        val result = adbClient.run(args = listOf("devices"), allowFailure = true)
        if (!result.success) {
            log("[ProxyCommander] Failed to list connected devices: ${result.briefOutput()}")
            return ConnectedDeviceQuery(success = false, devices = emptyList())
        }

        return ConnectedDeviceQuery(
            success = true,
            devices = ProxyCommanderParsing.parseDevices(result.output)
        )
    }

    fun listConnectedEmulators(log: (String) -> Unit = {}): List<ConnectedEmulator> {
        val emulators = listConnectedDevices(log).filter { it.isEmulator }
        return emulators.map { device ->
            val props = readDeviceProps(device.serial)
            ConnectedEmulator(
                serial = device.serial,
                avdName = readStableEmulatorName(device.serial, props) ?: "Unknown AVD",
                model = readModel(props)
            )
        }
    }

    fun listConnectedDeviceDetails(log: (String) -> Unit = {}): List<ConnectedDeviceDetails> {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            return emptyList()
        }
        if (devices.size == 1) {
            return listOf(readConnectedDeviceDetails(devices.single()))
        }

        // Each device costs several adb round trips; reading them in parallel keeps the Devices
        // dialog and snapshot processing responsive when several devices are attached.
        val executor = Executors.newFixedThreadPool(minOf(devices.size, MAX_DETAIL_READ_THREADS))
        return try {
            devices
                .map { device -> device to executor.submit(Callable { readConnectedDeviceDetails(device) }) }
                .map { (device, future) ->
                    runCatching { future.get() }.getOrElse {
                        ConnectedDeviceDetails(
                            identifier = device.serial,
                            serial = device.serial,
                            name = device.serial,
                            apiLevel = "?",
                            isEmulator = device.isEmulator,
                            isProxyConnected = false
                        )
                    }
                }
        } finally {
            executor.shutdown()
        }
    }

    private fun readConnectedDeviceDetails(device: ConnectedDevice): ConnectedDeviceDetails {
        // One `getprop` dump per device instead of a separate adb call for each property.
        val props = readDeviceProps(device.serial)
        val identifier = readDeviceIdentifier(device, props)
        return ConnectedDeviceDetails(
            identifier = identifier,
            serial = device.serial,
            name = readDeviceDisplayName(device, identifier, props),
            apiLevel = readApiLevel(props),
            isEmulator = device.isEmulator,
            isProxyConnected = isProxyConnected(device.serial)
        )
    }

    fun connectAllDevices(log: (String) -> Unit): Boolean {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return false
        }

        log("[ProxyCommander] Connecting ${devices.size} device(s) on $reverseToken")
        var failed = false
        devices.forEach { device ->
            if (!connectSerial(device.serial, log)) {
                failed = true
            }
        }

        if (failed) {
            log("[ProxyCommander] Connect finished with errors on one or more devices.")
        } else {
            log("[ProxyCommander] Connect completed successfully for all devices.")
        }
        return !failed
    }

    fun connectDevice(serial: String, log: (String) -> Unit): Boolean {
        val exists = listConnectedDevices(log).any { it.serial == serial }
        if (!exists) {
            log("[ProxyCommander] Device '$serial' is not connected.")
            return false
        }
        return connectSerial(serial, log)
    }

    fun disconnectDevice(serial: String, log: (String) -> Unit): Boolean =
        disconnectDevice(serial, verifyInternet = true, log = log)

    fun disconnectDevice(
        serial: String,
        verifyInternet: Boolean,
        log: (String) -> Unit
    ): Boolean {
        val exists = listConnectedDevices(log).any { it.serial == serial }
        if (!exists) {
            log("[ProxyCommander] Device '$serial' is not connected.")
            return false
        }
        return disconnectSerial(serial, log, verifyInternet).cleanupSucceeded
    }

    fun disconnectAllDevices(log: (String) -> Unit): Boolean {
        val query = queryConnectedDevices(log)
        if (!query.success) {
            return false
        }

        val devices = query.devices
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices to disconnect.")
            return true
        }

        log("[ProxyCommander] Disconnecting ${devices.size} device(s) on $reverseToken")
        var failed = false
        val internetUnverified = mutableListOf<String>()
        devices.forEach { device ->
            val result = disconnectSerial(device.serial, log)
            if (!result.cleanupSucceeded) {
                failed = true
            } else if (result.internetVerified == false) {
                internetUnverified += device.serial
            }
        }

        if (failed) {
            log("[ProxyCommander] Disconnect finished with errors on one or more devices.")
        } else if (internetUnverified.isNotEmpty()) {
            log("[ProxyCommander] Disconnect completed, but direct internet access could not be verified for: ${internetUnverified.joinToString()}.")
        } else {
            log("[ProxyCommander] Disconnect completed successfully and direct internet access was verified for all devices.")
        }
        return !failed
    }

    fun keepOnlyDevice(selectedSerial: String, log: (String) -> Unit): Boolean =
        keepOnlyDeviceWithOutcome(selectedSerial, log).success

    fun keepOnlyDeviceWithOutcome(selectedSerial: String, log: (String) -> Unit): DeviceIsolationResult {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return DeviceIsolationResult(selectedConnected = false, cleanupSucceeded = false)
        }
        if (devices.none { it.serial == selectedSerial }) {
            log("[ProxyCommander] Selected device '$selectedSerial' is not connected.")
            return DeviceIsolationResult(selectedConnected = false, cleanupSucceeded = false)
        }

        if (!connectSerial(selectedSerial, log)) {
            log("[ProxyCommander] Failed to keep selected device '$selectedSerial' connected.")
            return DeviceIsolationResult(selectedConnected = false, cleanupSucceeded = false)
        }

        val others = devices.filterNot { it.serial == selectedSerial }
        if (others.isEmpty()) {
            log("[ProxyCommander] Selected device '$selectedSerial' is the only connected device.")
            return DeviceIsolationResult(selectedConnected = true, cleanupSucceeded = true)
        }

        var failed = false
        val internetUnverified = mutableListOf<String>()
        others.forEach { device ->
            val result = disconnectSerial(device.serial, log)
            if (!result.cleanupSucceeded) {
                failed = true
            } else if (result.internetVerified == false) {
                internetUnverified += device.serial
            }
        }
        when {
            failed -> log("[ProxyCommander] Selected device connected, but one or more other devices could not be fully disconnected.")
            internetUnverified.isNotEmpty() ->
                log("[ProxyCommander] Selected device connected and other devices were unproxied, but direct internet access could not be verified for: ${internetUnverified.joinToString()}.")
            else ->
                log("[ProxyCommander] Selected device connected; other devices were unproxied and their direct internet access was verified.")
        }
        return DeviceIsolationResult(selectedConnected = true, cleanupSucceeded = !failed)
    }

    fun connectDeviceAndClearProxyOnOthers(activeSerial: String, log: (String) -> Unit): Boolean =
        connectDeviceAndClearProxyOnOthersWithOutcome(activeSerial, log).success

    fun connectDeviceAndClearProxyOnOthersWithOutcome(
        activeSerial: String,
        log: (String) -> Unit
    ): DeviceIsolationResult {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return DeviceIsolationResult(selectedConnected = false, cleanupSucceeded = false)
        }

        val activeDevice = devices.firstOrNull { it.serial == activeSerial }
        if (activeDevice == null) {
            log("[ProxyCommander] Active device '$activeSerial' is not connected.")
            return DeviceIsolationResult(selectedConnected = false, cleanupSucceeded = false)
        }

        if (!connectSerial(activeSerial, log)) {
            log("[ProxyCommander] Active device '$activeSerial' could not be connected; proxies on other devices were left unchanged.")
            return DeviceIsolationResult(selectedConnected = false, cleanupSucceeded = false)
        }

        val others = devices.filterNot { it.serial == activeSerial }
        if (others.isEmpty()) {
            log("[ProxyCommander] Active device '$activeSerial' is the only connected device.")
            return DeviceIsolationResult(selectedConnected = true, cleanupSucceeded = true)
        }

        var failed = false
        val internetUnverified = mutableListOf<String>()
        log("[ProxyCommander] Clearing proxy on ${others.size} other connected device(s).")
        others.forEach { device ->
            if (!clearDeviceProxy(device.serial, log)) {
                failed = true
            } else if (!verifyDirectInternetAccess(device.serial, log)) {
                internetUnverified += device.serial
            }
        }

        if (failed) {
            log("[ProxyCommander] Finished with errors while connecting active device and clearing proxies on others.")
        } else if (internetUnverified.isNotEmpty()) {
            log("[ProxyCommander] Active device connected and other proxies were cleared, but direct internet access could not be verified for: ${internetUnverified.joinToString()}.")
        } else {
            log("[ProxyCommander] Active device connected; other proxies were cleared and their direct internet access was verified.")
        }
        return DeviceIsolationResult(selectedConnected = true, cleanupSucceeded = !failed)
    }

    fun testProxyConnection(serial: String, log: (String) -> Unit): Boolean {
        val device = listConnectedDevices(log).firstOrNull { it.serial == serial }
        if (device == null) {
            log("[ProxyCommander] Device '$serial' is not connected.")
            return false
        }

        val currentProxy = readGlobalSetting(serial, "http_proxy")
        if (currentProxy != desiredProxy) {
            log("[ProxyCommander] Device '$serial' is not configured to use $desiredProxy (current='$currentProxy').")
            return false
        }

        val reverseListResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (!reverseListResult.success || !containsReverseMapping(reverseListResult.output)) {
            log("[ProxyCommander] Reverse mapping $reverseToken is not enabled for '$serial'.")
            return false
        }

        val hostProxyName = confirmSupportedProxyIsListening(log) ?: return false

        log("[ProxyCommander] Test connection succeeded for '$serial': device proxy points to $desiredProxy and $hostProxyName is listening on host port ${config.port}.")
        return true
    }

    fun waitForDeviceReady(serial: String, timeoutMs: Long, log: (String) -> Unit): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val result = adbClient.run(
                serial,
                listOf("shell", "getprop", "sys.boot_completed"),
                allowFailure = true,
                timeoutMs = 2_000
            )
            val bootCompleted = result.output
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (result.success && bootCompleted == "1") {
                return true
            }

            try {
                Thread.sleep(1_000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        log("[ProxyCommander] Device '$serial' did not report boot completion within ${timeoutMs / 1_000}s.")
        return false
    }

    fun watchConnectedDevices(
        log: (String) -> Unit,
        shouldStop: () -> Boolean,
        onSnapshot: (Set<String>) -> Unit
    ): Boolean {
        val initialConnected = listConnectedDevices(log).map { it.serial }.toSet()
        onSnapshot(initialConnected)
        return adbClient.trackDevices(shouldStop = shouldStop, onSnapshot = onSnapshot, log = log)
    }

    private fun connectSerial(serial: String, log: (String) -> Unit): Boolean {
        val previousProxy = readDeviceProxySnapshot(serial, log) ?: return false

        removeStaleReverseMappings(serial, log)
        val reverseStatus = enableReverse(serial, log)
        if (reverseStatus == ReverseEnableStatus.FAILED) {
            return false
        }

        if (!setDeviceProxy(serial, previousProxy.httpProxy, log)) {
            if (!restoreDeviceProxy(serial, previousProxy, log)) {
                log("[ProxyCommander] Failed to restore the previous proxy for $serial after proxy setup failed.")
            }
            if (reverseStatus == ReverseEnableStatus.NEWLY_ENABLED) {
                if (removeReverse(serial, log)) {
                    log("[ProxyCommander] Rolled back newly enabled reverse mapping for $serial after proxy setup failed.")
                } else {
                    log("[ProxyCommander] Failed to roll back newly enabled reverse mapping for $serial after proxy setup failed.")
                }
            }
            return false
        }

        if (config.resetTimeOnConnect) {
            resetDeviceTime(serial, log)
        }
        return true
    }

    private fun resetDeviceTime(serial: String, log: (String) -> Unit) {
        val toggles = listOf("auto_time", "auto_time_zone")
        toggles.forEach { key ->
            adbClient.run(serial, listOf("shell", "settings", "put", "global", key, "0"), allowFailure = true)
            adbClient.run(serial, listOf("shell", "settings", "put", "global", key, "1"), allowFailure = true)
        }
        log("[ProxyCommander] Requested clock and timezone resync for $serial (auto_time toggle)")
        forceDeviceTimeFromHost(serial, log)
    }

    // Directly aligns the device clock to the host wall clock via time_detector. Unlike the
    // auto_time toggle (which only helps when NTP is reachable), this works offline and does
    // not require root, so it is the more reliable path on a developer machine.
    private fun forceDeviceTimeFromHost(serial: String, log: (String) -> Unit) {
        val before = System.currentTimeMillis()
        val uptimeResult = adbClient.run(serial, listOf("shell", "cat", "/proc/uptime"), allowFailure = true)
        val after = System.currentTimeMillis()

        val elapsedMs = parseUptimeMillis(uptimeResult.output)
        if (elapsedMs == null) {
            log("[ProxyCommander] Skipped time_detector sync for $serial (could not read /proc/uptime: ${uptimeResult.briefOutput()})")
            return
        }

        // Pair the device's elapsed-realtime reading with the host epoch sampled at the midpoint
        // of the adb round trip, the closest single estimate of the matching wall-clock instant.
        val hostEpochMs = (before + after) / 2
        val reference = listOf(
            "--elapsed_realtime", elapsedMs.toString(),
            "--unix_epoch_time", hostEpochMs.toString()
        )

        val setResult = adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "set_time_state_for_tests") + reference +
                listOf("--user_should_confirm_time", "false"),
            allowFailure = true
        )
        val setApplied = setResult.success && !ProxyCommanderParsing.looksLikeCmdFailure(setResult.output)
        if (!setApplied) {
            log("[ProxyCommander] Could not force clock on $serial via time_detector (not supported on this Android version?): ${setResult.briefOutput()}")
            return
        }

        val confirmResult = adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "confirm_time") + reference,
            allowFailure = true
        )

        // `confirm_time` always exits zero on supported Android versions and prints the actual
        // boolean result. Requiring an explicit `true` also verifies that the clock set above
        // landed within Android's confidence threshold of the host reference.
        val timeConfirmed = confirmResult.success &&
            !ProxyCommanderParsing.looksLikeCmdFailure(confirmResult.output) &&
            confirmResult.output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.equals("true", ignoreCase = true) == true
        if (!timeConfirmed) {
            log("[ProxyCommander] Clock set command completed for $serial, but confirm_time did not verify the host time: ${confirmResult.briefOutput()}")
            return
        }

        val stateResult = adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "get_time_state"),
            allowFailure = true
        )
        val state = stateResult.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        log("[ProxyCommander] Forced clock on $serial to host time via time_detector (epoch=${hostEpochMs}ms); state=${state.ifBlank { "<unavailable>" }}")
    }

    private fun parseUptimeMillis(output: String): Long? = ProxyCommanderParsing.parseUptimeMillis(output)

    private fun disconnectSerial(
        serial: String,
        log: (String) -> Unit,
        verifyInternet: Boolean = true
    ): DeviceDisconnectResult {
        val reverseRemoved = removeReverse(serial, log)
        val staleReverseMappingsRemoved = removeStaleReverseMappings(serial, log)
        val proxyCleared = clearDeviceProxy(serial, log)
        val internetVerified = if (proxyCleared && verifyInternet) verifyDirectInternetAccess(serial, log) else null
        return DeviceDisconnectResult(
            cleanupSucceeded = reverseRemoved && staleReverseMappingsRemoved && proxyCleared,
            internetVerified = internetVerified
        )
    }

    private fun verifyDirectInternetAccess(serial: String, log: (String) -> Unit): Boolean {
        val result = adbClient.run(
            serial,
            listOf("shell", "sh", "-c", ProxyCommanderInternetProbe.shellScript()),
            allowFailure = true,
            timeoutMs = ProxyCommanderInternetProbe.COMMAND_TIMEOUT_MS
        )
        val success = result.takeIf { it.success }
            ?.let { ProxyCommanderInternetProbe.parseSuccess(it.output) }
        if (success != null) {
            log("[ProxyCommander] Verified direct internet access for $serial via ${success.host} (${success.statusLine}).")
            return true
        }

        val reason = when {
            result.output.contains(ProxyCommanderInternetProbe.UNAVAILABLE_MARKER) ->
                "the device does not provide the nc command"
            result.exitCode == -2 -> "the HTTP probe timed out"
            result.success -> "the probe did not return a valid HTTP status line"
            else -> "no probe host returned a valid HTTP response"
        }
        log("[ProxyCommander] Proxy settings are cleared for $serial, but direct internet access could not be verified: $reason.")
        return false
    }

    private fun enableReverse(serial: String, log: (String) -> Unit): ReverseEnableStatus {
        val reverseListResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (!reverseListResult.success) {
            log("[ProxyCommander] Failed to inspect reverse mappings for $serial: ${reverseListResult.briefOutput()}")
            return ReverseEnableStatus.FAILED
        }
        if (containsReverseMapping(reverseListResult.output)) {
            log("[ProxyCommander] Reverse already enabled for $serial on $reverseToken")
            return ReverseEnableStatus.ALREADY_ENABLED
        }

        val enableResult = adbClient.run(serial, listOf("reverse", reverseToken, reverseToken), allowFailure = true)
        if (!enableResult.success) {
            log("[ProxyCommander] Failed to enable reverse for $serial: ${enableResult.briefOutput()}")
            return ReverseEnableStatus.FAILED
        }

        val verificationResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (!verificationResult.success) {
            log("[ProxyCommander] Could not verify reverse mapping $reverseToken was enabled for $serial: ${verificationResult.briefOutput()}")
            if (!removeReverse(serial, log)) {
                log("[ProxyCommander] Failed to roll back unverified reverse mapping for $serial")
            }
            return ReverseEnableStatus.FAILED
        }
        if (!containsReverseMapping(verificationResult.output)) {
            log("[ProxyCommander] Failed to enable reverse for $serial: mapping $reverseToken is not present after adb reported success")
            return ReverseEnableStatus.FAILED
        }

        log("[ProxyCommander] Enabled reverse $reverseToken <-> $reverseToken for $serial")
        return ReverseEnableStatus.NEWLY_ENABLED
    }

    private fun removeReverse(serial: String, log: (String) -> Unit): Boolean {
        return removeReverseMapping(serial, reverseToken, stale = false, log = log)
    }

    // Reverse mappings applied before a port change would otherwise linger on the device forever:
    // a later connect/disconnect only ever touches the current port's token.
    private fun removeStaleReverseMappings(serial: String, log: (String) -> Unit): Boolean {
        val staleTokens = config.previousPorts
            .filter { it != config.port }
            .map { "tcp:$it" }
        if (staleTokens.isEmpty()) {
            return true
        }

        val listResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (!listResult.success) {
            log("[ProxyCommander] Failed to inspect stale reverse mappings for $serial: ${listResult.briefOutput()}")
            return false
        }

        var allRemoved = true
        staleTokens
            .filter { ProxyCommanderParsing.containsReverseMapping(listResult.output, it) }
            .forEach { token ->
                if (!removeReverseMapping(serial, token, stale = true, log = log)) {
                    allRemoved = false
                }
            }
        return allRemoved
    }

    private fun removeReverseMapping(
        serial: String,
        token: String,
        stale: Boolean,
        log: (String) -> Unit
    ): Boolean {
        val beforeResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (beforeResult.success && !ProxyCommanderParsing.containsReverseMapping(beforeResult.output, token)) {
            log("[ProxyCommander] Reverse mapping $token is already absent for $serial")
            return true
        }

        val removeResult = adbClient.run(serial, listOf("reverse", "--remove", token), allowFailure = true)
        val verificationResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        val mappingAbsent = verificationResult.success &&
            !ProxyCommanderParsing.containsReverseMapping(verificationResult.output, token)
        if (mappingAbsent) {
            if (!removeResult.success) {
                log("[ProxyCommander] Reverse mapping $token is absent for $serial after a failed removal attempt")
            } else if (stale) {
                log("[ProxyCommander] Removed stale reverse mapping $token for $serial (left over from a previous port)")
            } else {
                log("[ProxyCommander] Removed reverse mapping $token for $serial")
            }
            return true
        }

        when {
            !removeResult.success ->
                log("[ProxyCommander] Failed to remove reverse mapping $token for $serial: ${removeResult.briefOutput()}")
            !verificationResult.success ->
                log("[ProxyCommander] Could not verify reverse mapping $token was removed for $serial: ${verificationResult.briefOutput()}")
            else ->
                log("[ProxyCommander] Failed to remove reverse mapping $token for $serial: mapping is still present")
        }
        return false
    }

    private fun setDeviceProxy(serial: String, currentProxy: String, log: (String) -> Unit): Boolean {
        if (currentProxy == desiredProxy) {
            log("[ProxyCommander] Device proxy already set to $desiredProxy for $serial")
            return true
        }

        val writeResult = adbClient.run(
            serial,
            listOf("shell", "settings", "put", "global", "http_proxy", desiredProxy),
            allowFailure = true
        )
        if (!writeResult.success) {
            log("[ProxyCommander] Failed to set proxy for $serial: ${writeResult.briefOutput()}")
            return false
        }

        val verifiedProxy = readGlobalSetting(serial, "http_proxy")
        if (verifiedProxy != desiredProxy) {
            log("[ProxyCommander] Proxy verification failed for $serial: expected '$desiredProxy', got '${verifiedProxy.ifBlank { "<empty>" }}'")
            return false
        }

        log("[ProxyCommander] Device proxy set to $desiredProxy for $serial")
        return true
    }

    private fun restoreDeviceProxy(
        serial: String,
        previousProxy: DeviceProxySnapshot,
        log: (String) -> Unit
    ): Boolean {
        var commandsSucceeded = true
        PROXY_SETTING_KEYS.forEach { key ->
            val value = previousProxy[key]
            val command = if (isAbsentSetting(value)) {
                listOf("shell", "settings", "delete", "global", key)
            } else {
                listOf("shell", "settings", "put", "global", key, value)
            }
            val result = adbClient.run(serial, command, allowFailure = true)
            if (!result.success) {
                commandsSucceeded = false
                log("[ProxyCommander] Could not restore proxy setting '$key' for $serial: ${result.briefOutput()}")
            }
        }

        val restored = readDeviceProxySnapshot(serial, log) ?: return false
        val verified = PROXY_SETTING_KEYS.all { key ->
            proxySettingValuesEquivalent(key, previousProxy[key], restored[key])
        }
        if (!commandsSucceeded || !verified) {
            if (!verified) {
                log("[ProxyCommander] Proxy rollback verification failed for $serial.")
            }
            return false
        }

        log("[ProxyCommander] Restored the previous proxy configuration for $serial after proxy setup failed.")
        return true
    }

    private fun clearDeviceProxy(serial: String, log: (String) -> Unit): Boolean {
        var currentProxy = readGlobalSetting(serial, "http_proxy")
        var currentHost = readGlobalSetting(serial, "global_http_proxy_host")
        var currentPort = readGlobalSetting(serial, "global_http_proxy_port")
        var currentPac = readGlobalSetting(serial, "global_http_proxy_pac")

        if (isProxyCleared(currentProxy, currentHost, currentPort, currentPac)) {
            log("[ProxyCommander] Device proxy already cleared for $serial")
            return true
        }

        adbClient.run(serial, listOf("shell", "settings", "delete", "global", "http_proxy"), allowFailure = true)
        adbClient.run(serial, listOf("shell", "settings", "put", "global", "http_proxy", ":0"), allowFailure = true)
        adbClient.run(serial, listOf("shell", "settings", "delete", "global", "global_http_proxy_host"), allowFailure = true)
        adbClient.run(serial, listOf("shell", "settings", "delete", "global", "global_http_proxy_port"), allowFailure = true)
        adbClient.run(serial, listOf("shell", "settings", "delete", "global", "global_http_proxy_exclusion_list"), allowFailure = true)
        adbClient.run(serial, listOf("shell", "settings", "delete", "global", "global_http_proxy_pac"), allowFailure = true)

        currentProxy = readGlobalSetting(serial, "http_proxy")
        currentHost = readGlobalSetting(serial, "global_http_proxy_host")
        currentPort = readGlobalSetting(serial, "global_http_proxy_port")
        currentPac = readGlobalSetting(serial, "global_http_proxy_pac")
        if (isProxyCleared(currentProxy, currentHost, currentPort, currentPac)) {
            log("[ProxyCommander] Device proxy cleared for $serial")
            return true
        }

        log("[ProxyCommander] Failed to clear device proxy for $serial (http_proxy='$currentProxy', host='$currentHost', port='$currentPort', pac='$currentPac')")
        return false
    }

    private fun readGlobalSetting(serial: String, key: String): String {
        return readGlobalSettingResult(serial, key).value
    }

    private fun readGlobalSettingResult(serial: String, key: String): GlobalSettingRead {
        val result = adbClient.run(serial, listOf("shell", "settings", "get", "global", key), allowFailure = true)
        val value = result.output
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace("\r", "")
            .trim()
        return GlobalSettingRead(result = result, value = value)
    }

    private fun readDeviceProxySnapshot(
        serial: String,
        log: (String) -> Unit
    ): DeviceProxySnapshot? {
        val result = adbClient.run(
            serial,
            listOf("shell", "settings", "list", "global"),
            allowFailure = true
        )
        if (!result.success) {
            log("[ProxyCommander] Failed to read the existing proxy configuration for $serial: ${result.briefOutput()}")
            return null
        }

        val globalSettings = result.output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val values = PROXY_SETTING_KEYS.associateWith { key -> globalSettings[key]?.trim() ?: "null" }
        return DeviceProxySnapshot(values)
    }

    private fun proxySettingValuesEquivalent(key: String, expected: String, actual: String): Boolean {
        if (expected == actual) {
            return true
        }
        return when (key) {
            "http_proxy" -> ProxyCommanderParsing.isProxyCleared(expected, "", "", "") &&
                ProxyCommanderParsing.isProxyCleared(actual, "", "", "")
            "global_http_proxy_port" ->
                expected in CLEARED_PROXY_PORT_VALUES && actual in CLEARED_PROXY_PORT_VALUES
            else -> isAbsentSetting(expected) && isAbsentSetting(actual)
        }
    }

    private fun isAbsentSetting(value: String): Boolean = value.isBlank() || value == "null"

    private fun isProxyConnected(serial: String): Boolean {
        val currentProxy = readGlobalSetting(serial, "http_proxy")
        if (currentProxy != desiredProxy) {
            return false
        }

        val reverseListResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        return reverseListResult.success && containsReverseMapping(reverseListResult.output)
    }

    private fun readDeviceProps(serial: String): DeviceProps {
        val result = adbClient.run(
            serial,
            listOf("shell", "getprop"),
            allowFailure = true,
            timeoutMs = 3_000
        )
        if (!result.success) {
            return DeviceProps(emptyMap())
        }
        return DeviceProps(ProxyCommanderParsing.parseGetpropOutput(result.output))
    }

    private fun readModel(props: DeviceProps): String =
        props.get("ro.product.model").ifBlank { "Unknown Model" }

    private fun readApiLevel(props: DeviceProps): String =
        props.get("ro.build.version.sdk").ifBlank { "?" }

    private fun readStableEmulatorName(serial: String, props: DeviceProps): String? {
        val propValue = props.get("ro.boot.qemu.avd_name")
        if (propValue.isNotBlank()) {
            return propValue
        }

        val emuResult = adbClient.run(
            serial,
            listOf("emu", "avd", "name"),
            allowFailure = true,
            timeoutMs = 2_500
        )
        val emuValue = emuResult.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "OK" }
            .orEmpty()
        return emuValue.ifBlank { null }
    }

    private fun containsReverseMapping(output: String): Boolean =
        ProxyCommanderParsing.containsReverseMapping(output, reverseToken)

    private fun readDeviceIdentifier(device: ConnectedDevice, props: DeviceProps): String {
        if (!device.isEmulator) {
            return device.serial
        }

        return readStableEmulatorName(device.serial, props) ?: device.serial
    }

    private fun readDeviceDisplayName(device: ConnectedDevice, identifier: String, props: DeviceProps): String {
        if (!device.isEmulator) {
            val model = readModel(props).takeUnless { it == "Unknown Model" }.orEmpty()
            return model.ifBlank { identifier }
        }

        return identifier.replace('_', ' ')
    }

    private fun confirmSupportedProxyIsListening(log: (String) -> Unit): String? {
        val probeResponse = sendHttpProxyProbe("http://control.charles/")
        if (probeResponse != null && probeResponse.contains("Charles", ignoreCase = true)) {
            log("[ProxyCommander] Charles web interface responded on localhost:${config.port}.")
            return "Charles"
        }

        val processName = detectPortOwnerProcessName()?.trim().orEmpty()
        if (processName.isNotBlank()) {
            val friendlyName = KNOWN_PROXY_PROCESSES
                .firstOrNull { (token, _) -> processName.contains(token, ignoreCase = true) }
                ?.second
                ?: processName
            log("[ProxyCommander] Host port ${config.port} is owned by $processName.")
            return friendlyName
        }

        // The port owner could not be named, but the probe socket connected, so something is
        // listening on the port even if we cannot identify it.
        if (probeResponse != null) {
            log("[ProxyCommander] A proxy answered on localhost:${config.port}.")
            return "a proxy on localhost:${config.port}"
        }

        log("[ProxyCommander] Could not confirm that a proxy is listening on localhost:${config.port}. Ensure your proxy app (Charles, Proxyman, mitmproxy, Burp, Fiddler, ...) is running on port ${config.port}.")
        return null
    }

    private fun sendHttpProxyProbe(url: String): String? =
        runCatching {
            Socket().use { socket ->
                socket.soTimeout = 2_000
                socket.connect(InetSocketAddress("127.0.0.1", config.port), 2_000)
                val writer = socket.getOutputStream().bufferedWriter()
                run {
                    writer.write("GET $url HTTP/1.1\r\n")
                    writer.write("Host: ${url.removePrefix("http://").removeSuffix("/")}\r\n")
                    writer.write("Connection: close\r\n")
                    writer.write("\r\n")
                    writer.flush()
                }
                socket.getInputStream().bufferedReader().use { reader ->
                    buildString {
                        val buffer = CharArray(2048)
                        var total = 0
                        while (total < MAX_PROXY_PROBE_RESPONSE_CHARS) {
                            val read = reader.read(buffer, 0, minOf(buffer.size, MAX_PROXY_PROBE_RESPONSE_CHARS - total))
                            if (read <= 0) {
                                break
                            }
                            append(buffer, 0, read)
                            total += read
                        }
                    }
                }
            }
        }.getOrNull()

    private fun detectPortOwnerProcessName(): String? =
        if (isWindowsHost()) {
            detectPortOwnerProcessNameWindows()
        } else {
            detectPortOwnerProcessNameUnix()
        }

    private fun detectPortOwnerProcessNameUnix(): String? {
        val output = runHostCommand(listOf("lsof", "-nP", "-iTCP:${config.port}", "-sTCP:LISTEN")) ?: return null
        return ProxyCommanderParsing.parseLsofListeningProcessName(output)
    }

    private fun detectPortOwnerProcessNameWindows(): String? {
        val netstatOutput = runHostCommand(listOf("netstat", "-ano", "-p", "TCP")) ?: return null
        val pid = ProxyCommanderParsing.parseNetstatListeningPid(netstatOutput, config.port) ?: return null
        val tasklistOutput = runHostCommand(listOf("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")) ?: return null
        return ProxyCommanderParsing.parseTasklistProcessName(tasklistOutput)
    }

    private fun runHostCommand(command: List<String>): String? =
        (ProcessRunner.run(command, timeoutMs = HOST_COMMAND_TIMEOUT_MS) as? ProcessRunner.Result.Completed)
            ?.output
            ?.takeIf { it.isNotBlank() }

    private fun isProxyCleared(proxy: String, host: String, port: String, pac: String): Boolean =
        ProxyCommanderParsing.isProxyCleared(proxy, host, port, pac)

    private fun startAdbServer() {
        adbClient.run(args = listOf("start-server"), allowFailure = true)
    }

    private data class ConnectedDeviceQuery(
        val success: Boolean,
        val devices: List<ConnectedDevice>
    )

    private data class GlobalSettingRead(
        val result: AdbCommandResult,
        val value: String
    )

    private data class DeviceProxySnapshot(private val values: Map<String, String>) {
        val httpProxy: String
            get() = values.getValue("http_proxy")

        operator fun get(key: String): String = values.getValue(key)
    }

    private class DeviceProps(private val map: Map<String, String>) {
        // getprop renders an unset property as an empty value; treat blank and "null" as absent.
        fun get(key: String): String =
            map[key]?.trim()?.takeUnless { it == "null" }.orEmpty()
    }

    private companion object {
        const val MAX_PROXY_PROBE_RESPONSE_CHARS = 8_192
        const val MAX_DETAIL_READ_THREADS = 4
        const val HOST_COMMAND_TIMEOUT_MS = 2_000L

        val PROXY_SETTING_KEYS = listOf(
            "http_proxy",
            "global_http_proxy_host",
            "global_http_proxy_port",
            "global_http_proxy_exclusion_list",
            "global_http_proxy_pac"
        )
        val CLEARED_PROXY_PORT_VALUES = setOf("", "null", "0", "-1")

        // (substring to match in the listening process name) -> (display name)
        val KNOWN_PROXY_PROCESSES = listOf(
            "charles" to "Charles",
            "proxyman" to "Proxyman",
            "mitmproxy" to "mitmproxy",
            "mitmdump" to "mitmproxy",
            "mitmweb" to "mitmproxy",
            "burp" to "Burp Suite",
            "fiddler" to "Fiddler"
        )
    }
}
