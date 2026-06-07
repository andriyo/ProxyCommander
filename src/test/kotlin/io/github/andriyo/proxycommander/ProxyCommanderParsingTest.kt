package io.github.andriyo.proxycommander

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Device-free unit tests for the pure parsing/predicate logic. No adb or IDE required. */
class ProxyCommanderParsingTest {

    @Test
    fun parseDevices_keepsOnlyDeviceStateAndDetectsEmulators() {
        val output = """
            List of devices attached
            emulator-5554	device
            R3CN30ABCDE	device
            emulator-5556	offline
            0123456789	unauthorized

        """.trimIndent()

        val devices = ProxyCommanderParsing.parseDevices(output)

        assertEquals(listOf("emulator-5554", "R3CN30ABCDE"), devices.map { it.serial })
        assertTrue(devices.first { it.serial == "emulator-5554" }.isEmulator)
        assertFalse(devices.first { it.serial == "R3CN30ABCDE" }.isEmulator)
    }

    @Test
    fun parseDevices_handlesVerboseColumns() {
        val output = """
            List of devices attached
            emulator-5554          device product:sdk_gphone64 model:Pixel_7 device:emu64a
        """.trimIndent()

        val devices = ProxyCommanderParsing.parseDevices(output)

        assertEquals(listOf("emulator-5554"), devices.map { it.serial })
        assertTrue(devices.single().isEmulator)
    }

    @Test
    fun parseTrackedDevicesSnapshot_returnsDeviceStateSerials() {
        val snapshot = "emulator-5554\tdevice\nR3CN30ABCDE\tdevice\nemulator-5556\toffline\n"

        val serials = ProxyCommanderParsing.parseTrackedDevicesSnapshot(snapshot)

        assertEquals(setOf("emulator-5554", "R3CN30ABCDE"), serials)
    }

    @Test
    fun parseTrackedDevicesSnapshot_emptyPayloadIsEmptySet() {
        assertTrue(ProxyCommanderParsing.parseTrackedDevicesSnapshot("").isEmpty())
    }

    @Test
    fun parseGetpropOutput_parsesBracketedPairs() {
        val output = """
            [ro.product.model]: [Pixel 7]
            [ro.build.version.sdk]: [34]
            [ro.boot.qemu.avd_name]: [Pixel_7_API_34]
            [persist.sys.timezone]: []
            garbage line that should be ignored
        """.trimIndent()

        val props = ProxyCommanderParsing.parseGetpropOutput(output)

        assertEquals("Pixel 7", props["ro.product.model"])
        assertEquals("34", props["ro.build.version.sdk"])
        assertEquals("Pixel_7_API_34", props["ro.boot.qemu.avd_name"])
        assertEquals("", props["persist.sys.timezone"])
        assertNull(props["garbage"])
    }

    @Test
    fun parseUptimeMillis_convertsSecondsToMillis() {
        assertEquals(1234560L, ProxyCommanderParsing.parseUptimeMillis("1234.56 7890.12\n"))
        assertEquals(0L, ProxyCommanderParsing.parseUptimeMillis("0.00 0.00"))
    }

    @Test
    fun parseUptimeMillis_returnsNullForGarbage() {
        assertNull(ProxyCommanderParsing.parseUptimeMillis(""))
        assertNull(ProxyCommanderParsing.parseUptimeMillis("not-a-number rest"))
    }

    @Test
    fun containsReverseMapping_matchesTwoAndThreeColumnForms() {
        val token = "tcp:8888"
        assertTrue(ProxyCommanderParsing.containsReverseMapping("tcp:8888 tcp:8888", token))
        assertTrue(ProxyCommanderParsing.containsReverseMapping("host-1 tcp:8888 tcp:8888", token))
        assertFalse(ProxyCommanderParsing.containsReverseMapping("tcp:9999 tcp:9999", token))
        assertFalse(ProxyCommanderParsing.containsReverseMapping("", token))
        assertFalse(ProxyCommanderParsing.containsReverseMapping("tcp:8888 tcp:9999", token))
    }

    @Test
    fun isProxyCleared_acceptsCommonEmptyRepresentations() {
        assertTrue(ProxyCommanderParsing.isProxyCleared("", "", "", ""))
        assertTrue(ProxyCommanderParsing.isProxyCleared(":0", "null", "0", "null"))
        assertTrue(ProxyCommanderParsing.isProxyCleared("null", "", "-1", ""))
        assertFalse(ProxyCommanderParsing.isProxyCleared("localhost:8888", "", "", ""))
        assertFalse(ProxyCommanderParsing.isProxyCleared("", "10.0.0.1", "", ""))
        assertFalse(ProxyCommanderParsing.isProxyCleared("", "", "8888", ""))
        assertFalse(ProxyCommanderParsing.isProxyCleared("", "", "", "http://pac"))
    }

    @Test
    fun isEmulatorSerial_matchesEmulatorPattern() {
        assertTrue(ProxyCommanderParsing.isEmulatorSerial("emulator-5554"))
        assertFalse(ProxyCommanderParsing.isEmulatorSerial("R3CN30ABCDE"))
        assertFalse(ProxyCommanderParsing.isEmulatorSerial("emulator-"))
        assertFalse(ProxyCommanderParsing.isEmulatorSerial("emulator-5554-extra"))
    }
}
