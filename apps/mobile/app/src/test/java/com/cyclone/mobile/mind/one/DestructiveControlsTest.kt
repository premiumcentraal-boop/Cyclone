package com.cyclone.mobile.mind.one

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestructiveControlsTest {
    private val timer = StepScreen("com.google.android.deskclock", "Clock", "timers", "f1",
        listOf(
            StepControl("e1", "Force stop", "button"),
            StepControl("e2", "Delete Timer", "button"),
            StepControl("e3", "Start", "button"),
            StepControl("e4", "Open alarm tab", "tab"),
        ),
        listOf("Timers"))

    @Test fun `a destructive control is hidden when the step does not name it`() {
        assertTrue(DestructiveControls.hidden("Force stop", "start a timer"))
        assertTrue(DestructiveControls.hidden("Delete Timer", "set a timer"))
    }

    @Test fun `a destructive control is offered when the step names the same word`() {
        assertFalse(DestructiveControls.hidden("Delete Timer", "delete the timer"))
        assertFalse(DestructiveControls.hidden("Force stop", "force stop the app"))
    }

    @Test fun `an ordinary control is never hidden`() {
        assertFalse(DestructiveControls.hidden("Start", "start a timer"))
        assertFalse(DestructiveControls.hidden("Open alarm tab", null))
    }

    @Test fun `a word that only contains a destructive word is not destructive`() {
        // "Reset" must match as a word, so "Resetting" and "Preset" are not destructive controls.
        assertFalse(DestructiveControls.hidden("Preset 1", "pick a preset"))
        assertFalse(DestructiveControls.hidden("Resetting", null))
    }

    @Test fun `the shortlist leaves destructive controls out unless the step names them`() {
        val step = Step("s1", "tap", target = "start the timer")
        val labels = FusedBoard.shortlist(timer, step).map { it.label }
        assertEquals(listOf("Start", "Open alarm tab"), labels)
        val named = Step("s2", "tap", target = "delete timer")
        assertTrue(FusedBoard.shortlist(timer, named).map { it.label }.contains("Delete Timer"))
    }
}
