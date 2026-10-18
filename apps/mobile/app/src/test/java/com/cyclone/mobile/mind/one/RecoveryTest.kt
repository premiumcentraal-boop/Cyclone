package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Alpha 144: what a stuck run tries before it asks the owner (field test 143: a Google Maps "dark theme" dialog, and an
 * Instagram tap that kept missing, each ended in "Keep going, change course, or stop?").
 */
class RecoveryTest {
    private fun screen(pkg: String, identity: String, vararg labels: String, typed: String? = null) = StepScreen(
        packageName = pkg, appLabel = pkg.substringAfterLast('.'), identity = identity,
        controls = labels.mapIndexed { i, l -> StepControl("e$i", l) } + listOfNotNull(typed?.let { StepControl("f", "Message", editable = true, value = it) }),
    )

    private class Phone(var now: StepScreen, val after: Map<String, StepScreen>) {
        val calls = mutableListOf<String>()
        fun hands(name: String, args: JSONObject): MindToolResult {
            val key = if (name == "tap") "tap:${now.controls.first { it.ref == args.getString("ref") }.label}" else name
            calls += key
            after[key]?.let { now = it }
            return MindToolResult("ok")
        }
    }

    private val maps = "com.google.android.apps.maps"

    @Test fun `a dialog over the app is closed by its own way out`() {
        val dialog = screen(maps, "dialog", "Try dark theme", "Not now", "Turn on")
        val phone = Phone(dialog, mapOf("tap:Not now" to screen(maps, "map", "Search here", "Explore", "You")))
        assertEquals("closed \"Not now\"", Recovery({ phone.now }, phone::hands).attempt())
        assertEquals(listOf("tap:Not now"), phone.calls)
    }

    @Test fun `nothing that allows, confirms or loses typing is pressed, and system sheets are not touched at all`() {
        assertNull(Recovery.dismissive(screen(maps, "p", "Allow", "Don't allow")))
        assertEquals("Cancel", Recovery.dismissive(screen(maps, "p", "Cancel", "Delete"))?.label)
        assertNull(Recovery.dismissive(screen("com.whatsapp", "chat", "Cancel", "Send", typed = "on my way")))
        // "Cancel" on a full page could throw work away; "Not now" can't.
        val page = screen("com.example", "page", *(1..12).map { "Row $it" }.toTypedArray(), "Cancel")
        assertNull(Recovery.dismissive(page))
        val permission = screen("com.google.android.permissioncontroller", "perm", "While using the app", "Only this time", "Don't allow")
        val phone = Phone(permission, mapOf("back" to screen("com.google.android.permissioncontroller", "perm2", "x", "y")))
        assertNull(Recovery({ phone.now }, phone::hands).attempt())
        // Not even Back: on a permission sheet that would answer it.
        assertEquals(emptyList<String>(), phone.calls)
    }

    @Test fun `Back counts when the same app is still in front, else the app is reopened`() {
        val stuck = screen("com.instagram.android", "story", "Reply", "Like")
        val back = Phone(stuck, mapOf("back" to screen("com.instagram.android", "feed", "Home", "Profile")))
        assertEquals("pressed Back", Recovery({ back.now }, back::hands).attempt())
        val out = Phone(stuck, mapOf("back" to screen("com.google.android.apps.nexuslauncher", "home", "Instagram", "Maps"),
            "open_app" to screen("com.instagram.android", "feed", "Home", "Profile")))
        assertEquals("reopened android", Recovery({ out.now }, out::hands).attempt())
        assertEquals(listOf("back", "open_app"), out.calls)
        // Text in a field: no Back (it could throw the draft away), only reopening.
        val draft = Phone(screen("com.whatsapp", "chat", "Attach", "Camera", typed = "on my way"), emptyMap())
        assertNull(Recovery({ draft.now }, draft::hands).attempt())
        assertEquals(listOf("open_app"), draft.calls)
        // Nothing changed anywhere: the owner is asked after all.
        val dead = Phone(stuck, emptyMap())
        assertNull(Recovery({ dead.now }, dead::hands).attempt())
    }

    @Test fun `a finished step resets the same-trigger count, so three misses with progress between them ask nobody`() {
        val breaker = Breaker()
        assertEquals(Breaker.Call.Go, breaker.wake("missed"))
        breaker.stepDone()
        assertEquals(Breaker.Call.Go, breaker.wake("missed"))
        breaker.stepDone()
        assertEquals(Breaker.Call.Go, breaker.wake("missed"))
        // Without progress, the third still checks in.
        val stuck = Breaker()
        stuck.wake("missed"); stuck.wake("missed")
        assertEquals(true, stuck.wake("missed") is Breaker.Call.CheckIn)
    }

    @Test fun `the same screen with a moving feed is not a change, another screen is`() {
        val before = StepScreen("com.instagram.android", identity = "feed", fingerprint = "a")
        assertEquals(Verdict.MISSED, ExpectCheck.check(Expect.Changed, before.copy(fingerprint = "b"), before, emptyList(), null))
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.Changed, before.copy(identity = "profile", fingerprint = "c"), before, emptyList(), null))
        // Without identities, the fingerprint decides as before.
        val old = StepScreen("com.x", fingerprint = "a")
        assertEquals(Verdict.HELD, ExpectCheck.check(Expect.Changed, old.copy(fingerprint = "b"), old, emptyList(), null))
    }
}
