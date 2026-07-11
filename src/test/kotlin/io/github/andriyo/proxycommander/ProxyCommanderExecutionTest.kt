package io.github.andriyo.proxycommander

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ProxyCommanderExecutionTest {

    @Test
    fun mutationCoordinator_serializesConcurrentOperations() {
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)

        try {
            val first = executor.submit {
                ProxyCommanderMutationCoordinator.run {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

            val second = executor.submit {
                secondAttempting.countDown()
                ProxyCommanderMutationCoordinator.run {
                    secondEntered.set(true)
                }
            }

            assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
            assertFalse(secondEntered.get())
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertTrue(secondEntered.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun mutationCoordinator_preservesSubmissionOrderWhileEarlierWorkIsBlocked() {
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val order = mutableListOf<String>()

        ProxyCommanderMutationCoordinator.execute {
            blockerEntered.countDown()
            releaseBlocker.await(2, TimeUnit.SECONDS)
        }
        assertTrue(blockerEntered.await(2, TimeUnit.SECONDS))

        ProxyCommanderMutationCoordinator.execute { order += "connect" }
        ProxyCommanderMutationCoordinator.execute {
            order += "disconnect"
            completed.countDown()
        }

        releaseBlocker.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("connect", "disconnect"), order)
    }
}
