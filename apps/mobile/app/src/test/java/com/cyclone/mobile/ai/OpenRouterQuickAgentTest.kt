package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterQuickAgentTest {
    @Test
    fun currentModelPresetsUseExpectedOpenRouterSlugs() {
        assertEquals("deepseek/deepseek-v4-flash-0731", OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id)
        assertEquals("google/gemma-4-26b-a4b-it", OpenRouterModelPresets.GEMMA_4_26B.id)
        assertTrue(OpenRouterModelPresets.GEMMA_4_26B.vision)
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
    fun stripsJsonCodeFenceForWorkflowCompiler() {
        assertEquals("{\"name\":\"Example\"}", OpenRouterQuickAgent.stripCodeFence("```json\n{\"name\":\"Example\"}\n```"))
    }
}
