package com.cyclone.mobile.ai

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAgentProtocolTest {
    private val control = PageControl(
        key = "battery-control",
        label = "Battery",
        semanticName = "battery",
        role = "button",
        selector = JSONObject().put("resourceId", "com.android.settings:id/battery").put("text", "Battery").put("clickable", true),
        androidActions = listOf("ACTION_CLICK"),
        risk = ActionRisk.SAFE,
        confidence = .9,
    )
    private val page = PageContext(
        pageKey = "settings:root",
        packageName = "com.android.settings",
        className = "com.android.settings.Settings",
        title = "Settings",
        structuralKey = "structure",
        contentKey = "content",
        controls = listOf(control),
        observationCount = 1,
        firstSeenAt = 1L,
        lastSeenAt = 1L,
    )

    @Test
    fun pageControlIdResolvesToLocalSemanticSelector() {
        val decision = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"Settings menu","displaySummary":"Opening Battery","actions":[{"tool":"phone.click","controlId":"battery-control","params":{},"expectedPageChange":true,"displaySummary":"Opening Battery"}]}""",
        )
        val params = PageAgentProtocol.resolveParams(decision.actions.single(), page).getOrThrow()
        assertEquals("com.android.settings:id/battery", params.getJSONObject("selector").getString("resourceId"))
        assertTrue(PageAgentProtocol.shouldStopBatch(decision.actions.single(), page, page.copy(pageKey = "battery:page")))
    }

    @Test
    fun invalidInventedControlIsRejectedLocally() {
        val action = PageAgentAction("phone.click", "made-up-id", JSONObject(), true, "Clicking")
        assertTrue(PageAgentProtocol.resolveParams(action, page).isFailure)
    }

    @Test
    fun lunaIsBalancedDefaultAndGeminiFlashIsCurrent() {
        assertEquals("openai/gpt-5.6-luna", OpenRouterModelPresets.DEFAULT.id)
        assertEquals("medium", OpenRouterModelPresets.GPT_5_6_LUNA.reasoningEffort)
        assertEquals("google/gemini-3.8-flash", OpenRouterModelPresets.GEMINI_3_8_FLASH.id)
        assertEquals(6, QuickAgentConfig().maxDecisions)
    }
}
