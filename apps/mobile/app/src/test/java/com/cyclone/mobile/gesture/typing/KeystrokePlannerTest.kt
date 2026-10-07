package com.cyclone.mobile.gesture.typing

import com.cyclone.mobile.gesture.HandsStyle
import com.cyclone.mobile.gesture.Pacing
import com.cyclone.mobile.gesture.SeededGestureRng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 52 runs 3-4: the rhythm of key-by-key typing, and the pause before acting. */
class KeystrokePlannerTest {
    @Test fun everyPlanTypesExactlyTheValue() {
        val values = listOf("sam.jones92", "sam@example.com", "Hello there, how are you today?", "Zürich 😀 café", "https://example.com/x?y=1",
            "a", "UPPER lower 123 !?", "x".repeat(80), "word ".repeat(60).trim())
        for (value in values) {
            for (seed in 0L until 200L) {
                for (typos in listOf(false, true)) {
                    val plan = KeystrokePlanner.plan(value, HandsStyle.NATURAL, typos, SeededGestureRng(seed))
                    assertEquals("seed $seed typos $typos", value, KeystrokePlanner.replay(plan))
                }
            }
        }
    }

    @Test fun typosOnlyInProseAndAlwaysCorrected() {
        assertFalse(KeystrokePlanner.typoSafe("sam.jones92"))
        assertFalse(KeystrokePlanner.typoSafe("sam@example.com"))
        assertFalse(KeystrokePlanner.typoSafe("see https://example.com now"))
        assertTrue(KeystrokePlanner.typoSafe("see you at the station tonight"))
        val prose = "see you at the station tonight, I will bring the tickets and some snacks"
        val withTypos = (0L until 400L).count { seed ->
            KeystrokePlanner.plan(prose, HandsStyle.NATURAL, true, SeededGestureRng(seed)).any { it.backspace }
        }
        assertTrue("typos happen now and then ($withTypos/400)", withTypos in 20..380)
        val username = (0L until 400L).count { seed ->
            KeystrokePlanner.plan("sam.jones92", HandsStyle.NATURAL, true, SeededGestureRng(seed)).any { it.backspace }
        }
        assertEquals(0, username)
        val off = (0L until 400L).count { seed -> KeystrokePlanner.plan(prose, HandsStyle.NATURAL, false, SeededGestureRng(seed)).any { it.backspace } }
        assertEquals(0, off)
    }

    @Test fun naturalTypingIsAQuickThumbAndNotAnEvenBeat() {
        val value = "the quick brown fox jumps"
        val plan = KeystrokePlanner.plan(value, HandsStyle.NATURAL, false, SeededGestureRng(5L))
        val gaps = plan.drop(1).map { it.delayMs }
        val perSecond = 1000.0 / gaps.average()
        assertTrue("about 6-11 keys a second, got $perSecond", perSecond in 5.0..11.0)
        assertTrue("gaps vary", gaps.toSet().size > gaps.size / 2)
        assertTrue(gaps.all { it >= 35L })
        val relaxed = KeystrokePlanner.plan(value, HandsStyle.RELAXED, false, SeededGestureRng(5L))
        assertTrue(KeystrokePlanner.totalMs(relaxed) > KeystrokePlanner.totalMs(plan))
    }

    @Test fun longValuesGetATypedOpeningAndStayWithinTheCap() {
        val long = "This is a long message that goes on. ".repeat(20).trim()
        val plan = KeystrokePlanner.plan(long, HandsStyle.NATURAL, false, SeededGestureRng(1L))
        assertEquals(long, KeystrokePlanner.replay(plan))
        assertTrue(plan.size < 60)
        assertTrue(plan.last().text!!.length > 100)
        val maxKeyed = KeystrokePlanner.plan("y".repeat(KeystrokePlanner.KEYED_CHARS), HandsStyle.NATURAL, false, SeededGestureRng(2L))
        assertTrue(KeystrokePlanner.totalMs(maxKeyed) <= KeystrokePlanner.NATURAL_CAP_MS)
    }

    @Test fun multiLineOrControlTextIsNotKeyed() {
        assertFalse(KeystrokePlanner.eligible("line one\nline two"))
        assertFalse(KeystrokePlanner.eligible(""))
        assertFalse(KeystrokePlanner.eligible("x".repeat(KeystrokePlanner.MAX_VALUE_CHARS + 1)))
        assertTrue(KeystrokePlanner.eligible("plain"))
    }

    @Test fun pausesAreBoundedAndCountTimeAlreadySpent() {
        repeat(500) { seed ->
            val natural = Pacing.pauseMs(HandsStyle.NATURAL, 2_000, 0L, SeededGestureRng(seed.toLong()))
            assertTrue(natural in 120L..Pacing.NATURAL_MAX_MS)
            val relaxed = Pacing.pauseMs(HandsStyle.RELAXED, 2_000, 0L, SeededGestureRng(seed.toLong()))
            assertTrue(relaxed in 280L..Pacing.RELAXED_MAX_MS)
            assertEquals(0L, Pacing.pauseMs(HandsStyle.PRECISE, 2_000, 0L, SeededGestureRng(seed.toLong())))
            assertEquals("a slow model already paused", 0L, Pacing.pauseMs(HandsStyle.NATURAL, 2_000, 5_000L, SeededGestureRng(seed.toLong())))
        }
    }
}
