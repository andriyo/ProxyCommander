package com.andrii.proxycommander

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyCommanderIntegrationTest {

    private val testEnabled = System.getenv(ENABLE_ENV) == "1"
    private val proxyPort = System.getenv(PORT_ENV)?.toIntOrNull() ?: DEFAULT_PROXY_PORT
    private val controller = ProxyCommanderController(
        projectBasePath = File(".").absolutePath,
        config = ProxyCommanderConfig(port = proxyPort)
    )

    private val logs = mutableListOf<String>()

    @Before
    fun ensureIntegrationPreconditions() {
        assumeTrue("Set $ENABLE_ENV=1 to run adb integration tests.", testEnabled)
        assumeTrue("adb is unavailable.", runLocalCommand("adb", "version").exitCode == 0)
        val devices = controller.listConnectedDevices(logs::add)
        assumeTrue("At least one connected device is required.", devices.isNotEmpty())
        val ncCapable = devices.any { hasNc(it.serial) }
        assumeTrue("No connected device has 'nc' available in shell.", ncCapable)
    }

    @After
    fun cleanupAfterTest() {
        if (!testEnabled) {
            return
        }
        controller.disconnectAllDevices {}
    }

    @Test
    fun connectAllAndDisconnectAll_toggleReverseAndProxyAndConnectivity() {
        val devices = controller.listConnectedDevices(logs::add)
        assumeTrue("At least one connected device is required.", devices.isNotEmpty())

        val localServer = ensureLocalEndpointListening(proxyPort)
        assumeTrue("No endpoint available on host at 127.0.0.1:$proxyPort.", isHostPortOpen("127.0.0.1", proxyPort))

        try {
            assertTrue("connectAllDevices failed.\n${logs.joinToString("\n")}", controller.connectAllDevices(logs::add))

            devices.forEach { device ->
                assertTrue("Reverse mapping missing on ${device.serial}", hasReverseMapping(device.serial, proxyPort))
                assertEquals(
                    "Unexpected http_proxy on ${device.serial}",
                    "localhost:$proxyPort",
                    readGlobalSetting(device.serial, "http_proxy")
                )
                assertTrue(
                    "Device ${device.serial} cannot reach http://0.0.0.0:$proxyPort while connected.",
                    canDeviceAccessHttp(device.serial, "0.0.0.0", proxyPort)
                )
            }

            assertTrue("disconnectAllDevices failed.\n${logs.joinToString("\n")}", controller.disconnectAllDevices(logs::add))

            devices.forEach { device ->
                assertFalse(
                    "Reverse mapping still present on ${device.serial}",
                    hasReverseMapping(device.serial, proxyPort)
                )
                assertTrue(
                    "Proxy settings are not fully cleared on ${device.serial}",
                    isProxyCleared(device.serial)
                )
                assertFalse(
                    "Device ${device.serial} can still reach http://0.0.0.0:$proxyPort after disconnect.",
                    canDeviceAccessHttp(device.serial, "0.0.0.0", proxyPort)
                )
                assertTrue(
                    "General connectivity appears broken on ${device.serial} after disconnect.",
                    canReachAnyInternetHost(device.serial)
                )
            }
        } finally {
            localServer?.close()
            controller.disconnectAllDevices {}
        }
    }

    @Test
    fun connectOneAndDisconnectOthers_keepsOnlySelectedDeviceConnected() {
        val devices = controller.listConnectedDevices(logs::add)
        val emulators = devices.filter { it.isEmulator }
        assumeTrue("At least one connected emulator is required.", emulators.isNotEmpty())
        assumeTrue("At least two connected devices are required.", devices.size >= 2)

        val selectedSerial = emulators.first().serial
        val localServer = ensureLocalEndpointListening(proxyPort)
        assumeTrue("No endpoint available on host at 127.0.0.1:$proxyPort.", isHostPortOpen("127.0.0.1", proxyPort))

        try {
            controller.disconnectAllDevices(logs::add)
            assertTrue(
                "connectEmulatorAndClearProxyOnOthers failed.\n${logs.joinToString("\n")}",
                controller.connectEmulatorAndClearProxyOnOthers(selectedSerial, logs::add)
            )

            assertTrue("Selected device reverse mapping missing.", hasReverseMapping(selectedSerial, proxyPort))
            assertEquals(
                "Selected device proxy is not configured.",
                "localhost:$proxyPort",
                readGlobalSetting(selectedSerial, "http_proxy")
            )
            assertTrue(
                "Selected device cannot reach http://0.0.0.0:$proxyPort.",
                canDeviceAccessHttp(selectedSerial, "0.0.0.0", proxyPort)
            )

            devices.filterNot { it.serial == selectedSerial }.forEach { other ->
                assertFalse("Unexpected reverse mapping on ${other.serial}", hasReverseMapping(other.serial, proxyPort))
                assertTrue("Proxy is not cleared on ${other.serial}", isProxyCleared(other.serial))
                assertFalse(
                    "Device ${other.serial} can still reach http://0.0.0.0:$proxyPort.",
                    canDeviceAccessHttp(other.serial, "0.0.0.0", proxyPort)
                )
            }
        } finally {
            localServer?.close()
            controller.disconnectAllDevices {}
        }
    }

    private fun ensureLocalEndpointListening(port: Int): LocalProbeServer? {
        if (isHostPortOpen("127.0.0.1", port)) {
            return null
        }
        return runCatching { LocalProbeServer(port) }.getOrNull()
    }

    private fun canReachAnyInternetHost(serial: String): Boolean =
        INTERNET_HOSTS.any { host -> canDeviceAccessHttp(serial, host, 80) }

    private fun hasNc(serial: String): Boolean {
        val result = runAdb(
            serial = serial,
            args = listOf(
                "shell",
                "sh",
                "-c",
                "command -v nc >/dev/null 2>&1 || toybox nc --help >/dev/null 2>&1"
            )
        )
        return result.exitCode == 0
    }

    private fun canDeviceAccessHttp(serial: String, host: String, port: Int): Boolean {
        val result = runAdb(
            serial = serial,
            args = listOf(
                "shell",
                "sh",
                "-c",
                "printf 'GET / HTTP/1.0\\r\\nHost: $host\\r\\nConnection: close\\r\\n\\r\\n' | nc -q 2 $host $port >/dev/null"
            )
        )
        return result.exitCode == 0
    }

    private fun hasReverseMapping(serial: String, port: Int): Boolean {
        val reverseToken = "tcp:$port"
        val result = runAdb(serial = serial, args = listOf("reverse", "--list"))
        if (result.exitCode != 0) {
            return false
        }
        return result.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .any { line ->
                val columns = line.split(Regex("\\s+"))
                when (columns.size) {
                    2 -> columns[0] == reverseToken && columns[1] == reverseToken
                    3 -> columns[1] == reverseToken && columns[2] == reverseToken
                    else -> false
                }
            }
    }

    private fun isProxyCleared(serial: String): Boolean {
        val proxy = readGlobalSetting(serial, "http_proxy")
        val host = readGlobalSetting(serial, "global_http_proxy_host")
        val port = readGlobalSetting(serial, "global_http_proxy_port")
        val pac = readGlobalSetting(serial, "global_http_proxy_pac")
        val proxyCleared = proxy.isBlank() || proxy == "null" || proxy == ":0"
        val hostCleared = host.isBlank() || host == "null"
        val portCleared = port.isBlank() || port == "null" || port == "0" || port == "-1"
        val pacCleared = pac.isBlank() || pac == "null"
        return proxyCleared && hostCleared && portCleared && pacCleared
    }

    private fun readGlobalSetting(serial: String, key: String): String {
        val result = runAdb(
            serial = serial,
            args = listOf("shell", "settings", "get", "global", key)
        )
        return result.output
            .lineSequence()
            .firstOrNull()
            .orEmpty()
            .replace("\r", "")
            .trim()
    }

    private fun runAdb(serial: String? = null, args: List<String>): CommandResult {
        val command = mutableListOf(ADB_BIN)
        if (!serial.isNullOrBlank()) {
            command += listOf("-s", serial)
        }
        command += args
        return runLocalCommand(*command.toTypedArray())
    }

    private fun runLocalCommand(vararg command: String): CommandResult {
        return try {
            val process = ProcessBuilder(command.toList())
                .directory(File("."))
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(200, TimeUnit.MILLISECONDS)
                return CommandResult(-2, "Timed out: ${command.joinToString(" ")}")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            CommandResult(process.exitValue(), output)
        } catch (error: Exception) {
            CommandResult(-1, error.message.orEmpty())
        }
    }

    private fun isHostPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 1000)
                true
            }
        } catch (_: ConnectException) {
            false
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    )

    private class LocalProbeServer(port: Int) : AutoCloseable {
        private val executor = Executors.newSingleThreadExecutor()
        private val server = HttpServer.create(InetSocketAddress("0.0.0.0", port), 0)

        init {
            server.createContext("/") { exchange ->
                val bytes = "proxy-commander".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { output -> output.write(bytes) }
            }
            server.executor = executor
            server.start()
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    companion object {
        private const val ENABLE_ENV = "PROXY_COMMANDER_RUN_INTEGRATION_TESTS"
        private const val PORT_ENV = "PROXY_COMMANDER_IT_PORT"
        private const val DEFAULT_PROXY_PORT = 8080
        private const val COMMAND_TIMEOUT_MS = 12_000L
        private val ADB_BIN = System.getenv("ADB").takeUnless { it.isNullOrBlank() } ?: "adb"
        private val INTERNET_HOSTS = listOf("example.com", "google.com", "cloudflare.com")
    }
}
