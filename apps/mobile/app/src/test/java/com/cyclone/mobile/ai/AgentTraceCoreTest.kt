package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceCoreTest {
    @Test
    fun tracePrivacyRedactsSecretsAndBinaryPayloads() {
        val dirty = "password=hunter2 token:abc123456789 Bearer abcdefghijklmnop pngBase64:${"A".repeat(220)}"
        val clean = TracePrivacy.clean(dirty)
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("abc123456789"))
        assertFalse(clean.contains("abcdefghijklmnop"))
        assertFalse(clean.contains("A".repeat(100)))
        assertTrue(clean.contains("REDACTED"))
    }

    @Test
    fun traceHumanizerNeverIncludesTypedValue() {
        val params = JSONObject()
            .put("value", "very-private-text")
            .put("selector", JSONObject().put("text", "Email"))
        val summary = TraceHumanizer.decision("phone.type", params, null)
        assertFalse(summary.contains("very-private-text"))
        assertTrue(summary.contains("field", ignoreCase = true))
    }

    @Test
    fun customOpenRouterSlugIsAcceptedAsConfiguration() {
        val slug = "my-provider/my-custom-model"
        val custom = OpenRouterModelPresets.byId(slug)
        assertEquals(slug, custom.id)
        assertEquals(slug, custom.label)
        assertFalse(custom.vision)
    }

    @Test
    fun explicitDisplaySummaryWinsButIsSanitizedForUserTrace() {
        val params = JSONObject().put("selector", JSONObject().put("text", "Battery"))
        val summary = TraceHumanizer.decision("phone.click", params, "Opening Battery because it matches the requested destination")
        assertEquals("Opening Battery because it matches the requested destination", summary)
    }
}
