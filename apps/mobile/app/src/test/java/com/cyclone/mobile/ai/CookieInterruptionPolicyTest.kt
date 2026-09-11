package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CookieInterruptionPolicyTest {
    @Test fun redditLoginInterruptionRejectsLocallyOnceAcrossObservationChurn() {
        val policy = CookieInterruptionPolicy()
        assertEquals("Reject Optional Cookies", policy.next(page(), "open reddit.com on Chrome and login for me")?.label)
        assertNull(policy.next(page("next"), "open reddit.com on Chrome and login for me"))
    }

    @Test fun explicitCookieChoicesAreLeftToThePlanner() {
        listOf("accept all cookies", "allow optional cookies", "manage cookies", "don't accept all cookies").forEach {
            assertNull(CookieInterruptionPolicy().next(page(), it))
        }
        assertNotNull(CookieInterruptionPolicy().next(page(), "reject cookies and log in"))
    }

    @Test fun ambiguousDisabledStaleAndUnrelatedControlsAreNeverSelected() {
        val card = page()
        val target = card.controls.single()
        listOf(
            card.copy(controls = listOf(target, target.copy(elementId = "semantic:obs:other"))),
            card.copy(controls = listOf(target.copy(evidence = JSONObject().put("enabled", false)))),
            card.copy(controls = listOf(target.copy(observationId = "old"))),
            card.copy(controls = listOf(target.copy(label = "Reject transfer"))),
            card.copy(actionable = false),
        ).forEach { assertNull(CookieInterruptionPolicy().next(it, "log in")) }
    }

    @Test fun genericRejectRequiresCookieScene() {
        val card = page().let { it.copy(pageText = JSONObject(), controls = listOf(it.controls.single().copy(label = "Reject all"))) }
        assertNull(CookieInterruptionPolicy().next(card, "log in"))
        assertNotNull(CookieInterruptionPolicy().next(card.copy(pageText = JSONObject().put("text", "We use cookies")), "log in"))
    }

    private fun page(obs: String = "obs") = AgentPageCard(
        obs, 1, true, 1, "com.android.chrome", null, "page-$obs", "structure", "content", "fp",
        JSONObject(), JSONObject().put("text", "We use cookies"), JSONObject(),
        listOf(AgentElementCandidate("semantic:$obs:reject", obs, "Reject Optional Cookies", "reject cookies",
            "button", "semantic", 1.0, JSONObject().put("enabled", true).put("clickable", true))), JSONArray(),
    )
}
