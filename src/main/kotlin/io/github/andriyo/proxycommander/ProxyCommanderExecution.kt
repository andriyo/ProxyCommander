package io.github.andriyo.proxycommander

import com.intellij.util.concurrency.SequentialTaskExecutor
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** Executes proxy mutations in the exact order they were submitted. */
internal object ProxyCommanderMutationCoordinator {
    private val executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SequentialTaskExecutor.createSequentialApplicationPoolExecutor("Proxy Commander mutations")
    }
    private val coordinatorThread = ThreadLocal<Boolean>()

    fun execute(block: () -> Unit) {
        executor.execute {
            coordinatorThread.set(true)
            try {
                block()
            } finally {
                coordinatorThread.remove()
            }
        }
    }

    fun <T> run(block: () -> T): T {
        if (coordinatorThread.get() == true) {
            return block()
        }

        val task = FutureTask<T> { block() }
        execute(task::run)
        return try {
            task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}

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
        beforeOperation: () -> Unit = {},
        operation: (ProxyCommanderController, (String) -> Unit) -> Boolean,
        onFinished: (success: Boolean, logs: List<String>) -> Unit
    ) {
        // Submission happens synchronously, so two EDT actions retain their click order even if
        // the application pool is busy. The sequential executor itself runs the adb work off-EDT.
        ProxyCommanderMutationCoordinator.execute {
            val logs = mutableListOf<String>()
            val success = runCatching {
                beforeOperation()
                val controller = ProxyCommanderController(projectBasePath, config)
                if (!controller.ensureAdbAvailable(logs::add)) {
                    false
                } else {
                    operation(controller, logs::add)
                }
            }.getOrElse { error ->
                logs += "[ProxyCommander] Error: ${error.message}"
                false
            }
            onFinished(success, logs)
        }
    }
}
