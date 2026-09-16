package com.cyclone.mobile.fastpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathLandingTest {
    @Test fun browserFirstWebsiteSkipsRedundantAppLaunch() {
        val hint = FastPathLanding.resolve("open chrome and go to Shopify")
        assertEquals("phone.launch_intent", hint?.tool)
        assertEquals("https://shopify.com", hint?.uri)
    }

    @Test
    fun websiteGoalPrefersLaunchIntentOverIconHunting() {
        val hint = FastPathLanding.resolve("Open ad.nl")
        assertEquals("phone.launch_intent", hint?.tool)
        assertEquals("https://ad.nl", hint?.uri)
        assertTrue(hint!!.reason.contains("icon", ignoreCase = true))
        assertNull(hint.packageName)
    }

    @Test
    fun namedAppPrefersOpenAppAndMarksWorkspaceRouting() {
        val hint = FastPathLanding.resolve("Open Chrome and search for Pixel 8")
        assertEquals("phone.open_app", hint?.tool)
        assertEquals("com.android.chrome", hint?.packageName)
        assertTrue(hint!!.workspaceNamedApp)
        assertTrue(hint.reason.contains("3.9.12"))
    }

    @Test
    fun rawHttpsUrlIsSanitizedWithoutQueryOrFragment() {
        val hint = FastPathLanding.resolve("Go to https://example.com/path?q=secret#frag")
        assertEquals("https://example.com/path", hint?.uri)
        assertTrue(hint!!.toJson().optBoolean("preferBeforeIconHunt"))
    }

    @Test
    fun blankOrAmbiguousGoalsDoNotInventALanding() {
        assertNull(FastPathLanding.resolve(""))
        assertNull(FastPathLanding.resolve("tap the blue button"))
        assertNull(FastPathLanding.sanitizeUri("javascript:alert(1)"))
    }

    @Test
    fun facebookLoginLandsOnInstalledAppNotTheModel() {
        val hint = FastPathLanding.resolve("open Facebook and login")
        assertEquals("phone.open_app", hint?.tool)
        assertEquals("com.facebook.katana", hint?.packageName)
        assertEquals(listOf("com.facebook.katana", "com.facebook.lite"), FastPathLanding.launchCandidates(hint!!.packageName!!))
        assertEquals("https://facebook.com", FastPathLanding.webFallback(hint.packageName!!))
    }

    @Test
    fun namedAppOpenIsALocalPlannerLanding() {
        val agent = sequenceOf(
            java.io.File("src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"),
            java.io.File("apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"),
        ).first { it.isFile }.readText()
        val landing = agent.indexOf("landing?.tool == \"phone.open_app\"")
        assertTrue(landing >= 0)
        assertTrue(agent.contains("webFallback(landing.packageName)"))
        assertTrue(agent.contains("launchFailure"))
        assertTrue(agent.contains("horizon.plan"))
        assertTrue(agent.contains("taskTier"))
    }

    @Test
    fun settingsAliasResolvesToSystemPackage() {
        assertEquals("com.android.settings", FastPathLanding.namedApp("Open Settings")?.second)
        assertEquals("settings", FastPathLanding.namedApp("Open Settings")?.first)
    }
}
