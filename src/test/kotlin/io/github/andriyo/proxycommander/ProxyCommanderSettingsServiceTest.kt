package io.github.andriyo.proxycommander

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the persistence/normalization logic directly against a freshly-constructed service
 * instance. None of these methods touch the application container, so no IDE fixture is needed.
 */
class ProxyCommanderSettingsServiceTest {

    private fun service() = ProxyCommanderSettingsService()

    @Test
    fun rememberDevices_normalizesTrimsDedupesAndSorts() {
        val service = service()
        service.rememberDevices(
            listOf(
                RememberedDevice("beta", "Beta Phone"),
                RememberedDevice("alpha", ""),
                RememberedDevice("beta", "Beta Phone Updated"),
                RememberedDevice("  gamma  ", "  Gamma  "),
                RememberedDevice("   ", "blank-id-dropped")
            )
        )

        val devices = service.getRememberedDevices()
        assertEquals(listOf("alpha", "beta", "gamma"), devices.map { it.id })
        assertEquals("alpha", devices.first { it.id == "alpha" }.name) // blank name falls back to id
        assertEquals("Beta Phone Updated", devices.first { it.id == "beta" }.name) // later write for same id wins
        assertEquals("Gamma", devices.first { it.id == "gamma" }.name) // id and name are trimmed
        assertEquals(setOf("alpha", "beta", "gamma"), service.getRememberedDeviceIds())
    }

    @Test
    fun replaceAndClear_resetRememberedDevices() {
        val service = service()
        service.rememberDevices(listOf(RememberedDevice("a", "A"), RememberedDevice("b", "B")))
        service.replaceRememberedDevices(listOf(RememberedDevice("c", "C")))
        assertEquals(listOf("c"), service.getRememberedDevices().map { it.id })

        service.clearRememberedDevices()
        assertTrue(service.getRememberedDevices().isEmpty())
        assertTrue(service.getRememberedDeviceIds().isEmpty())
    }

    @Test
    fun loadState_migratesLegacyIdOnlyList() {
        val service = service()
        service.loadState(
            ProxyCommanderSettingsService.State(
                rememberedDeviceIds = mutableListOf("emulator-5554", "R3CN30ABCDE")
            )
        )

        assertEquals(
            listOf("R3CN30ABCDE", "emulator-5554"),
            service.getRememberedDevices().map { it.id }
        )
        // With no richer record, the id doubles as the display name.
        assertEquals("emulator-5554", service.getRememberedDevices().first { it.id == "emulator-5554" }.name)
    }

    @Test
    fun getConfig_fallsBackToDefaultPortWhenInvalid() {
        val service = service()
        service.updateConfig(port = 70000, adbPath = "  /opt/adb  ", resetTimeOnConnect = false)

        val config = service.getConfig()
        assertEquals(ProxyCommanderSettingsService.DEFAULT_PORT, config.port)
        assertEquals("/opt/adb", config.adbPath)
        assertEquals(false, config.resetTimeOnConnect)
    }

    @Test
    fun importLegacyState_keepsCustomizedValuesAndUnionsDevices() {
        val service = service()
        service.updateConfig(port = 9000, adbPath = "/custom/adb", resetTimeOnConnect = true)
        service.rememberDevices(listOf(RememberedDevice("existing", "Existing")))

        service.importLegacyState(
            ProxyCommanderSettingsService.State(
                port = 7000,
                adbPath = "/legacy/adb",
                rememberedDevices = mutableListOf(
                    ProxyCommanderSettingsService.RememberedDeviceState("legacy", "Legacy Device")
                )
            )
        )

        val config = service.getConfig()
        // Already-customized port/adb path must win over legacy values.
        assertEquals(9000, config.port)
        assertEquals("/custom/adb", config.adbPath)
        // Remembered devices are unioned across the migration.
        assertEquals(listOf("existing", "legacy"), service.getRememberedDevices().map { it.id })
    }

    @Test
    fun updateConfig_recordsPreviousPortsOnChange() {
        val service = service()

        service.updateConfig(port = 9999, adbPath = "", resetTimeOnConnect = true)
        assertEquals(setOf(8888), service.getConfig().previousPorts)

        service.updateConfig(port = 8080, adbPath = "", resetTimeOnConnect = true)
        assertEquals(setOf(8888, 9999), service.getConfig().previousPorts)

        // Returning to a previous port drops it from the history.
        service.updateConfig(port = 8888, adbPath = "", resetTimeOnConnect = true)
        assertEquals(setOf(9999, 8080), service.getConfig().previousPorts)
    }

    @Test
    fun updateConfig_samePortDoesNotGrowHistory() {
        val service = service()
        service.updateConfig(port = 8888, adbPath = "", resetTimeOnConnect = true)
        assertTrue(service.getConfig().previousPorts.isEmpty())
    }

    @Test
    fun ignoreDevice_normalizesAndDeduplicates() {
        val service = service()
        service.ignoreDevice("  emulator-5554  ")
        service.ignoreDevice("emulator-5554")
        service.ignoreDevice("   ")

        assertEquals(setOf("emulator-5554"), service.getIgnoredDeviceIds())
    }

    @Test
    fun rememberDevices_clearsIgnoreFlagForConnectedDevice() {
        val service = service()
        service.ignoreDevice("emulator-5554")
        service.ignoreDevice("R3CN30ABCDE")

        service.rememberDevices(listOf(RememberedDevice("emulator-5554", "Pixel 7")))

        // Connecting is an explicit opt-in, so the device is no longer ignored.
        assertEquals(setOf("R3CN30ABCDE"), service.getIgnoredDeviceIds())
    }

    @Test
    fun forgetDevice_removesOnlyThatDevice() {
        val service = service()
        service.rememberDevices(listOf(RememberedDevice("a", "A"), RememberedDevice("b", "B")))

        service.forgetDevice("a")

        assertEquals(listOf("b"), service.getRememberedDevices().map { it.id })
        assertEquals(setOf("b"), service.getRememberedDeviceIds())
    }

    @Test
    fun importLegacyState_carriesOverDisabledResetTime() {
        val service = service()

        service.importLegacyState(ProxyCommanderSettingsService.State(resetTimeOnConnect = false))

        assertFalse(service.getConfig().resetTimeOnConnect)
    }

    @Test
    fun importLegacyState_adoptsLegacyValuesWhenStillAtDefaults() {
        val service = service()

        service.importLegacyState(
            ProxyCommanderSettingsService.State(
                port = 7000,
                adbPath = "/legacy/adb",
                rememberedDeviceIds = mutableListOf("legacy-only")
            )
        )

        val config = service.getConfig()
        assertEquals(7000, config.port)
        assertEquals("/legacy/adb", config.adbPath)
        assertEquals(listOf("legacy-only"), service.getRememberedDevices().map { it.id })
    }
}
