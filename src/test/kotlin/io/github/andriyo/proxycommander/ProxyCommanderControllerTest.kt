package io.github.andriyo.proxycommander

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unit tests for the controller's adb command sequencing, run against an in-memory fake device
 * state — no adb binary, IDE, or hardware required.
 */
class ProxyCommanderControllerTest {

    @Test
    fun connectDevice_enablesReverseAndProxy() {
        val adb = FakeAdb("emulator-5554")
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).connectDevice("emulator-5554", logs::add))

        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
        assertEquals("localhost:8888", adb.globalSetting("emulator-5554", "http_proxy"))
    }

    @Test
    fun connectDevice_failsWhenDeviceNotConnected() {
        val adb = FakeAdb("emulator-5554")
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).connectDevice("emulator-9999", logs::add))
        assertTrue(logs.any { it.contains("not connected") })
    }

    @Test
    fun connectDevice_failsBeforeMutationWhenExistingProxyCannotBeRead() {
        val adb = FakeAdb("emulator-5554")
        adb.failNextProxySnapshotRead = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).connectDevice("emulator-5554", logs::add))

        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
        assertTrue(adb.executedCommands.none { it.contains("reverse tcp:8888 tcp:8888") })
        assertTrue(adb.executedCommands.none { it.contains("settings put global http_proxy") })
        assertTrue(logs.any { it.contains("Failed to read the existing proxy configuration") })
    }

    @Test
    fun connectDevice_failsWhenReverseCannotBeEnabled() {
        val adb = FakeAdb("emulator-5554")
        adb.failReverseAdd = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).connectDevice("emulator-5554", logs::add))
        assertEquals(null, adb.globalSetting("emulator-5554", "http_proxy"))
        assertTrue(adb.executedCommands.none { it.contains("settings put global http_proxy") })
        assertTrue(adb.executedCommands.none { it.contains("time_detector") })
        assertTrue(logs.any { it.contains("Failed to enable reverse") })
    }

    @Test
    fun connectDevice_failsWhenProxyVerificationFails() {
        val adb = FakeAdb("emulator-5554")
        adb.dropSettingsPuts = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).connectDevice("emulator-5554", logs::add))
        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
        assertTrue(adb.executedCommands.none { it.contains("time_detector") })
        assertTrue(logs.any { it.contains("Proxy verification failed") })
        assertTrue(logs.any { it.contains("Rolled back newly enabled reverse mapping") })
    }

    @Test
    fun connectDevice_clearsAppliedProxyBeforeRollingBackReverseWhenVerificationFails() {
        val adb = FakeAdb("emulator-5554")
        adb.misreportNextProxyVerificationAfterPut = true

        assertFalse(controller(adb).connectDevice("emulator-5554") {})

        assertEquals(null, adb.globalSetting("emulator-5554", "http_proxy"))
        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
        val proxyClearIndex = adb.executedCommands.indexOfFirst {
            it.contains("settings delete global http_proxy")
        }
        val reverseRemovalIndex = adb.executedCommands.indexOfFirst {
            it.contains("reverse --remove tcp:8888")
        }
        assertTrue(proxyClearIndex >= 0)
        assertTrue(reverseRemovalIndex > proxyClearIndex)
    }

    @Test
    fun connectDevice_restoresPreviousProxyWhenVerificationFails() {
        val adb = FakeAdb("emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "proxy.example:3128")
        adb.misreportNextProxyVerificationAfterPut = true

        assertFalse(controller(adb).connectDevice("emulator-5554") {})

        assertEquals("proxy.example:3128", adb.globalSetting("emulator-5554", "http_proxy"))
        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
    }

    @Test
    fun connectDevice_restoresCompletePacProxyConfigurationWhenVerificationFails() {
        val adb = FakeAdb("emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "global_http_proxy_pac", "http://proxy.example/proxy.pac")
        adb.presetGlobalSetting("emulator-5554", "global_http_proxy_exclusion_list", "localhost,127.0.0.1")
        adb.misreportNextProxyVerificationAfterPut = true

        assertFalse(controller(adb).connectDevice("emulator-5554") {})

        assertEquals(null, adb.globalSetting("emulator-5554", "http_proxy"))
        assertEquals(
            "http://proxy.example/proxy.pac",
            adb.globalSetting("emulator-5554", "global_http_proxy_pac")
        )
        assertEquals(
            "localhost,127.0.0.1",
            adb.globalSetting("emulator-5554", "global_http_proxy_exclusion_list")
        )
        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
    }

    @Test
    fun connectDevice_failsWhenReverseAddReportsSuccessButMappingIsMissing() {
        val adb = FakeAdb("emulator-5554")
        adb.dropReverseAdds = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).connectDevice("emulator-5554", logs::add))

        assertEquals(null, adb.globalSetting("emulator-5554", "http_proxy"))
        assertTrue(adb.executedCommands.none { it.contains("time_detector") })
        assertTrue(logs.any { it.contains("mapping tcp:8888 is not present") })
    }

    @Test
    fun connectDevice_preservesExistingReverseWhenProxySetupFails() {
        val adb = FakeAdb("emulator-5554")
        adb.presetReverse("emulator-5554", "tcp:8888")
        adb.dropSettingsPuts = true

        assertFalse(controller(adb).connectDevice("emulator-5554") {})

        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
        assertTrue(adb.executedCommands.none { it.contains("reverse --remove tcp:8888") })
        assertTrue(adb.executedCommands.none { it.contains("time_detector") })
    }

    @Test
    fun disconnectDevice_removesReverseAndClearsProxy() {
        val adb = FakeAdb("emulator-5554")
        adb.presetReverse("emulator-5554", "tcp:8888")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        adb.presetGlobalSetting("emulator-5554", "global_http_proxy_host", "localhost")

        assertTrue(controller(adb).disconnectDevice("emulator-5554") {})

        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
        assertEquals(":0", adb.globalSetting("emulator-5554", "http_proxy"))
        assertEquals(null, adb.globalSetting("emulator-5554", "global_http_proxy_host"))
    }

    @Test
    fun disconnectDevice_verifiesAnActualHttpResponseAfterClearingProxy() {
        val adb = FakeAdb("emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        adb.internetProbeOutput = "PROXY_COMMANDER_HTTP_OK|example.com|HTTP/1.1 204 No Content"
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).disconnectDevice("emulator-5554", logs::add))

        assertTrue(logs.any { it.contains("Verified direct internet access") && it.contains("HTTP/1.1 204") })
    }

    @Test
    fun disconnectDevice_warnsButDoesNotFailCleanupWhenHttpResponseCannotBeVerified() {
        val adb = FakeAdb("emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        adb.internetProbeExitCode = 0
        adb.internetProbeOutput = "PROXY_COMMANDER_HTTP_OK|example.com|connected without an HTTP response"
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).disconnectDevice("emulator-5554", logs::add))

        assertEquals(":0", adb.globalSetting("emulator-5554", "http_proxy"))
        assertTrue(logs.any { it.contains("could not be verified") && it.contains("valid HTTP status line") })
    }

    @Test
    fun disconnectDevice_warnsWhenNoNetcatImplementationIsAvailable() {
        val adb = FakeAdb("emulator-5554")
        adb.internetProbeExitCode = 2
        adb.internetProbeOutput = ProxyCommanderInternetProbe.UNAVAILABLE_MARKER
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).disconnectDevice("emulator-5554", logs::add))

        assertTrue(logs.any { it.contains("does not provide the nc command") })
    }

    @Test
    fun disconnectAllDevices_surfacesInternetWarningWithoutReportingCleanupFailure() {
        val adb = FakeAdb("emulator-5554")
        adb.internetProbeExitCode = 1
        adb.internetProbeOutput = "PROXY_COMMANDER_HTTP_FAILED"
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).disconnectAllDevices(logs::add))

        assertTrue(logs.last().contains("Disconnect completed, but direct internet access could not be verified"))
    }

    @Test
    fun disconnectDevice_failsWhenReverseRemovalCommandFails() {
        val adb = FakeAdb("emulator-5554")
        adb.presetReverse("emulator-5554", "tcp:8888")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        adb.failReverseRemove = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).disconnectDevice("emulator-5554", logs::add))

        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
        assertEquals(":0", adb.globalSetting("emulator-5554", "http_proxy"))
        assertTrue(logs.any { it.contains("Failed to remove reverse mapping") })
    }

    @Test
    fun disconnectDevice_failsWhenReverseRemovalDoesNotTakeEffect() {
        val adb = FakeAdb("emulator-5554")
        adb.presetReverse("emulator-5554", "tcp:8888")
        adb.keepReverseAfterRemove = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).disconnectDevice("emulator-5554", logs::add))

        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
        assertTrue(logs.any { it.contains("mapping is still present") })
    }

    @Test
    fun disconnectDevice_succeedsWhenReverseIsAlreadyAbsent() {
        val adb = FakeAdb("emulator-5554")
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).disconnectDevice("emulator-5554", logs::add))

        assertTrue(adb.executedCommands.none { it.contains("reverse --remove tcp:8888") })
        assertTrue(logs.any { it.contains("already absent") })
    }

    @Test
    fun disconnectAllDevices_succeedsWhenNoDevicesAreConnected() {
        val adb = FakeAdb()
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).disconnectAllDevices(logs::add))

        assertTrue(logs.any { it.contains("No connected devices to disconnect") })
    }

    @Test
    fun disconnectAllDevices_failsWhenDeviceListingFails() {
        val adb = FakeAdb()
        adb.failDeviceListing = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).disconnectAllDevices(logs::add))

        assertTrue(logs.any { it.contains("Failed to list connected devices") })
        assertFalse(logs.any { it.contains("No connected devices to disconnect") })
    }

    @Test
    fun connectDevice_removesStaleReverseMappingsFromPreviousPorts() {
        val adb = FakeAdb("emulator-5554")
        adb.presetReverse("emulator-5554", "tcp:8888")
        val config = ProxyCommanderConfig(port = 9999, previousPorts = setOf(8888))

        assertTrue(controller(adb, config).connectDevice("emulator-5554") {})

        assertEquals(setOf("tcp:9999"), adb.reverseTokens("emulator-5554"))
    }

    @Test
    fun disconnectDevice_removesStaleReverseMappingsFromPreviousPorts() {
        val adb = FakeAdb("emulator-5554")
        adb.presetReverse("emulator-5554", "tcp:8888")
        adb.presetReverse("emulator-5554", "tcp:9999")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:9999")
        val config = ProxyCommanderConfig(port = 9999, previousPorts = setOf(8888))

        assertTrue(controller(adb, config).disconnectDevice("emulator-5554") {})

        assertTrue(adb.reverseTokens("emulator-5554").isEmpty())
        assertEquals(":0", adb.globalSetting("emulator-5554", "http_proxy"))
    }

    @Test
    fun keepOnlyDevice_disconnectsAllOtherDevices() {
        val adb = FakeAdb("emulator-5554", "emulator-5556")
        adb.presetReverse("emulator-5556", "tcp:8888")
        adb.presetGlobalSetting("emulator-5556", "http_proxy", "localhost:8888")

        assertTrue(controller(adb).keepOnlyDevice("emulator-5554") {})

        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
        assertTrue(adb.reverseTokens("emulator-5556").isEmpty())
        assertEquals(":0", adb.globalSetting("emulator-5556", "http_proxy"))
    }

    @Test
    fun keepOnlyDeviceOutcome_preservesSelectedIntentWhenOtherCleanupFails() {
        val adb = FakeAdb("emulator-5554", "emulator-5556")
        adb.presetReverse("emulator-5556", "tcp:8888")
        adb.presetGlobalSetting("emulator-5556", "http_proxy", "localhost:8888")
        adb.failReverseRemove = true

        val outcome = controller(adb).keepOnlyDeviceWithOutcome("emulator-5554") {}

        assertTrue(outcome.selectedConnected)
        assertFalse(outcome.cleanupSucceeded)
        assertFalse(outcome.success)
        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
    }

    @Test
    fun connectDeviceAndClearProxyOnOthers_acceptsPhysicalDevices() {
        val adb = FakeAdb("R3CN30ABCDE", "emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        adb.presetReverse("emulator-5554", "tcp:8888")

        assertTrue(controller(adb).connectDeviceAndClearProxyOnOthers("R3CN30ABCDE") {})

        assertEquals("localhost:8888", adb.globalSetting("R3CN30ABCDE", "http_proxy"))
        assertEquals(setOf("tcp:8888"), adb.reverseTokens("R3CN30ABCDE"))
        // Others get their proxy cleared but keep the reverse mapping (clear-only semantics).
        assertEquals(":0", adb.globalSetting("emulator-5554", "http_proxy"))
        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
    }

    @Test
    fun connectDeviceAndClearProxyOnOthers_leavesOthersUnchangedWhenActiveConnectFails() {
        val adb = FakeAdb("R3CN30ABCDE", "emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        adb.presetReverse("emulator-5554", "tcp:8888")
        adb.failReverseAdd = true
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).connectDeviceAndClearProxyOnOthers("R3CN30ABCDE", logs::add))

        assertEquals("localhost:8888", adb.globalSetting("emulator-5554", "http_proxy"))
        assertEquals(setOf("tcp:8888"), adb.reverseTokens("emulator-5554"))
        assertTrue(logs.any { it.contains("left unchanged") })
    }

    @Test
    fun listConnectedDeviceDetails_readsPropsAndProxyStateInDeviceOrder() {
        val adb = FakeAdb("emulator-5554", "R3CN30ABCDE")
        adb.getpropBySerial["emulator-5554"] = """
            [ro.product.model]: [sdk_gphone64_arm64]
            [ro.build.version.sdk]: [34]
            [ro.boot.qemu.avd_name]: [Pixel_7_API_34]
        """.trimIndent()
        adb.getpropBySerial["R3CN30ABCDE"] = """
            [ro.product.model]: [Pixel 6]
            [ro.build.version.sdk]: [33]
        """.trimIndent()
        adb.presetGlobalSetting("R3CN30ABCDE", "http_proxy", "localhost:8888")
        adb.presetReverse("R3CN30ABCDE", "tcp:8888")

        val details = controller(adb).listConnectedDeviceDetails()

        assertEquals(listOf("emulator-5554", "R3CN30ABCDE"), details.map { it.serial })

        val emulator = details[0]
        assertEquals("Pixel_7_API_34", emulator.identifier)
        assertEquals("Pixel 7 API 34", emulator.name)
        assertEquals("34", emulator.apiLevel)
        assertTrue(emulator.isEmulator)
        assertFalse(emulator.isProxyConnected)

        val physical = details[1]
        assertEquals("R3CN30ABCDE", physical.identifier)
        assertEquals("Pixel 6", physical.name)
        assertEquals("33", physical.apiLevel)
        assertFalse(physical.isEmulator)
        assertTrue(physical.isProxyConnected)
    }

    @Test
    fun connectDevice_reportsTimeSyncFailureWhenTimeDetectorUnsupported() {
        val adb = FakeAdb("emulator-5554")
        adb.timeDetectorSupported = false
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).connectDevice("emulator-5554", logs::add))

        assertTrue(logs.any { it.contains("Could not force clock") })
        assertFalse(logs.any { it.contains("Forced clock") })
        assertTrue(adb.executedCommands.none { it.contains("time_detector confirm_time") })
    }

    @Test
    fun connectDevice_reportsTimeSyncSuccessWhenTimeDetectorWorks() {
        val adb = FakeAdb("emulator-5554")
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).connectDevice("emulator-5554", logs::add))

        assertTrue(logs.any { it.contains("Forced clock") })
    }

    @Test
    fun connectDevice_doesNotReportTimeSyncSuccessWhenConfirmationIsFalse() {
        val adb = FakeAdb("emulator-5554")
        adb.timeDetectorConfirmOutput = "false"
        val logs = mutableListOf<String>()

        assertTrue(controller(adb).connectDevice("emulator-5554", logs::add))

        assertFalse(logs.any { it.contains("Forced clock") })
        assertTrue(logs.any { it.contains("confirm_time did not verify") })
    }

    @Test
    fun ensureAdbAvailable_failsForAnyNonzeroResult() {
        val adb = FakeAdb()
        adb.availabilityResult = AdbCommandResult(
            exitCode = -2,
            output = "Timed out after 3000ms",
            command = listOf("adb", "version")
        )
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).ensureAdbAvailable(logs::add))

        assertTrue(logs.any { it.contains("ADB availability check failed (exit -2)") })
        assertTrue(logs.any { it.contains("Timed out after 3000ms") })
    }

    @Test
    fun connectDevice_skipsTimeSyncWhenDisabled() {
        val adb = FakeAdb("emulator-5554")
        val config = ProxyCommanderConfig(port = 8888, resetTimeOnConnect = false)

        assertTrue(controller(adb, config).connectDevice("emulator-5554") {})

        assertTrue(adb.executedCommands.none { it.contains("time_detector") })
    }

    @Test
    fun testProxyConnection_failsWhenDeviceProxyNotConfigured() {
        val adb = FakeAdb("emulator-5554")
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).testProxyConnection("emulator-5554", logs::add))
        assertTrue(logs.any { it.contains("not configured") })
    }

    @Test
    fun testProxyConnection_failsWhenReverseMappingMissing() {
        val adb = FakeAdb("emulator-5554")
        adb.presetGlobalSetting("emulator-5554", "http_proxy", "localhost:8888")
        val logs = mutableListOf<String>()

        assertFalse(controller(adb).testProxyConnection("emulator-5554", logs::add))
        assertTrue(logs.any { it.contains("Reverse mapping") })
    }

    private fun controller(
        adb: FakeAdb,
        config: ProxyCommanderConfig = ProxyCommanderConfig(port = 8888)
    ): ProxyCommanderController = ProxyCommanderController(adb, config)

    /**
     * In-memory adb double: tracks reverse mappings and global settings per serial and answers
     * the command shapes the controller issues. Thread-safe because device details are read in
     * parallel.
     */
    private class FakeAdb(vararg serials: String) : AdbCommander {
        val executedCommands = CopyOnWriteArrayList<String>()
        val getpropBySerial = ConcurrentHashMap<String, String>()

        var availabilityResult = AdbCommandResult(
            0,
            "Android Debug Bridge version 1.0.41",
            listOf("adb", "version")
        )
        var failReverseAdd = false
        var dropReverseAdds = false
        var failReverseRemove = false
        var keepReverseAfterRemove = false
        var failDeviceListing = false

        /** Makes `settings put` a silent no-op so post-write verification fails. */
        var dropSettingsPuts = false
        var failNextProxySnapshotRead = false
        var misreportNextProxyVerificationAfterPut = false
        var timeDetectorSupported = true
        var timeDetectorConfirmOutput = "true"
        var internetProbeExitCode = 0
        var internetProbeOutput = "PROXY_COMMANDER_HTTP_OK|example.com|HTTP/1.1 200 OK"

        private val devices = serials.toList()
        private val globalSettings = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
        private val reverseMappings = ConcurrentHashMap<String, MutableSet<String>>()
        private var misreportProxyRead = false

        fun reverseTokens(serial: String): Set<String> = reverseMappings[serial].orEmpty().toSet()

        fun globalSetting(serial: String, key: String): String? = globalSettings[serial]?.get(key)

        fun presetReverse(serial: String, token: String) {
            reverseMappings.getOrPut(serial) { ConcurrentHashMap.newKeySet() }.add(token)
        }

        fun presetGlobalSetting(serial: String, key: String, value: String) {
            globalSettings.getOrPut(serial) { ConcurrentHashMap() }[key] = value
        }

        override fun checkAvailability(): AdbCommandResult = availabilityResult

        override fun trackDevices(
            shouldStop: () -> Boolean,
            onSnapshot: (Set<String>) -> Unit,
            log: (String) -> Unit
        ): Boolean = true

        override fun run(
            serial: String?,
            args: List<String>,
            allowFailure: Boolean,
            timeoutMs: Long
        ): AdbCommandResult {
            executedCommands += (listOfNotNull(serial) + args).joinToString(" ")
            val device = serial.orEmpty()
            return when {
                args == listOf("start-server") -> ok(args)
                args == listOf("version") -> ok(args, "Android Debug Bridge version 1.0.41")
                args == listOf("devices") ->
                    if (failDeviceListing) {
                        fail(args, "adb: failed to list devices")
                    } else {
                        ok(args, buildString {
                            appendLine("List of devices attached")
                            devices.forEach { appendLine("$it\tdevice") }
                        })
                    }
                args == listOf("reverse", "--list") ->
                    ok(args, reverseTokens(device).joinToString("\n") { "$it $it" })
                args.size == 3 && args.take(2) == listOf("reverse", "--remove") -> {
                    when {
                        failReverseRemove -> fail(args, "adb: error: reverse removal failed")
                        keepReverseAfterRemove -> ok(args)
                        else -> {
                            reverseMappings[device]?.remove(args[2])
                            ok(args)
                        }
                    }
                }
                args.size == 3 && args[0] == "reverse" ->
                    if (failReverseAdd) {
                        fail(args, "adb: error: cannot bind listener")
                    } else {
                        if (!dropReverseAdds) {
                            presetReverse(device, args[1])
                        }
                        ok(args)
                    }
                args == listOf("shell", "settings", "list", "global") -> {
                    if (failNextProxySnapshotRead) {
                        failNextProxySnapshotRead = false
                        fail(args, "adb: settings read failed")
                    } else {
                        ok(
                            args,
                            globalSettings[device].orEmpty().entries.joinToString("\n") { (key, value) -> "$key=$value" }
                        )
                    }
                }
                args.size == 5 && args.take(4) == listOf("shell", "settings", "get", "global") -> {
                    if (args[4] == "http_proxy" && misreportProxyRead) {
                        misreportProxyRead = false
                        ok(args, "null")
                    } else {
                        ok(args, globalSetting(device, args[4]) ?: "null")
                    }
                }
                args.size == 6 && args.take(4) == listOf("shell", "settings", "put", "global") -> {
                    if (!dropSettingsPuts) {
                        presetGlobalSetting(device, args[4], args[5])
                        if (
                            args[4] == "http_proxy" &&
                            args[5].startsWith("localhost:") &&
                            misreportNextProxyVerificationAfterPut
                        ) {
                            misreportProxyRead = true
                            misreportNextProxyVerificationAfterPut = false
                        }
                    }
                    ok(args)
                }
                args.size == 5 && args.take(4) == listOf("shell", "settings", "delete", "global") -> {
                    globalSettings[device]?.remove(args[4])
                    ok(args)
                }
                args == listOf("shell", "cat", "/proc/uptime") -> ok(args, "123.45 678.90")
                args.take(3) == listOf("shell", "cmd", "time_detector") ->
                    if (timeDetectorSupported) {
                        when (args.getOrNull(3)) {
                            "confirm_time" -> ok(args, timeDetectorConfirmOutput)
                            "get_time_state" -> ok(args, "TimeState{confirmed}")
                            else -> ok(args)
                        }
                    } else {
                        fail(args, "Error: unknown command 'time_detector'")
                    }
                args == listOf("shell", "getprop") -> ok(args, getpropBySerial[device].orEmpty())
                args.take(2) == listOf("shell", "getprop") -> ok(args, "")
                args.size == 4 &&
                    args.take(3) == listOf("shell", "sh", "-c") &&
                    args[3].contains("PROXY_COMMANDER_HTTP_OK") ->
                    AdbCommandResult(internetProbeExitCode, internetProbeOutput, listOf("adb") + args)
                args.take(2) == listOf("emu", "avd") -> ok(args, "TestAvd\nOK")
                else -> ok(args)
            }
        }

        private fun ok(args: List<String>, output: String = ""): AdbCommandResult =
            AdbCommandResult(0, output.trim(), listOf("adb") + args)

        private fun fail(args: List<String>, output: String): AdbCommandResult =
            AdbCommandResult(1, output, listOf("adb") + args)
    }
}
