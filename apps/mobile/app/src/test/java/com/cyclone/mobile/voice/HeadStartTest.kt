package com.cyclone.mobile.voice

import com.cyclone.mobile.mind.lab.GoldenSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Plan 65 R1: work started at a pause is used only for exactly what it was started for. */
class HeadStartTest {
    private class Job(val name: String) { var cancelled = false }

    @Test fun `the same key gets the work, once`() {
        val slot = HeadStart<String, Job> { it.cancelled = true }
        assertTrue(slot.start("open the camera") { Job("a") })
        assertFalse("already running for it", slot.start("open the camera") { Job("b") })
        val got = slot.take("open the camera")
        assertEquals("a", got?.name)
        assertFalse(got!!.cancelled)
        assertNull("used once", slot.take("open the camera"))
    }

    @Test fun `other words cancel it`() {
        val slot = HeadStart<String, Job> { it.cancelled = true }
        val first = Job("a")
        slot.start("open the") { first }
        val second = Job("b")
        slot.start("open the camera") { second }
        assertTrue("replaced", first.cancelled)
        assertNull(slot.take("open the camera please"))
        assertTrue("a different final cancels it", second.cancelled)
    }

    @Test fun `clear cancels`() {
        val slot = HeadStart<String, Job> { it.cancelled = true }
        val job = Job("a")
        slot.start("x") { job }
        slot.clear()
        assertTrue(job.cancelled)
        assertNull(slot.take("x"))
    }

    // ---- the pause in the detector ---------------------------------------------------------------------------------------

    private val rate = VoiceActivity.SAMPLE_RATE
    private fun quiet(ms: Int) = ShortArray(rate * ms / 1000) { ((it * 7919) % 81 - 40).toShort() }
    private fun speech(ms: Int) = ShortArray(rate * ms / 1000) { i ->
        val t = i.toDouble() / rate
        (6_000 * (0.65 + 0.35 * sin(2 * PI * 4 * t)) * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t)) / 1.5).toInt().toShort()
    }

    @Test fun `a pause reports exactly the span speech end will report`() {
        val detector = VoiceActivity(VoiceActivity.Tuning(endSilenceMs = 700))
        val pauses = mutableListOf<VoiceActivity.Pause>()
        var heard: VoiceActivity.Result.Heard? = null
        for (part in listOf(quiet(300), speech(900), quiet(1_000))) {
            var i = 0
            while (i < part.size && heard == null) {
                val n = minOf(320, part.size - i)
                when (val r = detector.feed(part.copyOfRange(i, i + n))) {
                    is VoiceActivity.Result.Listening -> r.pause?.let { pauses += it }
                    is VoiceActivity.Result.Heard -> heard = r
                    else -> error("unexpected $r")
                }
                i += n
            }
        }
        assertNotNull(heard)
        assertTrue("a pause came before the end", pauses.isNotEmpty())
        assertEquals(setOf(heard!!.startMs to heard!!.endMs), pauses.map { it.startMs to it.endMs }.toSet())
        // The clip cut at the pause is the clip cut at the end: the padding is inside the pause.
        assertTrue(VoiceActivity.PAUSE_MS > 250)
    }

    @Test fun `talking on after a pause moves the span`() {
        val detector = VoiceActivity(VoiceActivity.Tuning(endSilenceMs = 700))
        val pauses = mutableListOf<VoiceActivity.Pause>()
        var heard: VoiceActivity.Result.Heard? = null
        for (part in listOf(quiet(300), speech(700), quiet(400), speech(600), quiet(1_000))) {
            var i = 0
            while (i < part.size && heard == null) {
                val n = minOf(320, part.size - i)
                when (val r = detector.feed(part.copyOfRange(i, i + n))) {
                    is VoiceActivity.Result.Listening -> r.pause?.let { pauses += it }
                    is VoiceActivity.Result.Heard -> heard = r
                    else -> error("unexpected $r")
                }
                i += n
            }
        }
        val spans = pauses.map { it.endMs }.distinct()
        assertEquals("two pauses, the first one superseded", 2, spans.size)
        assertEquals(heard!!.endMs, spans.last())
        assertTrue(GoldenSet.WORLD.apps.isNotEmpty())
    }
}
