package io.github.andriyo.proxycommander

import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.TimeUnit

internal data class ProxyCommanderConfig(
    val port: Int = ProxyCommanderSettingsService.DEFAULT_PORT,
    val adbPath: String = ""
)

internal data class ConnectedDevice(
    val serial: String,
    val isEmulator: Boolean
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

    private fun connectSerial(serial: String, log: (String) -> Unit): Boolean {
        val reverseEnabled = enableReverse(serial, log)
        val proxyConfigured = setDeviceProxy(serial, log)
        return reverseEnabled && proxyConfigured
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

    private fun readEmulatorAvdName(serial: String): String {
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
        return emuValue.ifBlank { "Unknown AVD" }
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

    private class AdbClient(workingDirectory: File?, adbPath: String) {
        private val adbExecutable = when {
            adbPath.isNotBlank() -> adbPath
            else -> System.getenv("ADB").takeUnless { it.isNullOrBlank() } ?: "adb"
        }
        private val workingDirectory = workingDirectory
        private val defaultTimeoutMs = 10_000L

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
                val processBuilder = ProcessBuilder(command)
                workingDirectory?.let(processBuilder::directory)
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
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
                val result = CommandResult(exitCode = exitCode, output = output, command = command)
                if (!allowFailure && !result.success) {
                    result
                } else {
                    result
                }
            } catch (error: Exception) {
                CommandResult(
                    exitCode = -1,
                    output = error.message ?: "Failed to execute adb command.",
                    command = command
                )
            }
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String,
        val command: List<String>
    ) {
        val success: Boolean
            get() = exitCode == 0

        fun briefOutput(): String {
            val firstLine = output.lineSequence().firstOrNull().orEmpty().trim()
            return firstLine.ifBlank { "Command failed: ${command.joinToString(" ")}" }
        }
    }

    private companion object {
        val EMULATOR_SERIAL_REGEX = Regex("^emulator-[0-9]+$")
    }
}
