package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterQuickAgentTest {
    @Test
    fun currentModelPresetsUseExpectedOpenRouterSlugs() {
        assertEquals(
            listOf(
                "google/gemini-3.8-flash",
                "openai/gpt-5.6-luna",
                "z-ai/glm-5.3-flash",
                "meta/muse-spark-1.3",
                "openai/gpt-5.6-sol",
            ),
            OpenRouterModelPresets.all.map { it.id },
        )
        assertTrue(OpenRouterModelPresets.all.all { it.vision })
        assertEquals("max", OpenRouterModelPresets.MUSE_SPARK_1_3.reasoningEffort)
    }

    @Test
    fun safeModeAllowsBenignSemanticNavigation() {
        val params = JSONObject().put("selector", JSONObject().put("text", "Battery"))
        assertTrue(SafeModeGuard.allowed("phone.click", params))
        assertTrue(SafeModeGuard.allowed("phone.open_app", JSONObject().put("package", "com.android.settings")))
    }

    @Test
    fun safeModeBlocksObviousConsequentialActions() {
        assertFalse(SafeModeGuard.allowed("phone.click", JSONObject().put("selector", JSONObject().put("text", "Confirm payment"))))
        assertFalse(SafeModeGuard.allowed("phone.share", JSONObject().put("text", "hello")))
        assertFalse(SafeModeGuard.allowed("phone.launch_intent", JSONObject().put("uri", "sms:+15551234567")))
    }

    @Test
    fun guidedProfileAllowsSafeNavigationButBlocksInput() {
        assertTrue(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.GUIDED,
            "phone.click",
            JSONObject().put("selector", JSONObject().put("text", "Battery")),
        ).allowed)
        assertFalse(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.GUIDED,
            "phone.type",
            JSONObject().put("selector", JSONObject().put("text", "Search")),
        ).allowed)
    }

    @Test
    fun balancedProfileBlocksCommunicationComposeButAllowsOrdinaryTyping() {
        assertTrue(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.BALANCED,
            "phone.type",
            JSONObject().put("selector", JSONObject().put("text", "Search")),
        ).allowed)
        assertFalse(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.BALANCED,
            "phone.launch_intent",
            JSONObject().put("uri", "sms:+15551234567"),
        ).allowed)
    }

    @Test
    fun fullProfileCanOpenComposeSurfaceButNeverFinalSendOrSensitiveTyping() {
        assertTrue(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.FULL,
            "phone.launch_intent",
            JSONObject().put("uri", "mailto:test@example.com"),
        ).allowed)
        assertFalse(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.FULL,
            "phone.click",
            JSONObject().put("selector", JSONObject().put("text", "Send message")),
        ).allowed)
        assertFalse(CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfile.FULL,
            "phone.type",
            JSONObject().put("selector", JSONObject().put("text", "Password")),
        ).allowed)
    }

    @Test
    fun profileStorageParsingDefaultsToBalanced() {
        assertEquals(CycloneAiAccessProfile.GUIDED, CycloneAiAccessProfile.fromStorage("GUIDED"))
        assertEquals(CycloneAiAccessProfile.BALANCED, CycloneAiAccessProfile.fromStorage("unknown"))
    }

    @Test
    fun stripsJsonCodeFenceForWorkflowCompiler() {
        assertEquals("{\"name\":\"Example\"}", OpenRouterQuickAgent.stripCodeFence("```json\n{\"name\":\"Example\"}\n```"))
    }
}
