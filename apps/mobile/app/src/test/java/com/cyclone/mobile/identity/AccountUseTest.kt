package com.cyclone.mobile.identity

import com.cyclone.mobile.ui.v32.AccountsPresenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountUseTest {
    private val food = "Cyclone_0123456789abcdef"
    private val fit = "Cyclone_fedcba9876543210"
    private val labels = mapOf(food to "Food", fit to "Fitness")

    private fun book(): IdentityBook = IdentityBook().also { it.me = "phone-a"; it.myName = "Pixel 8" }

    private fun identity(handle: String, profile: String? = food, label: String? = "Food") = SavedIdentity(
        id = SavedIdentity.newId(), app = "com.instagram.android", handle = handle,
        card = IdentityCard.fromSignup(mapOf("username" to handle.removePrefix("@"), "email" to "$handle@brand.one".removePrefix("@"))),
        password = "s3cret-$handle", profileId = profile, profileLabel = label, wasProfileLabel = null, createdAtMs = 0, updatedAtMs = 0,
    )

    @Test
    fun theAccountBoundToThisProfileIsChosenAndFillsWithoutACard() {
        val b = book()
        val mine = b.save(identity("@food"), 10)
        b.save(identity("@fit", fit, "Fitness"), 10)
        val list = AccountUse.candidatesIn(b, "com.instagram.android", food, labels)
        assertEquals(2, list.size)
        val pick = AccountUse.pick(list, null) as AccountUse.Pick.Use
        assertEquals(mine.id, pick.candidate.id)
        assertFalse("bound here: no card", pick.confirm)
        assertEquals("s3cret-@food", AccountUse.serve(b, mine.id, "password", food, "Food", confirmed = false, now = 20).getOrThrow())
        assertEquals("food", AccountUse.serve(b, mine.id, "username", food, "Food", confirmed = false, now = 21).getOrThrow())
    }

    @Test
    fun anAccountBoundElsewhereNeedsTheOwnersYes() {
        val b = book()
        val other = b.save(identity("@fit", fit, "Fitness"), 10)
        val list = AccountUse.candidatesIn(b, "com.instagram.android", food, labels)
        val pick = AccountUse.pick(list, null) as AccountUse.Pick.Use
        assertTrue("another profile: the card first", pick.confirm)
        assertEquals("Fitness on this phone", pick.candidate.binding)
        val refused = AccountUse.serve(b, other.id, "password", food, "Food", confirmed = false, now = 20)
        assertEquals("NEEDS_CONFIRMATION", (refused.exceptionOrNull() as AccountUse.Refused).code)
        assertTrue("nothing was used without a yes", b.uses(other.id).isEmpty())
        assertEquals("s3cret-@fit", AccountUse.serve(b, other.id, "password", food, "Food", confirmed = true, now = 30).getOrThrow())
        assertEquals("Food", b.uses(other.id).single().label)
    }

    @Test
    fun anAccountOnAnotherPhoneOrNotBoundAlsoAsks() {
        val b = book()
        val remote = identity("@galaxy").copy(boundDevice = "phone-b", boundDeviceName = "Galaxy", updatedBy = "phone-b", updatedAtMs = 5)
        b.applyRemote(remote)
        val loose = b.save(identity("@loose", null, null), 10)
        val list = AccountUse.candidatesIn(b, "com.instagram.android", food, labels)
        assertTrue(list.none { it.here })
        assertEquals("Food on Galaxy", list.first { it.id == remote.id }.binding)
        assertEquals("not bound", list.first { it.id == loose.id }.binding)
        val choose = AccountUse.pick(list, null)
        assertTrue("two fit and none is here: the Mind names one", choose is AccountUse.Pick.Choose)
        assertFalse("handles only, never a password", (choose as AccountUse.Pick.Choose).text.contains("s3cret"))
        val named = AccountUse.pick(list, "galaxy") as AccountUse.Pick.Use
        assertEquals(remote.id, named.candidate.id)
        assertTrue(named.confirm)
        assertTrue("an unknown handle falls back to the Secrets Card", AccountUse.pick(list, "@nobody") is AccountUse.Pick.None)
    }

    @Test
    fun trashedAccountsAndOtherAppsAreNeverOffered() {
        val b = book()
        val gone = b.save(identity("@old"), 10)
        b.moveToTrash(gone.id, 20)
        assertTrue(AccountUse.candidatesIn(b, "com.instagram.android", food, labels).isEmpty())
        assertTrue(AccountUse.candidatesIn(b, "com.zhiliaoapp.musically", food, labels).isEmpty())
        assertEquals("NO_ACCOUNT", (AccountUse.serve(b, gone.id, "password", food, "Food", true, 30).exceptionOrNull() as AccountUse.Refused).code)
        assertEquals("SLOT", (AccountUse.serve(b, gone.id, "card.number", food, "Food", true, 30).exceptionOrNull() as AccountUse.Refused).code)
    }

    @Test
    fun theCardNamesTheAccountItsBindingWhereAndWhy() {
        val c = AccountUse.Candidate("idn_" + "a".repeat(32), "com.instagram.android", "@shopnl", here = false, binding = "Food on Galaxy")
        val text = AccountUse.confirmText("Instagram", c, "Fitness", "Post the weekly drop")
        assertEquals("log in to Instagram as @shopnl in Fitness on this phone. This account is bound to Food on Galaxy. Reason: Post the weekly drop", text)
    }

    @Test
    fun usesAreKeptLocallyAndShownPlainly() {
        val b = book()
        val a = b.save(identity("@food"), 10)
        assertEquals("Not used on this phone yet", AccountsPresenter.usedLine(b.uses(a.id), labels, 0))
        b.recordUse(a.id, food, "Food", now = 86_400_000L * 3)
        b.recordUse(a.id, IdentityBook.MAIN, "Main", now = 86_400_000L * 5)
        assertEquals("Used by Main and Food tasks · 2 days ago", AccountsPresenter.usedLine(b.uses(a.id), labels, 86_400_000L * 7))
        val again = IdentityBook.fromJson(b.toJson())
        assertEquals(2, again.uses(a.id).size)
        assertFalse("uses never ride along in the hive item", a.toJson().has("uses"))
        again.moveToTrash(a.id, 1)
        again.purge(a.id, 2)
        assertTrue(again.uses(a.id).isEmpty())
    }
}
