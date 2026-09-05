package com.cyclone.mobile.runtime.session

import com.cyclone.mobile.ai.vision.live.AccessibilityScreenshotFrameSource
import com.cyclone.mobile.ai.vision.live.FrameSourceType
import com.cyclone.mobile.ai.vision.live.InMemoryLiveFrameBroker
import com.cyclone.mobile.ai.vision.live.LiveFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class V4FoundationTest {
    @Test
    fun defaultSessionIsStableDisplayZero() {
        val store = ExecutionSessionStore()
        val a = store.defaultForeground()
        val b = store.lookup(null)
        val c = store.lookup("")
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, a.sessionId)
        assertEquals(0, a.displayId)
        assertEquals(a.sessionId, b.sessionId)
        assertEquals(c.sessionId, a.sessionId)
        assertTrue(a.executable)
        assertEquals(ExecutionBackendKind.FOREGROUND_ACCESSIBILITY, a.backend)
        assertEquals(InputOwner.HUMAN, a.inputOwner)
    }

    @Test
    fun syntheticSessionsCoexistAndAreNotExecutable() {
        val store = ExecutionSessionStore()
        val one = store.registerSynthetic("hidden-a", 12, ExecutionBackendKind.VIRTUAL_DISPLAY)
        val two = store.registerSynthetic("hidden-b", 13, ExecutionBackendKind.SHIZUKU)
        assertTrue(!one.executable)
        assertTrue(!two.executable)
        assertEquals(one.sessionId, store.lookup("hidden-a").sessionId)
        assertEquals(3, store.snapshot().size)
        try {
            store.registerSynthetic("hidden-a", 14, ExecutionBackendKind.ROOT)
            fail("duplicate")
        } catch (_: SessionIdentityException) {
        }
    }

    @Test
    fun observationsDoNotCrossSessions() {
        val store = SessionObservationStore()
        store.publish("A", 0, "obs-a", "payload-a", 1)
        store.publish("B", 1, "obs-b", "payload-b", 2)
        assertEquals("obs-a", store.current("A")?.observationId)
        assertEquals("obs-b", store.current("B", 1)?.observationId)
        store.publish("A", 0, "obs-a2", "payload-a2", 3)
        assertEquals(2L, store.current("A")?.generation)
        try {
            store.associate("A", 0, "obs-b")
            fail("mismatch")
        } catch (_: SessionIdentityException) {
        }
        store.associate("A", 0, "obs-a2")
    }

    @Test
    fun framesAreIsolatedBoundedAndLatestDoesNotCapture() {
        val broker = InMemoryLiveFrameBroker(capacityPerSession = 2)
        val source = AccessibilityScreenshotFrameSource("A", 0)
        source.adaptOneShot(broker, 100, 200, "h1", 10, 1)
        broker.publish(LiveFrame("A", 0, 2, 11, 100, 200, FrameSourceType.ACCESSIBILITY_SCREENSHOT, "h2"))
        broker.publish(LiveFrame("A", 0, 3, 12, 100, 200, FrameSourceType.ACCESSIBILITY_SCREENSHOT, "h3"))
        broker.publish(LiveFrame("B", 1, 1, 13, 10, 10, FrameSourceType.SCRCPY, "b1"))
        assertEquals("h3", broker.latest("A")?.payloadHandle)
        assertEquals(2, broker.framesSince("A", 0).size)
        assertEquals("b1", broker.latest("B")?.payloadHandle)
        broker.clear("A")
        assertNull(broker.latest("A"))
        assertNotNull(broker.latest("B"))
        assertEquals(0, broker.captureInvocations)
        try {
            broker.publish(LiveFrame("B", 9, 2, 14, 10, 10, FrameSourceType.SCRCPY, "bad"))
            fail("mismatch")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun sessionStoreIsSafeUnderConcurrency() {
        val store = ExecutionSessionStore()
        val pool = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(40)
        repeat(40) { index ->
            pool.execute {
                try {
                    runCatching { store.registerSynthetic("s-$index", index + 1, ExecutionBackendKind.VIRTUAL_DISPLAY) }
                    store.lookup(null)
                    store.snapshot()
                } finally {
                    latch.countDown()
                }
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, store.defaultForeground().sessionId)
    }
}
