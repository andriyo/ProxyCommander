package io.github.andriyo.proxycommander

internal data class InternetProbeSuccess(
    val host: String,
    val statusLine: String
)

/** Builds and validates a device-side HTTP probe without relying on `nc`'s exit code alone. */
internal object ProxyCommanderInternetProbe {
    const val COMMAND_TIMEOUT_MS = 15_000L
    const val UNAVAILABLE_MARKER = "PROXY_COMMANDER_HTTP_UNAVAILABLE"

    private const val SUCCESS_MARKER = "PROXY_COMMANDER_HTTP_OK"
    private const val FAILED_MARKER = "PROXY_COMMANDER_HTTP_FAILED"
    private const val CONNECT_TIMEOUT_SECONDS = 3
    private const val QUIT_DELAY_SECONDS = 1
    private val validHost = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
    private val validStatusLine = Regex("HTTP/[0-9]+(?:\\.[0-9]+)?\\s+[1-5][0-9]{2}(?:\\s+.*)?")

    val internetHosts: List<String> = listOf("example.com", "google.com", "cloudflare.com")

    fun shellScript(hosts: List<String> = internetHosts, port: Int = 80): String {
        require(hosts.isNotEmpty()) { "At least one probe host is required." }
        require(hosts.all(validHost::matches)) { "Probe hosts must be DNS names or IPv4 addresses." }
        require(port in 1..65535) { "Probe port must be between 1 and 65535." }

        val hostList = hosts.joinToString(" ")
        return """
            if command -v nc >/dev/null 2>&1; then
              nc_command='nc'
            elif command -v toybox >/dev/null 2>&1 && toybox nc --help >/dev/null 2>&1; then
              nc_command='toybox nc'
            else
              printf '%s\n' '$UNAVAILABLE_MARKER'
              exit 2
            fi
            for host in $hostList; do
              status="${'$'}(printf 'GET / HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n' "${'$'}host" | ${'$'}nc_command -w $CONNECT_TIMEOUT_SECONDS -q $QUIT_DELAY_SECONDS "${'$'}host" $port 2>/dev/null | tr -d '\r' | head -n 1)"
              case "${'$'}status" in
                HTTP/[0-9]*)
                  printf '%s|%s|%s\n' '$SUCCESS_MARKER' "${'$'}host" "${'$'}status"
                  exit 0
                  ;;
              esac
            done
            printf '%s\n' '$FAILED_MARKER'
            exit 1
        """.trimIndent()
    }

    fun parseSuccess(output: String): InternetProbeSuccess? {
        val parts = output
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("$SUCCESS_MARKER|") }
            ?.split('|', limit = 3)
            ?: return null
        if (parts.size != 3 || !validHost.matches(parts[1]) || !validStatusLine.matches(parts[2])) {
            return null
        }
        return InternetProbeSuccess(host = parts[1], statusLine = parts[2])
    }
}
