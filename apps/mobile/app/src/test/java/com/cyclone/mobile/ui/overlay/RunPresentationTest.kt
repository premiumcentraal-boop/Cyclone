package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.ui.overlay.RunPresentation.Kind
import com.cyclone.mobile.ui.overlay.RunPresentation.Size
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Plan 70: the overlay is as big as the work. */
class RunPresentationTest {
    @After fun reset() = RunPresentation.resetForTests()

    @Test
    fun eachKindStartsAtItsOwnSize() {
        assertEquals(Size.WHISPER, RunPresentation.start(Kind.INSTANT))
        assertEquals("a cancel window has words to read", Size.ISLAND, RunPresentation.start(Kind.INSTANT, loud = true))
        assertEquals(Size.ISLAND, RunPresentation.start(Kind.FLASH))
        assertEquals(Size.CARD, RunPresentation.start(Kind.MIND))
        assertTrue(RunPresentation.isInstant("instant-1234"))
        assertFalse(RunPresentation.isInstant("mission-1234"))
    }

    @Test
    fun aRunOnlyGrows() {
        assertEquals(Size.ISLAND, RunPresentation.grow(Size.WHISPER, Size.ISLAND))
        assertEquals(Size.CARD, RunPresentation.grow(Size.ISLAND, Size.CARD))
        assertEquals("never shrinks on its own", Size.CARD, RunPresentation.grow(Size.CARD, Size.WHISPER))
    }

    @Test
    fun theEndingIsAHopOrAFewWords() {
        RunPresentation.ended(true, null)
        val hop = RunPresentation.ending.value!!
        assertNull(hop.words)
        assertEquals(RunPresentation.END_MS, RunPresentation.endMs(hop))
        RunPresentation.ended(false, "  Couldn't find Pokémon GO.  ")
        val words = RunPresentation.ending.value!!
        assertEquals("Couldn't find Pokémon GO.", words.words)
        assertTrue(words.seq > hop.seq)
        assertTrue(RunPresentation.endMs(words) > RunPresentation.END_MS)
        RunPresentation.ended(true, "x".repeat(400))
        assertEquals(160, RunPresentation.ending.value!!.words!!.length)
        assertEquals(RunPresentation.END_WORDS_MAX_MS, RunPresentation.endMs(RunPresentation.ending.value!!))
    }

    @Test
    fun anInstantRunWhispersAndAMissionDoesNot() {
        val m = OverlayChromeMachine(emit = {})
        m.startAnalysis("instant-1")
        m.enterWorking("instant-1")
        m.setWhisper(true)
        assertTrue(m.snapshot().whisper)
        m.updateStatus("Opening the camera")
        assertTrue("a status keeps the whisper", m.snapshot().whisper)
        // Promoted: the mission reports on the running chrome, and its own session turns the whisper off.
        m.setWhisper(false)
        assertFalse(m.snapshot().whisper)
        m.setWhisper(true)
        m.completeDone("instant-1")
        assertFalse("a finished run never whispers", m.snapshot().whisper)
        val idle = OverlayChromeMachine(emit = {})
        idle.setWhisper(true)
        assertFalse("only a working run can whisper", idle.snapshot().whisper)
    }

    @Test
    fun theIslandSaysWhatItDoesNotWhatItIs() {
        assertEquals("Opening WhatsApp" to "2 of 4", OverlayGlassCopy.island("✓ Opening WhatsApp", "Task in Cyclone Mind", 1, 4))
        assertEquals("Working" to "Cyclone", OverlayGlassCopy.island(null, "Opening Cyclone Mind", 0, null))
        assertEquals("Typing your reply" to "Replying to Sam · 3 of 7", OverlayGlassCopy.island("Typing your reply", "Replying to Sam", 2, 7))
        assertEquals("Find Sara", OverlayGlassCopy.label("✓ Find Sara"))
        assertEquals("Retry", OverlayGlassCopy.label("✗ ↳ Retry"))
        assertEquals("-5 degrees", OverlayGlassCopy.label("-5 degrees"))
    }

    @Test
    fun aDiversionWithNothingToUnfoldIsQuiet() {
        val bare = PlanDiversion(2, steered = false, reason = null, earlier = emptyList(), dropped = emptyList(),
            next = listOf(MindPlanStep("Open Gmail", "doing")), done = 1)
        assertTrue(bare.quiet)
        assertFalse(bare.copy(reason = "The button moved").quiet)
        assertFalse(bare.copy(earlier = listOf(MindPlanStep("Open Mail", "done"))).quiet)
    }

    @Test
    fun theWiringFollowsThePlan() {
        val root = File("src/main/java/com/cyclone/mobile")
        val modes = File(root, "mind/modes/CycloneModes.kt").readText()
        assertTrue("a cancel window speaks", "loud = true" in modes)
        assertTrue("an answer is the whisper's words", "ending = true to route.answer" in modes)
        val missions = File(root, "mind/mission/MindMissions.kt").readText()
        assertTrue("Flash starts folded", "fold = run.flash" in missions)
        assertTrue("Flash grows into the Mind's card", "missionGrow(run.taskId)" in missions)
        val chrome = File(root, "ui/overlay/OverlayChrome.kt").readText()
        assertTrue("WhisperCapsule(" in chrome && "BarFace.WHISPER" in chrome)
        assertTrue("an Instant run leaves no finished island", "minimized && !instantRun" in chrome)
    }
}
