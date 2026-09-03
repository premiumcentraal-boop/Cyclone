package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class AgentRunPrivacyTest {
    @Test
    fun passwordOtpAndProviderKeyNeverAppearInSanitizedPayload() {
        val dirty = JSONObject()
            .put("password", "hunter2")
            .put("otp", "482991")
            .put("apiKey", "sk-or-v1-supersecretproviderkey")
            .put("safeArguments", JSONObject().put("text", "typed-secret"))
            .put("note", "password=hunter2 OTP:482991 Bearer abcdefghijklmnop")

        val clean = AgentRunSanitizer.sanitizeObject(dirty, "phone.type").toString()

        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("482991"))
        assertFalse(clean.contains("supersecretproviderkey"))
        assertFalse(clean.contains("typed-secret"))
        assertFalse(clean.contains("abcdefghijklmnop"))
        assertTrue(clean.contains("REDACTED"))
    }

    @Test
    fun screenshotMetadataSurvivesWhileBase64ImagePayloadIsRemoved() {
        val payload = JSONObject().put(
            "screenshot",
            JSONObject()
                .put("id", "shot-7")
                .put("sha256", "abc123")
                .put("width", 720)
                .put("height", 1280)
                .put("base64", "A".repeat(300)),
        )

        val safe = AgentRunSanitizer.sanitizeObject(payload)
        val screenshot = safe.getJSONObject("screenshot")

        assertEquals("shot-7", screenshot.getString("id"))
        assertEquals("abc123", screenshot.getString("sha256"))
        assertEquals(720, screenshot.getInt("width"))
        assertFalse(screenshot.getString("base64").contains("A".repeat(50)))
        assertTrue(screenshot.getString("base64").contains("OMITTED_BINARY"))
    }

    @Test
    fun diagnosticZipHasOnlySanitizedRunTimelineAndMetadata() {
        val event = AgentRunEvent(
            id = "evt-1",
            runId = "run-1",
            sequence = 1,
            timestampMs = 1_000,
            type = AgentRunEventType.USING_VISION,
            message = "checking screenshot",
            tool = "agent.visual_context",
            payload = JSONObject()
                .put("password", "never-export-me")
                .put("screenshot", JSONObject().put("id", "shot-1").put("hash", "h1").put("pngBase64", "B".repeat(300))),
        )
        val record = AgentRunRecord(
            id = "run-1",
            goal = "Open Chrome",
            model = "test-model",
            startedAtMs = 1_000,
            endedAtMs = 2_000,
            status = AgentRunStatus.COMPLETE,
            summary = "Done",
            finalClassification = "COMPLETE",
            events = listOf(event),
        )
        val bytes = AgentRunLogExporter.archiveBytes(
            record,
            AgentRunLogExporter.metadataJson(record, "3.8.8", 99, "test-build", 2_100),
        )
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }

        assertEquals(setOf("run.json", "timeline.txt", "metadata.json"), entries.keys)
        val joined = entries.values.joinToString("\n")
        assertFalse(joined.contains("never-export-me"))
        assertFalse(joined.contains("B".repeat(100)))
        assertTrue(joined.contains("shot-1"))
        assertTrue(joined.contains("screenshotsIncluded"))
        assertTrue(joined.contains("false"))
    }

    @Test
    fun privateReasoningKeysAreOmittedRatherThanExported() {
        val dirty = JSONObject()
            .put("chainOfThought", "secret hidden model reasoning")
            .put("privateReasoning", "more hidden reasoning")
            .put("message", "chain of thought: another private model rationale")
            .put("verificationBasis", "PAGE_KEY_CHANGED")

        val clean = AgentRunSanitizer.sanitizeObject(dirty).toString()

        assertFalse(clean.contains("secret hidden model reasoning"))
        assertFalse(clean.contains("more hidden reasoning"))
        assertFalse(clean.contains("another private model rationale"))
        assertTrue(clean.contains("PAGE_KEY_CHANGED"))
        assertTrue(clean.contains("OMITTED_PRIVATE_REASONING"))
    }
}
