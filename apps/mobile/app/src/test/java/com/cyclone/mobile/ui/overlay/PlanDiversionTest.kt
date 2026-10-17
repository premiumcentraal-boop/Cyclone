package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDiversionTest {
    private fun mission(version: Int, note: String?, label: String?) = Mission("m1", "Send Sara the photos", MissionStatus.RUNNING, 0, 0, "m", "M",
        plan = listOf(
            MindPlanStep("Open Photos", "done"),
            MindPlanStep("Send in Instagram DM", MindPlanStep.DROPPED),
            MindPlanStep("Open WhatsApp", "doing", note = note, branch = true),
            MindPlanStep("Send the photos", "todo", branch = true),
        ),
        planVersion = version, planLabel = label,
        planHistory = listOf(listOf(MindPlanStep("Open Photos", "done"), MindPlanStep("Send in Instagram DM", "todo"), MindPlanStep("Confirm", "todo"))))

    @Test fun noDiversionUntilThePlanChanges() {
        assertNull(PlanDiversion.of(mission(1, null, null)))
    }

    @Test fun cycloneChangingCourseKeepsItsReasonAndTucksTheEarlierPlan() {
        val d = PlanDiversion.of(mission(2, "Her DMs are closed", "Changed course"))!!
        assertFalse(d.steered)
        assertEquals("Plan diverted", d.title)
        assertEquals("Her DMs are closed", d.reason)
        assertEquals(listOf("Send in Instagram DM"), d.dropped)
        assertEquals(listOf("Open WhatsApp", "Send the photos"), d.next.map { it.text })
        assertEquals(1, d.done)
        assertEquals("Earlier plan · 3 steps", d.tucked)
    }

    @Test fun theOwnersSteerIsNamedAsTheirsWithoutAReasonLine() {
        val d = PlanDiversion.of(mission(2, "You changed this", "You changed this"))!!
        assertTrue(d.steered)
        assertEquals("Plan diverted", d.title)
        assertNull(d.reason)
    }

    @Test fun theDiversionLivesInsideTheWorkingCardNotUnderIt() {
        val chrome = sequenceOf(File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt")).first { it.isFile }.readText()
        assertTrue(chrome.contains("OverlayWorkCard(task, diversion, thinking, minimize)"))
        assertFalse(chrome.contains("CyclonePlanView(mission, compact = true"))
    }
}
