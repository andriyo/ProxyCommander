package io.github.andriyo.proxycommander

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProxyCommanderInternetProbeTest {

    @Test
    fun parseSuccess_acceptsAValidatedHttpStatusLine() {
        val result = ProxyCommanderInternetProbe.parseSuccess(
            "PROXY_COMMANDER_HTTP_OK|example.com|HTTP/1.1 204 No Content"
        )

        assertEquals("example.com", result?.host)
        assertEquals("HTTP/1.1 204 No Content", result?.statusLine)
    }

    @Test
    fun parseSuccess_rejectsACommandSuccessWithoutAnHttpResponse() {
        assertNull(
            ProxyCommanderInternetProbe.parseSuccess(
                "PROXY_COMMANDER_HTTP_OK|example.com|connected"
            )
        )
    }

    @Test
    fun parseSuccess_rejectsAnUnvalidatedServerStatusLine() {
        assertNull(ProxyCommanderInternetProbe.parseSuccess("HTTP/1.1 200 OK"))
    }

    @Test
    fun shellScriptUsesBoundedNetcatTimeoutsAndAllFallbackHosts() {
        val script = ProxyCommanderInternetProbe.shellScript()

        assertTrue(script.contains("nc_command -w 3 -q 1"))
        assertTrue(script.contains("toybox nc"))
        ProxyCommanderInternetProbe.internetHosts.forEach { host ->
            assertTrue(script.contains(host))
        }
    }

    @Test
    fun shellScriptRejectsHostsThatCouldBeInterpretedAsShellOrNetcatOptions() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyCommanderInternetProbe.shellScript(listOf("-e"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyCommanderInternetProbe.shellScript(listOf("example.com;reboot"))
        }
    }
}
