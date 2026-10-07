package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverOverlayVisibilityTest {
    @Test fun allVoiceWindowsYieldAndTheRequestedFaceReturns() {
        val state = DriverOverlayVisibility()
        state.show(panel = true, dim = true)
        state.yielding = true
        assertFalse(state.buttonVisible)
        assertFalse(state.panelVisible)
        assertFalse(state.dimVisible)
        state.yielding = false
        assertFalse(state.buttonVisible)
        assertTrue(state.panelVisible)
        assertTrue(state.dimVisible)
    }

    @Test fun faceChangesDuringAStrokeStayHiddenAndRestoreTheNewFace() {
        val state = DriverOverlayVisibility()
        state.show(panel = true, dim = true)
        state.yielding = true
        state.show(panel = false, dim = false)
        state.showButton()
        assertFalse(state.buttonVisible)
        assertFalse(state.panelVisible)
        assertFalse(state.dimVisible)
        state.yielding = false
        assertTrue(state.buttonVisible)
        assertFalse(state.panelVisible)
        assertFalse(state.dimVisible)
    }

    @Test fun aPanelOpeningDuringYieldCannotInterceptTheGesture() {
        val state = DriverOverlayVisibility()
        state.yielding = true
        state.show(panel = true, dim = true)
        assertFalse(state.buttonVisible)
        assertFalse(state.panelVisible)
        assertFalse(state.dimVisible)
        state.yielding = false
        assertTrue(state.panelVisible)
    }
}
