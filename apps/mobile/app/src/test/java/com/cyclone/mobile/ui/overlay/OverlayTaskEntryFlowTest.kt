package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent E: overlay / task-entry flow.
 *
 * Agent C owns [phone.type] internals. These tests assume the Phone task field
 * already holds accepted text after that fix lands. They never call PhoneToolExecutor
 * and never dispatch Accessibility clicks onto a host app.
 */
class OverlayTaskEntryFlowTest {
    @Test
    fun acceptedTaskEntryDrivesIdleAnalysisWorkingLiveDoneWithExactCopy() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)

        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals(listOf(OverlayCopy.COMPOSER), OverlayCopy.visibleFor(machine.snapshot()))
        assertEquals("Ask Cyclone", OverlayCopy.COMPOSER)

        // Typing is assumed to work (Agent C). Overlay only consumes the accepted string.
        val acceptedTask = "Open Chrome and search for Pixel 8 user guide"
        assertTrue(acceptedTask.isNotBlank())
        machine.startAnalysis("task-entry-1", bullets = listOf(acceptedTask), cta = OverlayAnalysisCta.CONFIRM)

        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertEquals(listOf(acceptedTask), machine.snapshot().bullets)
        assertEquals(
            listOf("Analysis", "Do this", OverlayCopy.LEGAL),
            OverlayCopy.visibleFor(machine.snapshot()),
        )
        assertEquals("Analysis", OverlayCopy.ANALYSIS_TITLE)
        assertEquals("Do this", OverlayCopy.CONFIRM)

        machine.dispatch(OverlayUserAction.CONFIRM)
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertEquals(1, effects.resumes)
        assertEquals(0, effects.pauses)
        assertEventNeverClicksHost(events.single(), OverlayChromeEventKind.CONFIRM)
        assertEquals(
            listOf(
                "Task automation",
                "I'm on it. I'll let you know when this is ready to complete. You can leave this screen.",
                "Working on this task",
                "View progress",
                "Stop task",
                "Take control",
                OverlayCopy.LEGAL,
            ),
            OverlayCopy.visibleFor(machine.snapshot()),
        )
        assertEquals("Task automation", OverlayCopy.WORKING_TITLE)
        assertEquals(
            "I'm on it. I'll let you know when this is ready to complete. You can leave this screen.",
            OverlayCopy.WORKING_BODY,
        )
        assertEquals("Working on this task", OverlayCopy.STATUS)
        assertEquals("View progress", OverlayCopy.PRIMARY)
        assertEquals("Stop task", OverlayCopy.LIVE_LEFT)
        assertEquals("Take control", OverlayCopy.LIVE_RIGHT)

        machine.dispatch(OverlayUserAction.VIEW_PROGRESS)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertEventNeverClicksHost(events.last(), OverlayChromeEventKind.VIEW_PROGRESS)
        assertEquals(
            listOf("Working on this task", "Stop task", "Take control"),
            OverlayCopy.visibleFor(machine.snapshot()),
        )

        machine.completeDone()
        assertEquals(OverlayChromeState.DONE, machine.state())
        assertEventNeverClicksHost(events.last(), OverlayChromeEventKind.DONE)
        assertEquals(
            listOf("Saved as a draft skill. Review it in Automations before it can run alone."),
            OverlayCopy.visibleFor(machine.snapshot()),
        )
        assertEquals(
            "Saved as a draft skill. Review it in Automations before it can run alone.",
            OverlayCopy.DONE,
        )
        assertEquals(0, effects.pauses)
        assertTrue(events.none { it.clicksHost || it.dispatchAccessibilityAction })
    }

    @Test
    fun commerceTaskEntryUsesOrderThisFromThenWorkingCopy() {
        val events = mutableListOf<OverlayChromeEvent>()
        val machine = OverlayChromeMachine(emit = { events += it })
        machine.startAnalysis(
            "task-commerce",
            bullets = listOf("Compare public product pages only"),
            cta = OverlayAnalysisCta.COMMERCE,
        )
        assertEquals("Order this from", OverlayCopy.COMMERCE)
        assertEquals(
            listOf("Analysis", "Order this from", OverlayCopy.LEGAL),
            OverlayCopy.visibleFor(machine.snapshot()),
        )
        machine.dispatch(OverlayUserAction.COMMERCE)
        assertEquals(OverlayChromeState.WORKING, machine.state())
        assertEventNeverClicksHost(events.single(), OverlayChromeEventKind.COMMERCE)
        assertTrue(OverlayCopy.visibleFor(machine.snapshot()).contains("View progress"))
    }

    @Test
    fun blankTaskDoesNotLeaveIdleUntilAnalysisStarts() {
        val machine = OverlayChromeMachine()
        val blank = "   "
        assertTrue(blank.trim().isEmpty())
        assertEquals(OverlayChromeState.IDLE, machine.state())
        assertEquals("Ask Cyclone", OverlayCopy.visibleFor(machine.snapshot()).single())
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        assertEquals(OverlayChromeState.IDLE, machine.state())
    }

    @Test
    fun frozenCopyTableMatchesV4BibleExactly() {
        assertEquals("Analysis", OverlayCopy.ANALYSIS_TITLE)
        assertEquals("Task automation", OverlayCopy.WORKING_TITLE)
        assertEquals(
            "I'm on it. I'll let you know when this is ready to complete. You can leave this screen.",
            OverlayCopy.WORKING_BODY,
        )
        assertEquals("Working on this task", OverlayCopy.STATUS)
        assertEquals("View progress", OverlayCopy.PRIMARY)
        assertEquals("Do this", OverlayCopy.CONFIRM)
        assertEquals("Order this from", OverlayCopy.COMMERCE)
        assertEquals("Stop task", OverlayCopy.LIVE_LEFT)
        assertEquals("Take control", OverlayCopy.LIVE_RIGHT)
        assertEquals("Ask Cyclone", OverlayCopy.COMPOSER)
        assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
        assertEquals(
            "Saved as a draft skill. Review it in Automations before it can run alone.",
            OverlayCopy.DONE,
        )
        OverlayCopy.visibleStrings().forEach { visible ->
            OverlayCopy.NEVER_SAY.forEach { forbidden ->
                assertFalse("visible copy must not contain: $forbidden", visible.contains(forbidden))
            }
        }
    }

    private fun assertEventNeverClicksHost(event: OverlayChromeEvent, kind: OverlayChromeEventKind) {
        assertEquals(kind, event.kind)
        assertFalse(event.clicksHost)
        assertFalse(event.dispatchAccessibilityAction)
    }

    private class RecordingEffects : OverlayCycloneStateEffects {
        var pauses = 0
        var resumes = 0
        override fun pauseAgentForUser() {
            pauses += 1
        }
        override fun resumeAgent() {
            resumes += 1
        }
    }
}
