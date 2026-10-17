package com.cyclone.mobile.ui.overlay.cy

import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** alpha.137: Cy, Cyclone's character (first concept). */
class CyTest {
    @Test
    fun handsOnlyWhenTheyMeanSomething() {
        assertEquals(setOf(CyMood.WORK, CyMood.ASK, CyMood.DONE), CyMood.entries.filter { it.hands }.toSet())
    }

    @Test
    fun onlyRealActivityLoops() {
        // The glass rule: still unless it means something. Idle, waiting and finished Cy move only in short gestures.
        assertEquals(setOf(CyMood.LISTEN, CyMood.THINK, CyMood.WORK), CyMood.entries.filter { it.loops }.toSet())
        assertTrue(CyMood.ASK.warm && CyMood.STUCK.warm && !CyMood.WORK.warm)
    }

    @Test
    fun theTaskStateSetsTheMood() {
        assertEquals(CyMood.WORK, CyMoods.of(CycloneTaskVisualState.WORKING))
        assertEquals(CyMood.ASK, CyMoods.of(CycloneTaskVisualState.ACTION_NEEDED))
        assertEquals(CyMood.DONE, CyMoods.of(CycloneTaskVisualState.DONE))
        assertEquals(CyMood.STUCK, CyMoods.of(CycloneTaskVisualState.FAILED))
        assertEquals(CyMood.PAUSE, CyMoods.of(CycloneTaskVisualState.WORKING, paused = true))
    }

    @Test
    fun handsWidenTheBoxOnly() {
        assertEquals(34.dp, CyMoods.width(34.dp, hands = false))
        assertEquals(34.dp * CyMoods.HANDS_SPAN, CyMoods.width(34.dp, hands = true))
    }

    @Test
    fun cyKeepsTheOverlaysRulesAndCanBeTurnedOff() {
        val root = File("src/main/java/com/cyclone/mobile/ui/overlay")
        val cy = File(root, "cy/Cy.kt").readText()
        // No window, no capture flag, no touch of its own: Cy is drawing inside surfaces that already exist.
        listOf("WindowManager", "FLAG_SECURE", "clickable", "pointerInput").forEach { assertFalse(it, it in cy) }
        assertTrue("Lite draws Cy still", "OverlayLite.active" in cy)
        val stack = File(root, "OverlayGlassStack.kt").readText()
        val chrome = File(root, "OverlayChrome.kt").readText()
        // Each place Cy appears keeps the old look when the owner turns Cy off.
        assertTrue("CyLook.enabled" in stack && "else if (appPackage != null) AppLogo(appPackage, 32.dp) else CycloneMark(22)" in stack)
        assertTrue("CyLook.enabled" in chrome && "tiltGlass(25.dp, dots = false, thin = true" in chrome)
        assertTrue("if (!cy) {\n        content()" in stack)
    }
}
