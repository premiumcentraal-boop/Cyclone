package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileBootstrapContractTest {
    @Test fun identityMustMatchReceivingAndroidUser() {
        ProfileBootstrapContract.validateTransfer(0, 12, 12)
        assertTrue(runCatching { ProfileBootstrapContract.validateTransfer(0, 12, 13) }.isFailure)
        assertTrue(runCatching { ProfileBootstrapContract.validateTransfer(0, 0, 0) }.isFailure)
        assertTrue(runCatching { ProfileBootstrapContract.validateTransfer(12, 12, 12) }.isFailure)
    }
    @Test fun onlyPortablePreferencesAreTransferred() {
        val input = mapOf("openrouter_model" to "model", "safe_mode" to true,
            "api_key" to "secret", "sessionId" to "owned", "gate_approved" to true, "pairing_token" to "private")
        assertEquals(mapOf("openrouter_model" to "model", "safe_mode" to true), ProfileBootstrapContract.portablePreferences(input))
    }
    @Test fun serviceMergePreservesUnrelatedAccessibilityAndIsIdempotent() {
        val own = ProfileBootstrapContract.ACCESSIBILITY
        val other = "reader.app/.Service"
        val once = ProfileBootstrapContract.mergeServiceList(other, own)
        assertEquals("$other:$own", once)
        assertEquals(once, ProfileBootstrapContract.mergeServiceList(once, own))
        assertEquals(own, ProfileBootstrapContract.mergeServiceList("null", own))
    }
    @Test fun arbitraryServicesCannotBeGrantedByTransfer() {
        assertTrue(runCatching { ProfileBootstrapContract.mergeServiceList("", "other.app/.Service") }.isFailure)
    }
    @Test fun uidArithmeticRetainsExactUser() {
        assertEquals(1210234, ProfileBootstrapContract.targetUid(10234, 12))
        assertEquals(12, ProfileBootstrapContract.userId(1210234))
        assertTrue(runCatching { ProfileBootstrapContract.targetUid(0, 12) }.isFailure)
    }

    @Test fun bootstrapAcknowledgementPreservesSafeStageAndReason() {
        val ack = ProfileBootstrapContract.parseBootstrapAcknowledgement(
            """{"nonce":"attempt","user":12,"ok":false,"stage":"permission_validation","reasonCode":"permission_denied"}""",
        )
        assertNotNull(ack)
        assertEquals("attempt", ack?.nonce)
        assertEquals(12, ack?.user)
        assertEquals("permission_validation", ack?.stage)
        assertEquals("permission_denied", ack?.reasonCode)
        assertFalse(ack?.ok ?: true)
    }

    @Test fun bootstrapAcknowledgementDropsUnrecognizedAndSensitiveDiagnostics() {
        val ack = ProfileBootstrapContract.parseBootstrapAcknowledgement(
            """{"nonce":"attempt","user":12,"ok":false,"stage":"private payload","reasonCode":"secret exception detail"}""",
        )
        assertNotNull(ack)
        assertEquals("unknown", ack?.stage)
        assertNull(ack?.reasonCode)
        assertNull(ProfileBootstrapContract.parseBootstrapAcknowledgement("not-json"))
    }

    @Test fun bootstrapAcknowledgementCanReportUnknownReceiverIdentity() {
        val ack = ProfileBootstrapContract.parseBootstrapAcknowledgement(
            """{"user":-1,"ok":false,"stage":"receiver_identity","reasonCode":"receiver_exception"}""",
        )
        assertNotNull(ack)
        assertEquals(-1, ack?.user)
        assertEquals("receiver_identity", ack?.stage)
    }

    @Test fun bootstrapExceptionsMapToStableNonSensitiveCodes() {
        assertEquals("permission_denied", ProfileBootstrapContract.bootstrapFailureCode(SecurityException("secret")))
        assertEquals("crypto_failed", ProfileBootstrapContract.bootstrapFailureCode(java.security.GeneralSecurityException("secret")))
        assertEquals("storage_failed", ProfileBootstrapContract.bootstrapFailureCode(java.io.IOException("secret")))
        assertEquals("validation_failed", ProfileBootstrapContract.bootstrapFailureCode(IllegalStateException("secret")))
        assertEquals("receiver_exception", ProfileBootstrapContract.bootstrapFailureCode(UnsupportedOperationException("secret")))
    }
}
