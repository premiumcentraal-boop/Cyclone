package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
                "meta/muse-spark-1.3-contributor",
                "openai/gpt-6-astra",
                "anthropic/claude-fable-5.1",
                "openai/gpt-5.6-sol",
            ),
            OpenRouterModelPresets.all.map { it.id },
        )
        assertTrue(OpenRouterModelPresets.all.all { it.vision })
        assertEquals(OpenRouterModelPresets.all.size, OpenRouterModelPresets.all.map { it.id }.distinct().size)
        assertEquals("max", OpenRouterModelPresets.MUSE_SPARK_1_3.reasoningEffort)
    }

    @Test
    fun newPremiumModelsExposePortableStructuredOutputCapabilities() {
        assertEquals(OpenRouterStructuredOutputMode.JSON_SCHEMA, OpenRouterModelPresets.GPT_6_ASTRA.structuredOutputMode)
        assertEquals(OpenRouterStructuredOutputMode.JSON_OBJECT, OpenRouterModelPresets.CLAUDE_FABLE_5_1.structuredOutputMode)
        assertEquals("GPT-6 Astra", OpenRouterModelPresets.GPT_6_ASTRA.label)
        assertEquals("Claude Fable 5.1", OpenRouterModelPresets.CLAUDE_FABLE_5_1.label)
    }

    @Test
    fun museContributorIsExplicitAndNeverAliasesNormalMuse() {
        val normal = OpenRouterModelPresets.MUSE_SPARK_1_3
        val contributor = OpenRouterModelPresets.MUSE_SPARK_1_3_CONTRIBUTOR
        assertFalse(normal.isContributor)
        assertTrue(contributor.isContributor)
        assertNotEquals(normal.id, contributor.id)
        assertTrue(contributor.contributorDisclosure.orEmpty().contains("may be used", ignoreCase = true))
        assertEquals("deny", normal.providerRouting("latency").getString("data_collection"))
        assertEquals("allow", contributor.providerRouting("latency").getString("data_collection"))
    }

    @Test
    fun unknownCustomModelCannotAccidentallyBecomeContributorTier() {
        val custom = OpenRouterModelPresets.byId("example/custom-model")
        assertFalse(custom.isContributor)
        assertEquals(OpenRouterDataPolicy.STANDARD, custom.dataPolicy)
        assertEquals("deny", custom.providerRouting("latency").getString("data_collection"))
    }

    @Test
    fun providerRoutingKeepsFallbackInsideSelectedModelPrivacyPolicy() {
        OpenRouterModelPresets.all.forEach { model ->
            val routing = model.providerRouting("latency")
            assertTrue(routing.getBoolean("allow_fallbacks"))
            assertTrue(routing.getBoolean("require_parameters"))
            assertEquals("latency", routing.getString("sort"))
        }
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
