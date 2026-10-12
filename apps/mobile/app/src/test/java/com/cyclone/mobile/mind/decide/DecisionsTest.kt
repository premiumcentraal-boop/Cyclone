package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.mind.modes.BoxQuestion
import com.cyclone.mobile.mind.modes.BoxRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 58: JEV decides by default; GPT-6 Luna Decisions is live, sees images, and can be chosen as a setting. */
class DecisionsTest {
    private val request = BoxRequest("Pick the shutter.", "Screen: Camera\nControls: Mode, Button 3",
        listOf(BoxQuestion("next", "Which control takes the photo?", listOf("Mode", "Button 3", "none"))),
        image = "data:image/png;base64,AAAA")

    @Test fun `jev decides by default and reads text only`() {
        assertEquals(DecisionProvider.JEV, Decisions.DEFAULT)
        assertEquals(DecisionProvider.JEV, Decisions.active())
        assertEquals("~typesafe/jev-latest", Decisions.active().model)
        assertFalse(DecisionProvider.JEV.vision)
    }

    @Test fun `luna is live, sees images and is the one watching beside jev`() {
        assertTrue(DecisionProvider.LUNA.usable)
        assertTrue(DecisionProvider.LUNA.vision)
        assertEquals("openai/gpt-6-luna-decisions", DecisionProvider.LUNA.model)
        assertEquals(DecisionProvider.LUNA, Decisions.watching())
        assertEquals(DecisionProvider.LUNA, DecisionProvider.of("luna"))
        assertNull(DecisionProvider.of("openai"))
    }

    @Test fun `jev never gets the screenshot and asks in the documented shape`() {
        val body = ProviderDecisionBox.body(DecisionProvider.JEV, request)
        assertEquals("~typesafe/jev-latest", body.getString("model"))
        assertFalse(body.toString().contains("image"))
        val next = body.getJSONObject("questions").getJSONObject("next")
        assertEquals("choice", next.getString("type"))
        assertEquals(3, next.getJSONObject("criteria").length())
        assertFalse(body.toString().contains("\"choices\""))
    }

    @Test fun `luna gets the screenshot as a state image`() {
        val body = ProviderDecisionBox.body(DecisionProvider.LUNA, request)
        val state = body.getJSONArray("state")
        val image = (0 until state.length()).map { state.get(it) }.filterIsInstance<org.json.JSONObject>().single()
        assertEquals("image_url", image.getString("type"))
        assertEquals("data:image/png;base64,AAAA", image.getJSONObject("image_url").getString("url"))
        assertTrue(ProviderDecisionBox("k", DecisionProvider.LUNA).sees)
        assertFalse(ProviderDecisionBox("k", DecisionProvider.JEV).sees)
    }

    @Test fun `strict privacy asks for zero data retention`() {
        assertTrue(ProviderDecisionBox.body(DecisionProvider.LUNA, request, zdr = true).getJSONObject("provider").getBoolean("zdr"))
        assertFalse(ProviderDecisionBox.body(DecisionProvider.LUNA, request).getJSONObject("provider").has("zdr"))
    }
}
