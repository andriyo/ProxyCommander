package io.github.andriyo.proxycommander

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.concurrent.TimeUnit

internal fun isWindowsHost(): Boolean =
    System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

/**
 * Runs a host process to completion, draining stdout concurrently with the wait. Reading only
 * after `waitFor` would stall whenever a command prints more than the OS pipe buffer (~64 KB) —
 * the child blocks on a full pipe and the wait always times out. `getprop` dumps on prop-heavy
 * physical devices exceed that limit.
 */
internal object ProcessRunner {

    sealed class Result {
        data class Completed(val exitCode: Int, val output: String) : Result()
        data class TimedOut(val timeoutMs: Long) : Result()
        data class StartFailed(val error: Exception) : Result()
    }

    fun run(command: List<String>, workingDirectory: File? = null, timeoutMs: Long): Result {
        val process = try {
            val processBuilder = ProcessBuilder(command)
            workingDirectory?.let(processBuilder::directory)
            processBuilder.redirectErrorStream(true)
            processBuilder.start()
        } catch (error: Exception) {
            return Result.StartFailed(error)
        }

        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) {
                            break
                        }
                        synchronized(output) { output.append(buffer, 0, read) }
                    }
                }
            }
        }, "ProxyCommander-process-output")
        reader.isDaemon = true
        reader.start()

        return try {
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(200, TimeUnit.MILLISECONDS)
                reader.join(200)
                Result.TimedOut(timeoutMs)
            } else {
                // The reader hits EOF once the process exits; give it a moment to drain the tail.
                reader.join(2_000)
                Result.Completed(process.exitValue(), synchronized(output) { output.toString() }.trim())
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            Result.StartFailed(error)
        }
    }
}

/** Outcome of a single adb invocation. */
internal data class AdbCommandResult(
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

    companion object {
        const val COMMAND_UNAVAILABLE_EXIT_CODE = -3
    }
}

/** Abstraction over the adb binary; the controller is unit-tested against a fake implementation. */
internal interface AdbCommander {
    fun checkAvailability(): AdbCommandResult

    fun run(
        serial: String? = null,
        args: List<String>,
        allowFailure: Boolean = false,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AdbCommandResult

    fun trackDevices(
        shouldStop: () -> Boolean,
        onSnapshot: (Set<String>) -> Unit,
        log: (String) -> Unit
    ): Boolean

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

internal class AdbClient(
    private val workingDirectory: File?,
    adbPath: String
) : AdbCommander {
    private val configuredAdbPath = adbPath.takeIf { it.isNotBlank() }
    private val resolution = resolveAdbExecutable()
    private val adbExecutable = resolution.executable

    override fun checkAvailability(): AdbCommandResult =
        run(args = listOf("version"), allowFailure = true, timeoutMs = 3_000)

    override fun run(
        serial: String?,
        args: List<String>,
        allowFailure: Boolean,
        timeoutMs: Long
    ): AdbCommandResult {
        val command = mutableListOf(adbExecutable)
        if (!serial.isNullOrBlank()) {
            command += listOf("-s", serial)
        }
        command += args

        return when (val result = ProcessRunner.run(command, workingDirectory, timeoutMs)) {
            is ProcessRunner.Result.Completed ->
                AdbCommandResult(exitCode = result.exitCode, output = result.output, command = command)
            is ProcessRunner.Result.TimedOut ->
                AdbCommandResult(exitCode = -2, output = "Timed out after ${result.timeoutMs}ms", command = command)
            is ProcessRunner.Result.StartFailed ->
                if (result.error is IOException) {
                    AdbCommandResult(
                        exitCode = AdbCommandResult.COMMAND_UNAVAILABLE_EXIT_CODE,
                        output = unavailableMessage(),
                        command = command
                    )
                } else {
                    AdbCommandResult(
                        exitCode = -1,
                        output = result.error.message ?: "Failed to execute adb command.",
                        command = command
                    )
                }
        }
    }

    override fun trackDevices(
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
                    onSnapshot(ProxyCommanderParsing.parseTrackedDevicesSnapshot(payload.toString(Charsets.UTF_8)))
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

    private fun readTrackFrame(input: InputStream, size: Int): ByteArray? {
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

    private fun adbExecutableName(): String = if (isWindowsHost()) "adb.exe" else "adb"

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

    private companion object {
        const val ADB_TRACK_HEADER_SIZE = 4
    }
}
