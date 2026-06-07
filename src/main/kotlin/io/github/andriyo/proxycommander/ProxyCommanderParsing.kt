package io.github.andriyo.proxycommander

/**
 * Pure parsing/predicate helpers shared by the controller. Kept free of any adb or platform calls
 * so they can be unit-tested without a device or a running IDE.
 */
internal object ProxyCommanderParsing {
    val EMULATOR_SERIAL_REGEX = Regex("^emulator-[0-9]+$")

    // Matches a single `adb shell getprop` line: `[key]: [value]`.
    private val GETPROP_LINE_REGEX = Regex("""\[(.*?)]:\s*\[(.*?)]""")

    fun isEmulatorSerial(serial: String): Boolean = EMULATOR_SERIAL_REGEX.matches(serial)

    /** Parses the table printed by `adb devices`, keeping only entries in the `device` state. */
    fun parseDevices(output: String): List<ConnectedDevice> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of devices") }
            .mapNotNull { line ->
                val columns = line.split(WHITESPACE)
                if (columns.size < 2 || columns[1] != "device") {
                    null
                } else {
                    val serial = columns[0]
                    ConnectedDevice(serial = serial, isEmulator = isEmulatorSerial(serial))
                }
            }
            .toList()

    /** Parses a single `adb track-devices` payload into the set of serials in the `device` state. */
    fun parseTrackedDevicesSnapshot(snapshot: String): Set<String> =
        snapshot.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val columns = line.split(WHITESPACE)
                if (columns.size < 2 || columns[1] != "device") {
                    null
                } else {
                    columns[0]
                }
            }
            .toSet()

    fun parseGetpropOutput(output: String): Map<String, String> =
        output.lineSequence()
            .mapNotNull { line -> GETPROP_LINE_REGEX.find(line) }
            .associate { match -> match.groupValues[1] to match.groupValues[2] }

    fun parseUptimeMillis(output: String): Long? {
        val seconds = output
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.split(WHITESPACE)
            ?.firstOrNull()
            ?.toDoubleOrNull()
            ?: return null
        return (seconds * 1000).toLong()
    }

    fun containsReverseMapping(output: String, reverseToken: String): Boolean {
        val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.any { line ->
            val columns = line.split(WHITESPACE)
            when (columns.size) {
                2 -> columns[0] == reverseToken && columns[1] == reverseToken
                3 -> columns[1] == reverseToken && columns[2] == reverseToken
                else -> false
            }
        }
    }

    fun isProxyCleared(proxy: String, host: String, port: String, pac: String): Boolean {
        val proxyCleared = proxy.isBlank() || proxy == "null" || proxy == ":0"
        val hostCleared = host.isBlank() || host == "null"
        val portCleared = port.isBlank() || port == "null" || port == "0" || port == "-1"
        val pacCleared = pac.isBlank() || pac == "null"
        return proxyCleared && hostCleared && portCleared && pacCleared
    }

    private val WHITESPACE = Regex("\\s+")
}
