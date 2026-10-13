package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.policy.ActionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GateShownTest {
    @Test fun gateTitleSaysWhatAndWhere() {
        assertEquals("Send a message in WhatsApp", OverlayGlassCopy.gate(OverlayGateClass.SEND, "WhatsApp").title)
        assertEquals("Make a payment", OverlayGlassCopy.gate(OverlayGateClass.PAY, null).title)
        assertEquals("Give access", OverlayGlassCopy.gate(OverlayGateClass.GRANT, null).title)
    }

    @Test fun theGateRemembersTheAppAndTheModelsReason() {
        ActionReason.set("  Send the photos:   she asked for them  ")
        GateShown.record(OverlayGateClass.PAY, "com.example.shop")
        assertEquals(GateShown(OverlayGateClass.PAY, "com.example.shop", "Send the photos: she asked for them"), GateShown.current.value)
        GateShown.clear()
        ActionReason.clear()
        assertNull(GateShown.current.value)
    }

    @Test fun aStaleReasonIsNotShown() {
        ActionReason.set("Old step", nowMs = 0L)
        assertNull(ActionReason.current(nowMs = 11 * 60_000L))
        assertEquals("Old step", ActionReason.current(nowMs = 60_000L))
        ActionReason.clear()
    }
}
