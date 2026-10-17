package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.decide.Decider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Alpha 143: Instant proves what it did, says "not on this phone" only when it is true, and never takes half a request.
 * The owner's two failures are the first tests: "open my profile" inside Instagram, and "open Instagram (and then) my
 * profile" ending on Instagram's home page.
 */
class InstantProofTest {
    private val apps = listOf("Instagram" to "com.instagram.android", "WhatsApp" to "com.whatsapp", "Spotify" to "com.spotify.music",
        "Chrome" to "com.android.chrome", "Settings" to "com.android.settings", "YouTube" to "com.google.android.youtube",
        "Maps" to "com.google.android.apps.maps")
    private val instagram = GrammarWorld(listOf("Home", "Search and explore", "Reels", "Shop", "Profile"), apps, "com.instagram.android")
    private val launcher = GrammarWorld(listOf("Instagram", "WhatsApp", "Chrome"), apps, "com.google.android.apps.nexuslauncher")

    // ---- "I don't see Profile on this phone" ---------------------------------------------------------------------------

    @Test fun `open my profile inside Instagram taps Profile and is never answered as a missing app`() {
        val r = ModeRouter.stage0("open my profile", instagram)
        assertNotNull(r)
        assertEquals(Mode.INSTANT, r!!.mode)
        assertEquals(InstantIntent.TAP, r.command!!.intent)
        assertEquals("Profile", r.command!!.target)
        // Without the label on screen it is still never "not on this phone" inside an app.
        assertNull(ModeRouter.missingApp("open my profile", instagram.copy(labels = emptyList())))
        assertNull(ModeRouter.missingApp("open my orders", instagram.copy(labels = emptyList())))
        assertNull(ModeRouter.missingApp("open snapchat", instagram))
    }

    @Test fun `absence is claimed only from the home screen, for an app name, with nothing on screen like it`() {
        assertEquals(Mode.ANSWER, ModeRouter.missingApp("open snapchat", launcher)?.mode)
        // The screen wasn't read: the app list alone still settles a plain app name.
        assertEquals(Mode.ANSWER, ModeRouter.missingApp("open snapchat", launcher.copy(foreground = null))?.mode)
        // A possessive, a place inside an app, or a label on screen is never answered as missing.
        assertNull(ModeRouter.missingApp("open my profile", launcher))
        assertNull(ModeRouter.missingApp("open profile", launcher))
        assertNull(ModeRouter.missingApp("open the orders", launcher))
        assertNull(ModeRouter.missingApp("open snapchat", launcher.copy(labels = listOf("Snapchat"))))
        val home = ModeRouter.route("open my profile", launcher, LocalFacts(), Speed.AUTO, null, 0.85,
            engine = EngineDecider { _, _, _ -> Route(Mode.FLASH, "engine") })
        assertNotEquals(Mode.ANSWER, home.mode)
    }

    @Test fun `a home screen app Android names counts as home, whatever it is called`() {
        val oneUi = launcher.copy(foreground = "com.example.homeapp")
        assertNull(ModeRouter.missingApp("open snapchat", oneUi))
        com.cyclone.mobile.mind.HomeSafety.registerHomes(setOf("com.example.homeapp"))
        assertEquals(Mode.ANSWER, ModeRouter.missingApp("open snapchat", oneUi)?.mode)
        assertTrue(com.cyclone.mobile.mind.HomeSafety.isLauncher("com.google.android.apps.nexuslauncher"))
    }

    @Test fun `on the home screen open X never taps a label, it opens an app or goes up`() {
        assertEquals(InstantIntent.OPEN_APP, ModeRouter.stage0("open instagram", launcher)!!.command!!.intent)
        assertEquals(InstantIntent.OPEN_APP, ModeRouter.stage0("open instagram", instagram)!!.command!!.intent)
        assertNull(InstantGrammar.parse("open my profile", launcher).let { it as? GrammarResult.Match })
    }

    // ---- the whole request -------------------------------------------------------------------------------------------

    private fun engine(command: InstantCommand) = EngineDecider { t, _, _ -> Route(Mode.INSTANT, "engine", command.copy(text = t), by = Decider.DECISIONS) }
    private val openInsta = InstantCommand(InstantIntent.OPEN_APP, "", target = "com.instagram.android", targetLabel = "Instagram")

    @Test fun `a model's open-the-app for a request that names a page in it goes to Flash`() {
        for (said in listOf("open instagram my profile", "instagram profile", "open instagram on my profile", "open instagram go to reels")) {
            val r = ModeRouter.route(said, launcher, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine(openInsta))
            assertEquals(said, Mode.FLASH, r.mode)
            assertNull(said, r.command)
        }
        // "and then" never reaches a model: the rules send it to Flash.
        assertEquals(Mode.FLASH, ModeRouter.route("open instagram and then my profile", launcher, LocalFacts(), Speed.AUTO, null, 0.85,
            engine = engine(openInsta)).mode)
        // Another app named: the rules already send two apps to the Mind; the check catches it for any decider.
        assertNotEquals(Mode.INSTANT, ModeRouter.route("instagram whatsapp", launcher, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine(openInsta)).mode)
        assertEquals(listOf("whatsapp"), Coverage.leftover("instagram whatsapp", openInsta, launcher))
    }

