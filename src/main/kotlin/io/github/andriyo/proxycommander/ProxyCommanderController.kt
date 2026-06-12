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

    fun ensureAdbAvailable(log: (String) -> Unit): Boolean {
        val result = adbClient.checkAvailability()
        if (result.isCommandUnavailable) {
            log("[ProxyCommander] ${result.briefOutput()}")
            return false
        }
        return true
    }

    fun listConnectedDevices(log: (String) -> Unit = {}): List<ConnectedDevice> {
        startAdbServer()
        val result = adbClient.run(args = listOf("devices"), allowFailure = true)
        if (!result.success) {
            log("[ProxyCommander] Failed to list connected devices: ${result.briefOutput()}")
            return emptyList()
        }

        return ProxyCommanderParsing.parseDevices(result.output)
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

    fun disconnectDevice(serial: String, log: (String) -> Unit): Boolean {
        val exists = listConnectedDevices(log).any { it.serial == serial }
        if (!exists) {
            log("[ProxyCommander] Device '$serial' is not connected.")
            return false
        }
        return disconnectSerial(serial, log)
    }

    fun disconnectAllDevices(log: (String) -> Unit): Boolean {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return false
        }

        log("[ProxyCommander] Disconnecting ${devices.size} device(s) on $reverseToken")
        var failed = false
        devices.forEach { device ->
            if (!disconnectSerial(device.serial, log)) {
                failed = true
            }
        }

        if (failed) {
            log("[ProxyCommander] Disconnect finished with errors on one or more devices.")
        } else {
            log("[ProxyCommander] Disconnect completed successfully for all devices.")
        }
        return !failed
    }

    fun keepOnlyDevice(selectedSerial: String, log: (String) -> Unit): Boolean {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return false
        }
        if (devices.none { it.serial == selectedSerial }) {
            log("[ProxyCommander] Selected device '$selectedSerial' is not connected.")
            return false
        }

        if (!connectSerial(selectedSerial, log)) {
            log("[ProxyCommander] Failed to keep selected device '$selectedSerial' connected.")
            return false
        }

        val others = devices.filterNot { it.serial == selectedSerial }
        if (others.isEmpty()) {
            log("[ProxyCommander] Selected device '$selectedSerial' is the only connected device.")
            return true
        }

        var failed = false
        others.forEach { device ->
            if (!disconnectSerial(device.serial, log)) {
                failed = true
            }
        }
        return !failed
    }

    fun connectDeviceAndClearProxyOnOthers(activeSerial: String, log: (String) -> Unit): Boolean {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return false
        }

        val activeDevice = devices.firstOrNull { it.serial == activeSerial }
        if (activeDevice == null) {
            log("[ProxyCommander] Active device '$activeSerial' is not connected.")
            return false
        }

        var failed = false
        if (!connectSerial(activeSerial, log)) {
            failed = true
        }

        val others = devices.filterNot { it.serial == activeSerial }
        if (others.isEmpty()) {
            log("[ProxyCommander] Active device '$activeSerial' is the only connected device.")
            return !failed
        }

        log("[ProxyCommander] Clearing proxy on ${others.size} other connected device(s).")
        others.forEach { device ->
            if (!clearDeviceProxy(device.serial, log)) {
                failed = true
            }
        }

        if (failed) {
            log("[ProxyCommander] Finished with errors while connecting active device and clearing proxies on others.")
        } else {
            log("[ProxyCommander] Active device connected and proxies cleared on all other connected devices.")
        }
        return !failed
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
        removeStaleReverseMappings(serial, log)
        val reverseEnabled = enableReverse(serial, log)
        val proxyConfigured = setDeviceProxy(serial, log)
        if (config.resetTimeOnConnect) {
            resetDeviceTime(serial, log)
        }
        return reverseEnabled && proxyConfigured
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
        val confirmResult = adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "confirm_time") + reference,
            allowFailure = true
        )

        val setApplied = setResult.success && !ProxyCommanderParsing.looksLikeCmdFailure(setResult.output)
        val confirmApplied = confirmResult.success && !ProxyCommanderParsing.looksLikeCmdFailure(confirmResult.output)
        if (!setApplied && !confirmApplied) {
            log("[ProxyCommander] Could not force clock on $serial via time_detector (not supported on this Android version?): ${setResult.briefOutput()}")
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

    private fun disconnectSerial(serial: String, log: (String) -> Unit): Boolean {
        val reverseRemoved = removeReverse(serial, log)
        removeStaleReverseMappings(serial, log)
        val proxyCleared = clearDeviceProxy(serial, log)
        return reverseRemoved && proxyCleared
    }

    private fun enableReverse(serial: String, log: (String) -> Unit): Boolean {
        val reverseListResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (containsReverseMapping(reverseListResult.output)) {
            log("[ProxyCommander] Reverse already enabled for $serial on $reverseToken")
            return true
        }

        val enableResult = adbClient.run(serial, listOf("reverse", reverseToken, reverseToken), allowFailure = true)
        if (!enableResult.success) {
            log("[ProxyCommander] Failed to enable reverse for $serial: ${enableResult.briefOutput()}")
            return false
        }

        log("[ProxyCommander] Enabled reverse $reverseToken <-> $reverseToken for $serial")
        return true
    }

    private fun removeReverse(serial: String, log: (String) -> Unit): Boolean {
        adbClient.run(serial, listOf("reverse", "--remove", reverseToken), allowFailure = true)
        log("[ProxyCommander] Reverse removal attempted for $serial on $reverseToken")
        return true
    }

    // Reverse mappings applied before a port change would otherwise linger on the device forever:
    // a later connect/disconnect only ever touches the current port's token.
    private fun removeStaleReverseMappings(serial: String, log: (String) -> Unit) {
        val staleTokens = config.previousPorts
            .filter { it != config.port }
            .map { "tcp:$it" }
        if (staleTokens.isEmpty()) {
            return
        }

        val listResult = adbClient.run(serial, listOf("reverse", "--list"), allowFailure = true)
        if (!listResult.success) {
            return
        }

        staleTokens
            .filter { ProxyCommanderParsing.containsReverseMapping(listResult.output, it) }
            .forEach { token ->
                adbClient.run(serial, listOf("reverse", "--remove", token), allowFailure = true)
                log("[ProxyCommander] Removed stale reverse mapping $token for $serial (left over from a previous port)")
            }
    }

    private fun setDeviceProxy(serial: String, log: (String) -> Unit): Boolean {
        val currentProxy = readGlobalSetting(serial, "http_proxy")
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
        val result = adbClient.run(serial, listOf("shell", "settings", "get", "global", key), allowFailure = true)
        return result.output
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace("\r", "")
            .trim()
    }

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

    private class DeviceProps(private val map: Map<String, String>) {
        // getprop renders an unset property as an empty value; treat blank and "null" as absent.
        fun get(key: String): String =
            map[key]?.trim()?.takeUnless { it == "null" }.orEmpty()
    }

    private companion object {
        const val MAX_PROXY_PROBE_RESPONSE_CHARS = 8_192
        const val MAX_DETAIL_READ_THREADS = 4
        const val HOST_COMMAND_TIMEOUT_MS = 2_000L

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
