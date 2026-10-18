package com.cyclone.mobile.mind.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha 144: field test 143's findings on the router and Instant, as tests. A Pixel lists Maps and the Google app side by
 * side, which is exactly what sent "pull up google maps" to the Mind as two apps.
 */
class FieldTest143Test {
    private val pixel = listOf("Instagram" to "com.instagram.android", "WhatsApp" to "com.whatsapp", "Maps" to "com.google.android.apps.maps",
        "Google" to "com.google.android.googlequicksearchbox", "Photos" to "com.google.android.apps.photos", "Meet" to "com.google.android.apps.tachyon",
        "YouTube" to "com.google.android.youtube", "YouTube Music" to "com.google.android.apps.youtube.music", "Settings" to "com.android.settings",
        "Clock" to "com.google.android.deskclock", "Chrome" to "com.android.chrome")
    private val home = GrammarWorld(listOf("Instagram", "WhatsApp", "Maps"), pixel, "com.google.android.apps.nexuslauncher")

    // ---- B13: one app, said with its maker ----------------------------------------------------------------------------

    @Test fun `an app said with its maker is one app, a label inside another word is none`() {
        assertEquals(listOf("Maps"), AppNames.mentioned("pull up google maps", pixel).map { it.first })
        assertEquals(listOf("Photos"), AppNames.mentioned("open google photos", pixel).map { it.first })
        assertEquals(listOf("YouTube Music"), AppNames.mentioned("play youtube music", pixel).map { it.first })
        assertTrue(AppNames.mentioned("set up a meeting", pixel).isEmpty())
        // Two different apps are still two.
        assertEquals(listOf("Instagram", "WhatsApp"), AppNames.mentioned("share from instagram to whatsapp", pixel).map { it.first })
        assertEquals(listOf("Google"), AppNames.mentioned("open google", pixel).map { it.first })
    }

    @Test fun `pull up google maps opens Maps at once, on every surface`() {
        assertNull(ModeRouter.forced("pull up google maps", home))
        val r = ModeRouter.stage0("pull up google maps", home)!!
        assertEquals(Mode.INSTANT, r.mode)
        assertEquals("com.google.android.apps.maps", r.command!!.target)
        assertEquals(InstantIntent.OPEN_APP, ModeRouter.stage0("open google maps", home)!!.command!!.intent)
        // The Mind's open_app resolves it the same way, instead of "ambiguous".
        assertEquals("Maps", AppNames.resolve("Google Maps", pixel).app?.first)
        assertEquals("Instagram", AppNames.resolve("insta", pixel).app?.first)
        assertEquals("Clock", AppNames.resolve("com.google.android.deskclock", pixel).app?.first)
        assertNull(AppNames.resolve("Snapchat", pixel).app)
        // The grammar is exact: an app's name with more words is not an Instant open.
        assertNull(AppNames.resolve("google maps directions home", pixel, strict = true).app)
        // Two apps in one request still go to the Mind.
        assertEquals(Mode.MIND, ModeRouter.forced("open instagram whatsapp", home)?.mode)
    }

    @Test fun `opening Maps covers the maker's word, but not another place`() {
        val maps = InstantCommand(InstantIntent.OPEN_APP, "", target = "com.google.android.apps.maps", targetLabel = "Maps")
        assertTrue(Coverage.leftover("pull up google maps", maps, home).isEmpty())
        assertEquals(listOf("settings"), Coverage.leftover("open google maps settings", maps, home))
    }

    // ---- B1: a tap is proven by what the owner can see -------------------------------------------------------------

    private class Feed(private val proves: Boolean) : InstantHands {
        override fun look(withImage: Boolean) = InstantScreen("Instagram", listOf("Home", "Reels", "Profile"), packageName = "com.instagram.android")
        override fun gesture(intent: InstantIntent, direction: String?) = InstantMove(true, true, "")
        // The feed always "changed" (autoplay); only [proves] says the owner's screen did.
        override fun tapLabel(label: String) = InstantMove(true, true, "", proven = proves)
        override fun openApp(packageName: String) = InstantMove(true, true, "")
        override fun camera(front: Boolean) = InstantMove(true, true, "")
        override fun contacts(name: String) = emptyList<InstantContact>()
        override fun dial(number: String) = InstantMove(true, true, "")
        override fun confirmWindow(text: String, ms: Long) = true
        override fun timer(seconds: Int) = InstantMove(true, true, "")
        override fun alarm(hour: Int, minute: Int) = InstantMove(true, true, "")
        override fun flashlight(on: Boolean) = InstantMove(true, true, "")
        override fun volume(up: Boolean) = InstantMove(true, true, "")
        override fun media(action: String) = InstantMove(true, true, "")
        override fun stopped() = false
    }

    @Test fun `a tap on a live feed that moved only the feed is not done`() {
        val tap = InstantCommand(InstantIntent.TAP, "open my profile", target = "Profile", targetLabel = "Profile")
        val feed = InstantRun.run(tap, Feed(proves = false))
        assertFalse(feed.done)
        assertEquals(Mode.FLASH, feed.promotion!!.to)
        assertTrue(InstantRun.run(tap, Feed(proves = true)).let { it.done && it.verified })
        // A swipe on the same feed is proven by the feed moving: that is what a swipe is for.
        assertTrue(InstantRun.run(InstantCommand(InstantIntent.SWIPE, "swipe up", direction = "up"), Feed(proves = false)).verified)
    }

    // ---- B2: no answer in time is not a decision -------------------------------------------------------------------

    @Test fun `a race nobody answered is marked so the router can still settle it`() {
        assertEquals(Ensemble.NO_ANSWER, Ensemble.combine(emptyList()).why)
        assertEquals(2_500L, com.cyclone.mobile.mind.decide.Decisions.ENGINE_DEADLINE_MS)
        assertTrue(ModeRouter.NO_ANSWER_GUESS < com.cyclone.mobile.mind.decide.Earning.SURE)
    }
}
