package io.github.andriyo.proxycommander

import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.concurrent.TimeUnit

internal data class ProxyCommanderConfig(
    val port: Int = ProxyCommanderSettingsService.DEFAULT_PORT,
    val adbPath: String = "",
    val resetTimeOnConnect: Boolean = true
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

internal class ProxyCommanderController private constructor(
    private val adbClient: AdbClient,
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

        return result.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val columns = line.split(Regex("\\s+"))
                if (columns.size < 2 || columns[1] != "device") {
                    null
                } else {
                    val serial = columns[0]
                    ConnectedDevice(
                        serial = serial,
                        isEmulator = EMULATOR_SERIAL_REGEX.matches(serial)
                    )
                }
            }
            .toList()
    }

    fun listConnectedEmulators(log: (String) -> Unit = {}): List<ConnectedEmulator> {
        val emulators = listConnectedDevices(log).filter { it.isEmulator }
        return emulators.map { device ->
            ConnectedEmulator(
                serial = device.serial,
                avdName = readEmulatorAvdName(device.serial),
                model = readDeviceModel(device.serial)
            )
        }
    }

    fun listConnectedDeviceDetails(log: (String) -> Unit = {}): List<ConnectedDeviceDetails> {
        val devices = listConnectedDevices(log)
        return devices.map { device ->
            val identifier = readDeviceIdentifier(device)
            ConnectedDeviceDetails(
                identifier = identifier,
                serial = device.serial,
                name = readDeviceDisplayName(device, identifier),
                apiLevel = readDeviceApiLevel(device.serial),
                isEmulator = device.isEmulator,
                isProxyConnected = isProxyConnected(device.serial)
            )
        }
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

    fun connectEmulatorAndClearProxyOnOthers(activeEmulatorSerial: String, log: (String) -> Unit): Boolean {
        val devices = listConnectedDevices(log)
        if (devices.isEmpty()) {
            log("[ProxyCommander] No connected devices in 'device' state.")
            return false
        }

        val activeDevice = devices.firstOrNull { it.serial == activeEmulatorSerial }
        if (activeDevice == null) {
            log("[ProxyCommander] Active emulator '$activeEmulatorSerial' is not connected.")
            return false
        }
        if (!activeDevice.isEmulator) {
            log("[ProxyCommander] Active device '$activeEmulatorSerial' is not an emulator.")
            return false
        }

        var failed = false
        if (!connectSerial(activeEmulatorSerial, log)) {
            failed = true
        }

        val others = devices.filterNot { it.serial == activeEmulatorSerial }
        if (others.isEmpty()) {
            log("[ProxyCommander] Active emulator '$activeEmulatorSerial' is the only connected device.")
            return !failed
        }

        log("[ProxyCommander] Clearing proxy on ${others.size} other connected device(s).")
        others.forEach { device ->
            if (!clearDeviceProxy(device.serial, log)) {
                failed = true
            }
        }

        if (failed) {
            log("[ProxyCommander] Finished with errors while connecting active emulator and clearing proxies on others.")
        } else {
            log("[ProxyCommander] Active emulator connected and proxies cleared on all other connected devices.")
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

        adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "set_time_state_for_tests") + reference +
                listOf("--user_should_confirm_time", "false"),
            allowFailure = true
        )
        adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "confirm_time") + reference,
            allowFailure = true
        )

        val stateResult = adbClient.run(
            serial,
            listOf("shell", "cmd", "time_detector", "get_time_state"),
            allowFailure = true
        )
        val state = stateResult.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        log("[ProxyCommander] Forced clock on $serial to host time via time_detector (epoch=${hostEpochMs}ms); state=${state.ifBlank { "<unavailable>" }}")
    }

    private fun parseUptimeMillis(output: String): Long? {
        val seconds = output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toDoubleOrNull()
            ?: return null
        return (seconds * 1000).toLong()
    }

    private fun disconnectSerial(serial: String, log: (String) -> Unit): Boolean {
        val reverseRemoved = removeReverse(serial, log)
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

    private fun readEmulatorAvdName(serial: String): String {
        return readStableEmulatorName(serial) ?: "Unknown AVD"
    }

    private fun readStableEmulatorName(serial: String): String? {
        val propResult = adbClient.run(
            serial,
            listOf("shell", "getprop", "ro.boot.qemu.avd_name"),
            allowFailure = true,
            timeoutMs = 2_000
        )
        val propValue = propResult.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" }
            .orEmpty()
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

    private fun readDeviceModel(serial: String): String {
        val result = adbClient.run(
            serial,
            listOf("shell", "getprop", "ro.product.model"),
            allowFailure = true,
            timeoutMs = 2_000
        )
        val value = result.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return value.ifBlank { "Unknown Model" }
    }

    private fun readDeviceApiLevel(serial: String): String {
        val result = adbClient.run(
            serial,
            listOf("shell", "getprop", "ro.build.version.sdk"),
            allowFailure = true,
            timeoutMs = 2_000
        )
        val value = result.output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return value.ifBlank { "?" }
    }

    private fun containsReverseMapping(output: String): Boolean {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.any { line ->
            val columns = line.split(Regex("\\s+"))
            when (columns.size) {
                2 -> columns[0] == reverseToken && columns[1] == reverseToken
                3 -> columns[1] == reverseToken && columns[2] == reverseToken
                else -> false
            }
        }
    }

    private fun readDeviceIdentifier(device: ConnectedDevice): String {
        if (!device.isEmulator) {
            return device.serial
        }

        return readStableEmulatorName(device.serial) ?: device.serial
    }

    private fun readDeviceDisplayName(device: ConnectedDevice, identifier: String): String {
        val model = readDeviceModel(device.serial).takeUnless { it == "Unknown Model" }.orEmpty()
        if (!device.isEmulator) {
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
        if (processName.contains("charles", ignoreCase = true)) {
            log("[ProxyCommander] Host port ${config.port} is owned by $processName.")
            return "Charles"
        }
        if (processName.contains("proxyman", ignoreCase = true)) {
            log("[ProxyCommander] Host port ${config.port} is owned by $processName.")
            return "Proxyman"
        }

        log("[ProxyCommander] Could not confirm that localhost:${config.port} is Charles or Proxyman. Ensure your proxy app is running on port ${config.port}.")
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

    private fun detectPortOwnerProcessName(): String? {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            return null
        }

        return runCatching {
            val process = ProcessBuilder("lsof", "-nP", "-iTCP:${config.port}", "-sTCP:LISTEN")
                .redirectErrorStream(true)
                .start()
            process.waitFor(2, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().use { reader ->
                reader.lineSequence()
                    .drop(1)
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
            }
        }.getOrNull()
    }

    private fun isProxyCleared(proxy: String, host: String, port: String, pac: String): Boolean {
        val proxyCleared = proxy.isBlank() || proxy == "null" || proxy == ":0"
        val hostCleared = host.isBlank() || host == "null"
        val portCleared = port.isBlank() || port == "null" || port == "0" || port == "-1"
        val pacCleared = pac.isBlank() || pac == "null"
        return proxyCleared && hostCleared && portCleared && pacCleared
    }

    private fun startAdbServer() {
        adbClient.run(args = listOf("start-server"), allowFailure = true)
    }

    private class AdbClient(private val workingDirectory: File?, adbPath: String) {
        private val configuredAdbPath = adbPath.takeIf { it.isNotBlank() }
        private val resolution = resolveAdbExecutable()
        private val adbExecutable = resolution.executable
        private val defaultTimeoutMs = 10_000L

        fun checkAvailability(): CommandResult =
            run(args = listOf("version"), allowFailure = true, timeoutMs = 3_000)

        fun trackDevices(
            shouldStop: () -> Boolean,
            onSnapshot: (Set<String>) -> Unit,
            log: (String) -> Unit
        ): Boolean {
            val command = listOf(adbExecutable, "track-devices")
            val process = try {
                startProcess(command)
            } catch (error: IOException) {
                log("[ProxyCommander] ${unavailableMessage()}")
                return false
            } catch (error: Exception) {
                log("[ProxyCommander] ${error.message ?: "Failed to start adb track-devices."}")
                return false
            }

            return try {
                process.inputStream.buffered().use { input ->
                    while (!shouldStop()) {
                        if (input.available() < ADB_TRACK_HEADER_SIZE) {
                            if (!process.isAlive) {
                                break
                            }
                            Thread.sleep(250)
                            continue
                        }

                        val header = readTrackFrame(input, ADB_TRACK_HEADER_SIZE) ?: break
                        val payloadSize = header.toString(Charsets.US_ASCII).toIntOrNull(16)
                        if (payloadSize == null) {
                            log("[ProxyCommander] Failed to parse adb track-devices frame header '${header.toString(Charsets.US_ASCII)}'.")
                            return false
                        }

                        while (!shouldStop() && input.available() < payloadSize) {
                            if (!process.isAlive) {
                                break
                            }
                            Thread.sleep(250)
                        }
                        if (shouldStop()) {
                            break
                        }

                        val payload = if (payloadSize == 0) {
                            ByteArray(0)
                        } else {
                            readTrackFrame(input, payloadSize) ?: break
                        }
                        onSnapshot(parseTrackedDevicesSnapshot(payload.toString(Charsets.UTF_8)))
                    }
                }
                true
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                true
            } catch (error: Exception) {
                log("[ProxyCommander] ${error.message ?: "adb track-devices failed."}")
                false
            } finally {
                process.destroyForcibly()
                runCatching { process.waitFor(200, TimeUnit.MILLISECONDS) }
            }
        }

        private fun readTrackFrame(input: java.io.InputStream, size: Int): ByteArray? {
            if (size == 0) {
                return ByteArray(0)
            }

            val buffer = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = input.read(buffer, offset, size - offset)
                if (read < 0) {
                    return null
                }
                offset += read
            }
            return buffer
        }

        fun run(args: List<String>, allowFailure: Boolean = false, timeoutMs: Long = defaultTimeoutMs): CommandResult =
            run(serial = null, args = args, allowFailure = allowFailure, timeoutMs = timeoutMs)

        fun run(
            serial: String?,
            args: List<String>,
            allowFailure: Boolean = false,
            timeoutMs: Long = defaultTimeoutMs
        ): CommandResult {
            val command = mutableListOf(adbExecutable)
            if (!serial.isNullOrBlank()) {
                command += listOf("-s", serial)
            }
            command += args

            return try {
                val process = startProcess(command)
                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    process.waitFor(200, TimeUnit.MILLISECONDS)
                    return CommandResult(
                        exitCode = -2,
                        output = "Timed out after ${timeoutMs}ms",
                        command = command
                    )
                }

                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val exitCode = process.exitValue()
                CommandResult(exitCode = exitCode, output = output, command = command)
            } catch (error: IOException) {
                CommandResult(
                    exitCode = COMMAND_UNAVAILABLE_EXIT_CODE,
                    output = unavailableMessage(),
                    command = command
                )
            } catch (error: Exception) {
                CommandResult(
                    exitCode = -1,
                    output = error.message ?: "Failed to execute adb command.",
                    command = command
                )
            }
        }

        private fun startProcess(command: List<String>): Process {
            val processBuilder = ProcessBuilder(command)
            workingDirectory?.let(processBuilder::directory)
            processBuilder.redirectErrorStream(true)
            return processBuilder.start()
        }

        private fun unavailableMessage(): String = when (resolution.source) {
            AdbSource.CONFIGURED_PATH ->
                "ADB executable at '$adbExecutable' could not be launched. Update Proxy Commander Settings > ADB Path."
            AdbSource.ENV_ADB ->
                "ADB executable from \$ADB at '$adbExecutable' could not be launched. Fix \$ADB or set Proxy Commander Settings > ADB Path."
            AdbSource.PATH ->
                "ADB command is not available. The plugin could not autodetect adb from the Android SDK. Add adb to PATH or set Proxy Commander Settings > ADB Path."
            else ->
                "Autodetected adb at '$adbExecutable' could not be launched. Set Proxy Commander Settings > ADB Path explicitly."
        }

        private fun resolveAdbExecutable(): AdbResolution {
            configuredAdbPath?.let {
                return AdbResolution(it, AdbSource.CONFIGURED_PATH)
            }

            val envAdbPath = System.getenv("ADB").takeUnless { it.isNullOrBlank() }
            existingFile(envAdbPath)?.let {
                return AdbResolution(it.absolutePath, AdbSource.ENV_ADB)
            }

            val localPropertiesSdk = findLocalPropertiesSdkDir(workingDirectory)
            sdkAdbCandidate(localPropertiesSdk)?.let {
                return AdbResolution(it.absolutePath, AdbSource.LOCAL_PROPERTIES)
            }

            val androidSdkRoot = System.getenv("ANDROID_SDK_ROOT").takeUnless { it.isNullOrBlank() }
            sdkAdbCandidate(androidSdkRoot)?.let {
                return AdbResolution(it.absolutePath, AdbSource.ANDROID_SDK_ROOT)
            }

            val androidHome = System.getenv("ANDROID_HOME").takeUnless { it.isNullOrBlank() }
            sdkAdbCandidate(androidHome)?.let {
                return AdbResolution(it.absolutePath, AdbSource.ANDROID_HOME)
            }

            standardSdkCandidates().firstNotNullOfOrNull(::sdkAdbCandidate)?.let {
                return AdbResolution(it.absolutePath, AdbSource.STANDARD_SDK_LOCATION)
            }

            envAdbPath?.let {
                return AdbResolution(it, AdbSource.ENV_ADB)
            }

            return AdbResolution("adb", AdbSource.PATH)
        }

        private fun parseTrackedDevicesSnapshot(snapshot: String): Set<String> =
            snapshot.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val columns = line.split(Regex("\\s+"))
                    if (columns.size < 2 || columns[1] != "device") {
                        null
                    } else {
                        columns[0]
                    }
                }
                .toSet()

        private fun existingFile(path: String?): File? {
            if (path.isNullOrBlank()) {
                return null
            }
            val file = File(path)
            return file.takeIf { it.isFile }
        }

        private fun sdkAdbCandidate(sdkDir: String?): File? {
            if (sdkDir.isNullOrBlank()) {
                return null
            }
            val candidate = File(File(sdkDir), "platform-tools/${adbExecutableName()}")
            return candidate.takeIf { it.isFile }
        }

        private fun findLocalPropertiesSdkDir(projectDir: File?): String? {
            val localProperties = projectDir?.resolve("local.properties") ?: return null
            if (!localProperties.isFile) {
                return null
            }

            return runCatching {
                val properties = Properties()
                localProperties.inputStream().use(properties::load)
                properties.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        private fun standardSdkCandidates(): List<String> {
            val userHome = System.getProperty("user.home").orEmpty()
            val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
            return buildList {
                if (userHome.isNotBlank()) {
                    add("$userHome/Library/Android/sdk")
                    add("$userHome/Android/Sdk")
                }
                if (localAppData.isNotBlank()) {
                    add("$localAppData/Android/Sdk")
                }
            }
        }

        private fun adbExecutableName(): String =
            if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "adb.exe" else "adb"
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val command: List<String>
    ) {
        val success: Boolean
            get() = exitCode == 0
        val isCommandUnavailable: Boolean
            get() = exitCode == COMMAND_UNAVAILABLE_EXIT_CODE

        fun briefOutput(): String {
            val firstLine = output.lineSequence().firstOrNull().orEmpty().trim()
            return firstLine.ifBlank { "Command failed: ${command.joinToString(" ")}" }
        }
    }

    private companion object {
        const val COMMAND_UNAVAILABLE_EXIT_CODE = -3
        const val ADB_TRACK_HEADER_SIZE = 4
        const val MAX_PROXY_PROBE_RESPONSE_CHARS = 8_192
        val EMULATOR_SERIAL_REGEX = Regex("^emulator-[0-9]+$")
    }

    private data class AdbResolution(
        val executable: String,
        val source: AdbSource
    )

    private enum class AdbSource {
        CONFIGURED_PATH,
        ENV_ADB,
        LOCAL_PROPERTIES,
        ANDROID_SDK_ROOT,
        ANDROID_HOME,
        STANDARD_SDK_LOCATION,
        PATH
    }
}
