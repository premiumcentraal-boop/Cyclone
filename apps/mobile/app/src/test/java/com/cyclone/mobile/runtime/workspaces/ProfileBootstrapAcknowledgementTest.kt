package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileBootstrapAcknowledgementTest {
    @get:Rule val folder = TemporaryFolder()
    private fun reply(ok: Boolean = true, nonce: String = "attempt-a", user: Int = 16) =
        ProfileBootstrapAcknowledgement.Reply(ok, user, nonce, if (ok) "COMPLETE" else "PERMISSIONS", if (ok) null else "BOOTSTRAP_FAILED")
    private fun await(read: () -> String?, timeout: Long = 1000): ProfileBootstrapAcknowledgement.Reply {
        var clock = 0L
        return ProfileBootstrapAcknowledgement.await(16, "attempt-a", read, timeout, { clock }, { clock += it })
    }
    @Test fun atomicWriteReadCycleRetainsOriginalIdentityFields() {
        val file = folder.newFile("profile-bootstrap-result.json")
        ProfileBootstrapAcknowledgement.write(file, reply())
        assertEquals(reply(), await({ file.readText() }))
        ProfileBootstrapAcknowledgement.write(file, reply(false))
        assertEquals(reply(false), ProfileBootstrapAcknowledgement.parse(file.readText()))
        assertFalse(java.io.File(file.path + ".new").exists())
    }
    @Test fun coldStartMissingAndPartialReadsRetryUntilMatchingReply() {
        var attempts = 0
        val result = await({ when (++attempts) { 1 -> null; 2 -> "{\"ok\":"; else -> reply().json().toString() } })
        assertTrue(result.ok)
        assertEquals(3, attempts)
    }
    @Test fun transientReadExceptionRetries() {
        var attempts = 0
        assertTrue(await({ if (++attempts == 1) error("temporary read failure") else reply().json().toString() }).ok)
        assertEquals(2, attempts)
    }
    @Test fun slowUnlockedReceiverCanCompleteAfterOriginalSixSecondWindow() {
        var clock = 0L
        val result = ProfileBootstrapAcknowledgement.await(16, "attempt-a", {
            if (clock < 7000) null else reply().json().toString()
        }, 30_000, { clock }, { clock += it })
        assertTrue(result.ok)
        assertEquals(7000, clock)
    }
    @Test fun originalSuccessfulReplyFormatRemainsCompatible() {
        assertTrue(await({ "{\"ok\":true,\"user\":16,\"nonce\":\"attempt-a\"}" }).ok)
    }
    @Test fun staleNonceAndWrongUserAreNeverAccepted() {
        var attempts = 0
        assertTrue(await({ when (++attempts) {
            1 -> reply(nonce = "stale").json().toString()
            2 -> reply(user = 0).json().toString()
            else -> reply().json().toString()
        } }).ok)
        assertEquals(3, attempts)
    }
    @Test fun matchedFailureSurfacesStageImmediatelyInsteadOfTimingOut() {
        val failure = assertThrows(ProfileBootstrapAcknowledgement.Failed::class.java) { await({ reply(false).json().toString() }) }
        assertTrue(failure.message!!.contains("PERMISSIONS"))
        assertTrue(failure.message!!.contains("BOOTSTRAP_FAILED"))
        assertFalse(failure.message!!.contains("attempt-a"))
    }
    @Test fun missingReplyTimesOutWithinBoundedBudget() {
        var reads = 0
        assertThrows(ProfileBootstrapAcknowledgement.TimedOut::class.java) { await({ reads++; null }, 600) }
        assertEquals(4, reads)
    }
    @Test fun lateSuccessAfterDeadlineIsNotAccepted() {
        var clock = 0L
        assertThrows(ProfileBootstrapAcknowledgement.TimedOut::class.java) {
            ProfileBootstrapAcknowledgement.await(16, "attempt-a", { clock = 1001; reply().json().toString() }, 1000, { clock }, {})
        }
    }
    @Test fun retryAfterFailureRequiresNewMatchingAttempt() {
        val file = folder.newFile("profile-bootstrap-result.json")
        ProfileBootstrapAcknowledgement.write(file, reply(false))
        assertThrows(ProfileBootstrapAcknowledgement.Failed::class.java) { await({ file.readText() }) }
        var reads = 0; var clock = 0L
        val success = ProfileBootstrapAcknowledgement.await(16, "attempt-b", {
            if (++reads == 2) ProfileBootstrapAcknowledgement.write(file, reply(nonce = "attempt-b"))
            file.readText()
        }, 1000, { clock }, { clock += it })
        assertTrue(success.ok)
        assertEquals(2, reads)
    }
    @Test fun summaryContainsOnlySafeMetadata() {
        val summary = reply(false).summary()
        assertEquals(setOf("ok", "user", "stage", "reasonCode"), summary.keys().asSequence().toSet())
        assertFalse(summary.toString().contains("attempt-a"))
    }
    @Test fun malformedLegacyAndUntrustedDiagnosticValuesFailClosed() {
        for (value in listOf("not-json", "{\"ok\":false}", "{\"ok\":true,\"user\":null}",
            "{\"ok\":\"true\",\"user\":16}", "{\"ok\":true,\"user\":16.5}")) assertNull(ProfileBootstrapAcknowledgement.parse(value))
        val parsed = ProfileBootstrapAcknowledgement.parse("{\"ok\":false,\"user\":16,\"nonce\":\"attempt-a\",\"stage\":\"private data\",\"reasonCode\":\"secret value\"}")!!
        assertEquals("TRANSFER", parsed.stage)
        assertNull(parsed.reasonCode)
    }
}
