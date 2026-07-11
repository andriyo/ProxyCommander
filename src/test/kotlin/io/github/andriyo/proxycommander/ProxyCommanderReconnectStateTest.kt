package io.github.andriyo.proxycommander

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Deterministic tests for reconnect generation/coalescing state; no IDE or adb is required. */
class ProxyCommanderReconnectStateTest {

    @Test
    fun beginGeneration_invalidatesOlderWork() {
        val state = ReconnectWorkState()
        val first = state.beginGeneration()!!

        assertTrue(state.isCurrent(first))

        val second = state.beginGeneration()!!

        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
    }

    @Test
    fun takeLatestSnapshot_usesGenerationStoredWithNewestSnapshot() {
        val state = ReconnectWorkState()
        val oldGeneration = state.beginGeneration()!!
        assertTrue(state.publishSnapshot(oldGeneration, setOf("old-device")))

        val newGeneration = state.beginGeneration()!!
        assertTrue(state.publishSnapshot(newGeneration, setOf("new-device")))

        // This can be called by a runnable queued for oldGeneration. The work itself still carries
        // newGeneration, so the runnable cannot consume the new snapshot and reject it as stale.
        val pending = state.takeLatestSnapshot()!!
        assertEquals(newGeneration, pending.generation)
        assertEquals(setOf("new-device"), pending.serials)
        assertTrue(state.isCurrent(pending.generation))
        assertNull(state.takeLatestSnapshot())
    }

    @Test
    fun staleSnapshotCallback_neverReplacesOrDeliversAfterNewGeneration() {
        val state = ReconnectWorkState()
        val oldGeneration = state.beginGeneration()!!
        val newGeneration = state.beginGeneration()!!
        val delivered = mutableListOf<Set<String>>()
        assertTrue(state.publishSnapshot(newGeneration, setOf("new-device"), delivered::add))

        // Models an old callback resuming after the new callback published and notified listeners.
        assertFalse(state.publishSnapshot(oldGeneration, setOf("stale-device"), delivered::add))

        val pending = state.takeLatestSnapshot()!!
        assertEquals(newGeneration, pending.generation)
        assertEquals(setOf("new-device"), pending.serials)
        assertEquals(setOf("new-device"), state.latestSnapshotSerials())
        assertEquals(listOf(setOf("new-device")), delivered)
    }

    @Test
    fun autoConnectSuppression_allowsReplacementWorkFromNewGeneration() {
        val state = ReconnectWorkState()
        val config = ProxyCommanderConfig(port = 8888)
        val oldGeneration = state.beginGeneration()!!
        val oldWork = ReconnectAutoConnectWork(oldGeneration, "emulator-5554", config)

        assertTrue(state.tryStartAutoConnect(oldWork))
        assertFalse(state.tryStartAutoConnect(oldWork))

        val newGeneration = state.beginGeneration()!!
        val replacement = ReconnectAutoConnectWork(newGeneration, "emulator-5554", config)

        // The obsolete serial is intentionally still in flight here.
        assertTrue(state.tryStartAutoConnect(replacement))
        assertFalse(state.isCurrent(oldWork.generation))
        assertTrue(state.isCurrent(replacement.generation))
    }

    @Test
    fun dispose_rejectsNewWorkAndClearsPendingSnapshot() {
        val state = ReconnectWorkState()
        val generation = state.beginGeneration()!!
        assertTrue(state.publishSnapshot(generation, setOf("emulator-5554")))

        assertTrue(state.dispose())

        assertFalse(state.isCurrent(generation))
        assertNull(state.beginGeneration())
        assertNull(state.takeLatestSnapshot())
        assertFalse(
            state.tryStartAutoConnect(
                ReconnectAutoConnectWork(generation, "emulator-5554", ProxyCommanderConfig())
            )
        )
        assertFalse(state.dispose())
    }

    @Test
    fun proxiedSerialState_marksSuccessfulConnectionImmediatelyAndIdempotently() {
        val state = ProxiedSerialState()
        assertTrue(state.replace(setOf("already-proxied")))

        assertTrue(state.markConnected("auto-connected"))
        assertEquals(setOf("already-proxied", "auto-connected"), state.get())

        assertFalse(state.markConnected("auto-connected"))
        assertFalse(state.replace(setOf("auto-connected", "already-proxied")))

        assertTrue(state.markDisconnected("auto-connected"))
        assertEquals(setOf("already-proxied"), state.get())
        assertFalse(state.markDisconnected("auto-connected"))
    }

    @Test
    fun proxiedSerialState_doesNotLetOlderStatusReadOverwriteSuccessfulConnect() {
        val state = ProxiedSerialState()
        val slowSnapshotRead = state.beginObservation()

        assertTrue(state.markConnected("emulator-5554"))

        // The adb read began first but completed later with pre-connect state.
        assertFalse(state.replace(slowSnapshotRead, emptySet()))
        assertEquals(setOf("emulator-5554"), state.get())
    }
}
