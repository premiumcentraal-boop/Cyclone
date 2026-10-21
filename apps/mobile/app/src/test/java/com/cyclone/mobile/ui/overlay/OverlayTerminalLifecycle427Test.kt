package com.cyclone.mobile.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayTerminalLifecycle427Test {
    @Test fun failureReturnsToUsableMinimizedComposer() {
        val machine = OverlayChromeMachine(emit = {})
        machine.startAnalysis("task")
        machine.enterWorking("task")
        machine.finishStopped("Failed")
        assertTrue(machine.snapshot().idleChipVisible)
        assertTrue(machine.snapshot().minimized)
        machine.dispatch(OverlayUserAction.ASK_CYCLONE)
        machine.updateComposer("open Chrome")
        machine.submitRequest()
        assertEquals(listOf("open Chrome"), machine.snapshot().bullets)
    }
}
