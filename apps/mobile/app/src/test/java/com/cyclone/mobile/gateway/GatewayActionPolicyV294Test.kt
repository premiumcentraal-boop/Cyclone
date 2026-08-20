package com.cyclone.mobile.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GatewayActionPolicyV294Test {
    @Test
    fun semanticConsequentialRiskCannotBeDowngradedBySelectorText() {
        val params = JSONObject()
            .put("_gatewayRisk", "CONSEQUENTIAL")
            .put("selector", JSONObject()
                .put("text", "Continue")
                .put("resourceId", "com.example:id/continue"))
        try {
            GatewayActionPolicy.requireAllowed("phone.click", params)
            fail("Consequential semantic risk must stay blocked")
        } catch (error: GatewayProtocolException) {
            assertEquals("POLICY_BLOCKED", error.code)
        }
    }

    @Test
    fun safeSemanticRiskAllowsOrdinaryNavigation() {
        GatewayActionPolicy.requireAllowed(
            "phone.click",
            JSONObject()
                .put("_gatewayRisk", "SAFE")
                .put("selector", JSONObject()
                    .put("text", "Apps")
                    .put("resourceId", "android:id/apps")),
        )
    }

    @Test
    fun gatewayTypingRequiresExplicitNonSensitiveTarget() {
        try {
            GatewayActionPolicy.requireAllowed("phone.type", JSONObject().put("value", "query"))
            fail("Focused-field typing must not be exposed to PC_CODEX")
        } catch (error: GatewayProtocolException) {
            assertEquals("POLICY_BLOCKED", error.code)
        }

        GatewayActionPolicy.requireAllowed(
            "phone.type",
            JSONObject()
                .put("value", "query")
                .put("_gatewayRisk", "SAFE")
                .put("selector", JSONObject().put("resourceId", "com.example:id/search")),
        )
    }

    @Test
    fun semanticAuthenticationRiskBlocksTypingEvenWithBenignResourceId() {
        val params = JSONObject()
            .put("value", "123456")
            .put("_gatewayRisk", "AUTHENTICATION")
            .put("selector", JSONObject().put("resourceId", "com.example:id/code"))
        try {
            GatewayActionPolicy.requireAllowed("phone.type", params)
            fail("Authentication semantic risk must remain blocked")
        } catch (error: GatewayProtocolException) {
            assertEquals("POLICY_BLOCKED", error.code)
        }
    }
}
