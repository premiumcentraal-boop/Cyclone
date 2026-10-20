package com.cyclone.mobile.mind.pilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 75: what is left of the Pilot is code's own say on risk, kept for Instant, the crew and the recovery ladder. */
class PilotSafetyTest {
    @Test fun `presses that can't be taken back are known by their words, in English and Dutch`() {
        assertTrue(Pilot.irreversible("Send") && Pilot.irreversible("Verwijderen") && Pilot.irreversible("Pay €12"))
        assertFalse(Pilot.irreversible("Message") || Pilot.irreversible("Search"))
    }

    @Test fun `banking, payment and authenticator apps are kept off`() {
        assertTrue(Pilot.keepOff("com.ing.mobile", "ING Bankieren"))
        assertTrue(Pilot.keepOff("com.google.android.apps.walletnfcrel", "Google Wallet"))
        assertTrue(Pilot.keepOff("com.revolut.revolut", "Revolut"))
        assertFalse(Pilot.keepOff("com.whatsapp", "WhatsApp"))
        assertFalse(Pilot.keepOff("com.instagram.android", "Instagram"))
    }
}
