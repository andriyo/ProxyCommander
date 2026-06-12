package io.github.andriyo.proxycommander

import com.intellij.openapi.application.ApplicationManager

/**
 * Shared "run a controller operation on a pooled thread" plumbing used by the actions, the
 * Devices dialog, and the reconnect service, so log collection and adb-availability handling
 * live in one place.
 */
internal object ProxyCommanderExecution {

    /** Last non-blank log line without the `[ProxyCommander]` prefix, or [fallback]. */
    fun summarize(logs: List<String>, fallback: String): String {
        val lastLog = logs.lastOrNull { it.isNotBlank() }?.removePrefix("[ProxyCommander] ")?.trim()
        return lastLog.takeUnless { it.isNullOrBlank() } ?: fallback
    }

    /**
     * Runs [operation] against a fresh controller on a pooled thread and reports the outcome via
     * [onFinished] (also invoked on that pooled thread). The operation is skipped when adb is
     * unavailable; the failure reason is part of the collected logs either way.
     */
    fun runControllerOperation(
        projectBasePath: String?,
        config: ProxyCommanderConfig,
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean,
        onFinished: (success: Boolean, logs: List<String>) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val logs = mutableListOf<String>()
            val controller = ProxyCommanderController(projectBasePath, config)
            val success = if (!controller.ensureAdbAvailable(logs::add)) {
                false
            } else {
                runCatching {
                    operation(controller, logs::add)
                }.getOrElse { error ->
                    logs += "[ProxyCommander] Error: ${error.message}"
                    false
                }
            }
            onFinished(success, logs)
        }
    }
}