    @Test fun `casual words are fine, the models are asked for exactly those`() {
        for (said in listOf("time for some insta", "let me see the gram", "fire up instagram for me", "instagram please")) {
            assertEquals(said, Mode.INSTANT, ModeRouter.route(said, launcher, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine(openInsta)).mode)
        }
        val back = InstantCommand(InstantIntent.BACK, "")
        assertEquals(Mode.INSTANT, ModeRouter.route("take me back a page", launcher, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine(back)).mode)
        assertEquals(Mode.FLASH, ModeRouter.route("go back to my profile", instagram, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine(back)).mode)
        val maps = InstantCommand(InstantIntent.OPEN_APP, "", target = "com.google.android.apps.maps", targetLabel = "Maps")
        assertEquals(Mode.INSTANT, ModeRouter.route("pull up google maps", launcher, LocalFacts(), Speed.AUTO, null, 0.85, engine = engine(maps)).mode)
        assertTrue(Coverage.leftover("open profile", InstantCommand(InstantIntent.TAP, "", target = "Profile", targetLabel = "Profile"), instagram).isEmpty())
    }

    // ---- proof of what happened --------------------------------------------------------------------------------------

    private class Phone(var front: String, val opens: String? = null, val changes: Boolean = true) : InstantHands {
        var pauses = 0
        override fun look(withImage: Boolean) = InstantScreen(front.substringAfterLast('.'), listOf("Profile", "Reels"), packageName = front)
        override fun gesture(intent: InstantIntent, direction: String?) = InstantMove(true, changes, "")
        override fun tapLabel(label: String) = InstantMove(true, changes, "")
        override fun openApp(packageName: String): InstantMove { opens?.let { front = it }; return InstantMove(true, true, "") }
        override fun camera(front: Boolean) = InstantMove(true, changes, "")
        override fun contacts(name: String) = emptyList<InstantContact>()
        override fun dial(number: String) = InstantMove(true, true, "")
        override fun confirmWindow(text: String, ms: Long) = true
        override fun timer(seconds: Int) = InstantMove(true, true, "")
        override fun alarm(hour: Int, minute: Int) = InstantMove(true, true, "")
        override fun flashlight(on: Boolean) = InstantMove(true, true, "")
        override fun volume(up: Boolean) = InstantMove(true, true, "")
        override fun media(action: String) = InstantMove(true, true, "")
        override fun stopped() = false
        override fun pause(ms: Long) { pauses++ }
    }

    private val home = "com.google.android.apps.nexuslauncher"

    @Test fun `an opened app counts only when it is the one in front`() {
        val ok = InstantRun.run(openInsta, Phone(home, opens = "com.instagram.android"))
        assertTrue(ok.done && ok.verified && ok.promotion == null)
        // The phone said ok, but the home screen is still in front: looked at three times, then Flash.
        val stuck = Phone(home, opens = null)
        val out = InstantRun.run(openInsta, stuck)
        assertFalse(out.done)
        assertEquals(Mode.FLASH, out.promotion!!.to)
        assertTrue(out.promotion!!.reason, out.promotion!!.reason.contains("nexuslauncher"))
        assertEquals(InstantRun.PROOF_LOOKS - 1, stuck.pauses)
        assertEquals(listOf("asked the phone to open Instagram"), out.lines)
        // A screen that doesn't say which app it is is no proof either.
        assertNotNull(InstantRun.run(openInsta, Phone("", opens = "")).promotion)
    }

    @Test fun `a tap that changed nothing goes up, a gesture that moved nothing says so and proves nothing`() {
        val tap = InstantCommand(InstantIntent.TAP, "tap Profile", target = "Profile", targetLabel = "Profile")
        assertTrue(InstantRun.run(tap, Phone("com.instagram.android")).verified)
        val dead = InstantRun.run(tap, Phone("com.instagram.android", changes = false))
        assertFalse(dead.done)
        assertTrue(dead.promotion!!.reason.contains("changed nothing"))
        val scroll = InstantRun.run(InstantCommand(InstantIntent.SCROLL, "scroll down", direction = "down"), Phone("x", changes = false))
        assertTrue(scroll.done)
        assertFalse(scroll.verified)
        assertTrue(scroll.lines.last().contains("nothing on the screen moved"))
        assertTrue(InstantRun.run(InstantCommand(InstantIntent.VOLUME, "volume up", direction = "up"), Phone("x")).verified)
        assertFalse(InstantRun.run(InstantCommand(InstantIntent.CAMERA, "open the camera"), Phone("x", changes = false)).done)
    }
}
