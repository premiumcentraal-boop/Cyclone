package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateShownTest {
    @Test fun controlIsTheFirstShortLabelWithLetters() {
        assertEquals("Pay €12.99", GateShown.control(listOf("", "Pay  €12.99", "Checkout")))
        assertEquals("Send", GateShown.control(listOf("123456789", "Send")))
        assertNull(GateShown.control(listOf("A very long paragraph of message text that is not a button label at all")))
    }

    @Test fun gateWordsSayWhatWhereAndThatNothingHappensYet() {
        val send = OverlayGlassCopy.gate(OverlayGateClass.SEND, "WhatsApp", "Send")
        assertEquals("Send a message in WhatsApp", send.title)
        assertEquals("Cyclone stopped before tapping “Send”. Nothing is sent until you say so.", send.detail)
        val grant = OverlayGlassCopy.gate(OverlayGateClass.GRANT, null, null)
        assertEquals("Give access", grant.title)
        assertTrue(grant.detail.startsWith("Cyclone stopped before doing this."))
    }

    @Test fun theGateRemembersTheAppAndControl() {
        GateShown.record(OverlayGateClass.PAY, listOf("Pay now"), "com.example.shop")
        assertEquals(GateShown(OverlayGateClass.PAY, "com.example.shop", "Pay now"), GateShown.current.value)
        GateShown.clear()
        assertNull(GateShown.current.value)
    }
}
