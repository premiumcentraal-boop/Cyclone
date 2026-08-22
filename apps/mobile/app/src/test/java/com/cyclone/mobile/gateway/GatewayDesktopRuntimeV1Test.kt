package com.cyclone.mobile.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDesktopRuntimeV1Test {
    @Test
    fun pairingCodeIsFourUppercaseLettersAndExpiresWithinSixtySeconds() {
        var now = 1_000L
        val engine = DesktopPairingEngine(nowMs = { now })
        val challenge = engine.begin("usb-session", "pc-nonce-abcdefghijklmnop")
        assertTrue(Regex("^[A-Z]{4}$").matches(challenge.code))
        assertEquals(61_000L, challenge.expiresAtMs)
        now = challenge.expiresAtMs + 1
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.EXPIRED),
            engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, challenge.code),
        )
    }

    @Test
    fun requestingNewPairingCodeSafelySupersedesPreviousChallenge() {
        val engine = DesktopPairingEngine()
        val first = engine.begin("usb-session", "pc-nonce-abcdefghijklmnop")
        val second = engine.begin("usb-session", "pc-nonce-ponmlkjihgfedcba")

        assertNotEquals(first.challengeId, second.challengeId)
        assertEquals(second.challengeId, engine.activeForUser()?.challengeId)
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY),
            engine.complete(first.challengeId, first.usbSessionId, first.pcNonce, first.code),
        )
        assertEquals(
            DesktopPairingCompletion.Success,
            engine.complete(second.challengeId, second.usbSessionId, second.pcNonce, second.code),
        )
    }

    @Test
    fun pairingIsBoundToUsbSessionAndNonceAndCannotReplay() {
        val engine = DesktopPairingEngine()
        val challenge = engine.begin("usb-A", "pc-nonce-abcdefghijklmnop")
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.SESSION_MISMATCH),
            engine.complete(challenge.challengeId, "usb-B", challenge.pcNonce, challenge.code),
        )
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.REPLAY),
            engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, challenge.code),
        )
    }

    @Test
    fun pairingStopsAfterFiveWrongCodes() {
        val engine = DesktopPairingEngine()
        val challenge = engine.begin("usb", "pc-nonce-abcdefghijklmnop")
        repeat(4) {
            assertEquals(
                DesktopPairingCompletion.Failure(DesktopPairingFailure.CODE_REJECTED),
                engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, "ZZZZ"),
            )
        }
        assertEquals(
            DesktopPairingCompletion.Failure(DesktopPairingFailure.ATTEMPTS_EXCEEDED),
            engine.complete(challenge.challengeId, challenge.usbSessionId, challenge.pcNonce, "ZZZZ"),
        )
    }

    @Test
    fun normalizedTapMapsToCurrentDisplayBounds() {
        assertEquals(0, DesktopManualControlContract.normalizedPixel(0.0, 1080))
        assertEquals(1079, DesktopManualControlContract.normalizedPixel(1.0, 1080))
        assertTrue(DesktopManualControlContract.normalizedPixel(0.5, 1080) in 539..540)
        assertTrue(runCatching { DesktopManualControlContract.normalizedPixel(1.1, 1080) }.isFailure)
        assertEquals(
            setOf("tap", "back", "home", "scroll_up", "scroll_down", "text", "wake"),
            DesktopManualControlContract.allowedKinds,
        )
    }

    @Test
    fun clipboardPrivacyRejectsCredentialLikeValues() {
        assertTrue(DesktopClipboardPolicy.looksSensitive("OTP: 123456"))
        assertTrue(DesktopClipboardPolicy.looksSensitive("123456"))
        assertTrue(DesktopClipboardPolicy.looksSensitive("Bearer abcdefghijklmnopqrstuvwxyz0123456789"))
        assertFalse(DesktopClipboardPolicy.looksSensitive("hello from Cyclone desktop"))
    }

    @Test
    fun onlyPairingBootstrapMayOmitAuthAndNoShellOperationExists() {
        assertEquals(setOf("pair.begin", "pair.complete"), GatewayProtocol.unauthenticatedOperations)
        val lower = GatewayProtocol.operations.map(String::lowercase)
        assertFalse(lower.any { "shell" in it || "powershell" in it || "root" in it || it == "adb" })
    }
}
