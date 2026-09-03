package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ElementSelector
import com.cyclone.mobile.EmptySelectorException
import com.cyclone.mobile.SelectorEngine
import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClickGateInterceptTest {
    @Test
    fun moveToBinFromIdleEntersGateAndDoesNotClick() {
        val machine = OverlayChromeMachine()
        assertEquals(OverlayChromeState.IDLE, machine.state())
        val perform = ClickGateIntercept.apply(machine, "phone.click", listOf("Move to bin"))
        assertFalse(perform)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(OverlayGateClass.DELETE, machine.snapshot().gateClass)
        assertTrue(
            OverlayCopy.visibleFor(machine.snapshot())
                .contains("Cyclone needs you to confirm before finishing this."),
        )
        assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
    }

    @Test
    fun moveToBinFromLiveEntersGateAndDoesNotClick() {
        val machine = OverlayChromeMachine()
        machine.enterWorking("t3-live")
        machine.enterLive()
        assertEquals(OverlayChromeState.LIVE, machine.state())
        val perform = ClickGateIntercept.apply(machine, "phone.click", listOf("Move to bin"))
        assertFalse(perform)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(OverlayGateClass.DELETE, machine.snapshot().gateClass)
        assertTrue(
            OverlayCopy.visibleFor(machine.snapshot())
                .contains("Cyclone needs you to confirm before finishing this."),
        )
    }

    @Test
    fun activationSiblingMoveToBinStillInterceptsBeforeClick() {
        val chosen = node("name", "0/0/0", "row", "Cyclone-3.6.0-beta.2.apk", "generic", clickable = false)
        val activation = node("btn", "0/0/1", "row", "Move to bin", "button", clickable = true)
        val labels = ClickGateIntercept.labelsFor(chosen, activation)
        assertTrue(labels.contains("Move to bin"))
        val machine = OverlayChromeMachine()
        val perform = ClickGateIntercept.apply(machine, "phone.click", labels)
        assertFalse(perform)
        assertEquals(OverlayChromeState.GATE, machine.state())
    }

    @Test
    fun nativeMove1FileToBinClassifiesAsDeleteGate() {
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Move 1 file to bin")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Move 2 files to bin")))
        val machine = OverlayChromeMachine()
        machine.enterWorking("t3-native")
        machine.enterLive()
        val perform = ClickGateIntercept.apply(machine, "phone.click", listOf("Move 1 file to bin"))
        assertFalse(perform)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertEquals(OverlayGateClass.DELETE, machine.snapshot().gateClass)
    }

    @Test
    fun seeAllAndHostClicksStayUngated() {
        val machine = OverlayChromeMachine()
        machine.enterWorking("t4")
        machine.enterLive()
        listOf(
            "See all",
            "Network & internet",
            "Alarms",
            "7",
            "Download",
            "More options",
        ).forEach { label ->
            assertNull(label, GateClassifier.classify("phone.click", listOf(label)))
            val live = OverlayChromeMachine()
            live.enterWorking("ungated")
            live.enterLive()
            assertTrue(label, ClickGateIntercept.apply(live, "phone.click", listOf(label)))
            assertEquals(label, OverlayChromeState.LIVE, live.state())
            val idle = OverlayChromeMachine()
            assertTrue(label, ClickGateIntercept.apply(idle, "phone.click", listOf(label)))
            assertEquals(label, OverlayChromeState.IDLE, idle.state())
        }
    }

    @Test
    fun emptySelectorStillHardErrors() {
        val snap = UiSnapshot(
            packageName = "com.google.android.documentsui",
            className = "Files",
            screenWidth = 100,
            screenHeight = 100,
            timestampMs = 1,
            fingerprint = "fp",
            controller = "agent",
            windows = emptyList(),
            nodes = listOf(node("top", "0", null, "Move to bin", "button", clickable = true)),
        )
        try {
            SelectorEngine.resolve(snap, ElementSelector(), 8)
            fail("empty selector must hard-error")
        } catch (error: EmptySelectorException) {
            assertTrue(error.message!!.contains("empty selector"))
        }
    }

    private fun node(
        id: String,
        path: String,
        parent: String?,
        text: String,
        role: String,
        clickable: Boolean,
    ) = UiNodeSnapshot(
        id = id,
        path = path,
        parentId = parent,
        childIds = emptyList(),
        depth = path.count { it == '/' },
        windowId = 1,
        className = if (role == "button") "android.widget.Button" else "android.view.View",
        role = role,
        text = text,
        contentDescription = "",
        resourceId = "",
        bounds = UiBounds(0, 0, 10, 10),
        clickable = clickable,
        longClickable = false,
        editable = false,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
        checkable = false,
        focused = false,
        focusable = clickable,
        visibleToUser = true,
        actions = if (clickable) listOf("ACTION_CLICK") else emptyList(),
    )
}
