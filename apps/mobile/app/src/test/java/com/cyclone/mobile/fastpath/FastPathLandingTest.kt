package com.cyclone.mobile.fastpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathLandingTest {
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
    fun settingsAliasResolvesToSystemPackage() {
        assertEquals("com.android.settings", FastPathLanding.namedApp("Open Settings")?.second)
        assertEquals("settings", FastPathLanding.namedApp("Open Settings")?.first)
    }
}
