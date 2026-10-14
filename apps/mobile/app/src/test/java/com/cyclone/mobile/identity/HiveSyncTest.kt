package com.cyclone.mobile.identity

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiveSyncTest {
    private val food = "Cyclone_0123456789abcdef"
    private val key = ByteArray(32) { it.toByte() }
    private val keys = mapOf(1 to key)

    private fun phone(me: String, name: String) = IdentityBook().also { it.me = me; it.myName = name }

    private fun identity(handle: String = "brandone") = SavedIdentity(
        id = SavedIdentity.newId(), app = "com.instagram.android", handle = handle,
        card = IdentityCard.fromSignup(mapOf("full_name" to "Brand One", "email" to "hello@brand.one")),
        password = "s3cret-Password!", profileId = food, profileLabel = "Food", wasProfileLabel = null, createdAtMs = 0, updatedAtMs = 0,
    )

    /** One sync for [book]: hands it [items], returns its outbox (and the acks the PC would send next time). */
    private fun sync(book: IdentityBook, items: List<JSONObject> = emptyList(), acked: List<JSONObject> = emptyList()): List<JSONObject> =
        HiveSync.apply(book, keys, JSONArray(items), JSONArray(acked)).outbox

    private fun ack(item: JSONObject) = JSONObject().put("itemId", item.getString("itemId")).put("at", item.getLong("at"))

    @Test
    fun anAccountMadeOnOnePhoneArrivesOnAnotherWithItsBinding() {
        val a = phone("phone-a", "Pixel 8")
        val b = phone("phone-b", "Galaxy")
        val saved = a.save(identity(), now = 100)
        assertEquals("phone-a", saved.boundDevice)
        val out = sync(a)
        assertEquals(1, out.size)
        assertFalse("the password is never in the clear on the wire", out[0].toString().contains("s3cret"))
        assertEquals("phone-a", out[0].getString("by"))
        sync(b, out)
        val copy = b.get(saved.id)!!
        assertEquals("s3cret-Password!", copy.password)
        assertEquals("Brand One", copy.card.fullName)
        assertFalse("bound on phone A, not here", copy.boundHere("phone-b"))
        assertEquals("Pixel 8", copy.boundDeviceName)
        assertTrue("b received it; nothing for b to push", b.pending().isEmpty())
        // The hive kept a's write: it leaves a's outbox.
        assertTrue(sync(a, acked = listOf(ack(out[0]))).isEmpty())
    }

    @Test
    fun theNewestVersionWinsAndALateOlderOneIsIgnored() {
        val a = phone("phone-a", "Pixel")
        val b = phone("phone-b", "Galaxy")
        val saved = a.save(identity(), now = 100)
        val first = sync(a)
        sync(b, first)
        b.moveToTrash(saved.id, now = 200)
        val trashed = sync(b)
        a.unbind(saved.id, now = 150)  // a's own older edit, made offline
        val unbound = sync(a)
        sync(a, trashed)
        assertTrue("b's later trash wins on a", a.get(saved.id)!!.trashed)
        assertEquals(food, a.get(saved.id)!!.profileId)
        assertFalse("a's lost edit leaves its outbox", saved.id in a.pending())
        sync(b, unbound)
        assertEquals("the older edit doesn't undo b's version", food, b.get(saved.id)!!.profileId)
    }

    @Test
    fun deleteForeverReachesEveryPhoneAndNothingComesBack() {
        val a = phone("phone-a", "Pixel")
        val b = phone("phone-b", "Galaxy")
        val saved = a.save(identity(), now = 100)
        val made = sync(a)
        sync(b, made)
        a.moveToTrash(saved.id, now = 200)
        a.purge(saved.id, now = 300)
        val tomb = sync(a)
        assertTrue(tomb.single().getBoolean("gone"))
        sync(b, tomb)
        assertNull(b.get(saved.id))
        sync(b, made)  // a stale copy relayed late
        assertNull("a deleted account never comes back", b.get(saved.id))
    }

    @Test
    fun anItemThatDoesNotOpenOrLiesAboutItselfIsSkipped() {
        val a = phone("phone-a", "Pixel")
        val b = phone("phone-b", "Galaxy")
        a.save(identity(), now = 100)
        val item = sync(a).single()
        val moved = JSONObject(item.toString()).put("at", 999)  // the PC reorders it: the AAD no longer matches
        val other = JSONObject(item.toString()).put("itemId", "idn_" + "f".repeat(32))
        val wrongKey = JSONObject(item.toString()).put("keyVersion", 2)
        val outcome = HiveSync.apply(b, keys, JSONArray(listOf(moved, other, wrongKey)), JSONArray())
        assertEquals(0, outcome.applied)
        assertTrue(b.all().isEmpty())
    }

    @Test
    fun aProfileRosterNeverUnbindsAnotherPhonesAccount() {
        val a = phone("phone-a", "Pixel")
        val b = phone("phone-b", "Galaxy")
        val saved = a.save(identity(), now = 100)
        sync(b, sync(a))
        assertEquals(0, b.applyRoster(emptySet(), now = 200))
        assertEquals(food, b.get(saved.id)!!.profileId)
        assertEquals(1, a.applyRoster(emptySet(), now = 200))
    }

    @Test
    fun accountsSavedBeforeJoiningAreStampedAndPushedOnce() {
        val book = IdentityBook()  // not in the hive yet
        val saved = book.save(identity(), now = 100)
        assertNull(saved.boundDevice)
        val restored = IdentityBook.fromJson(book.toJson())
        restored.me = "phone-a"
        restored.myName = "Pixel"
        restored.markAllPending()
        val out = sync(restored)
        assertEquals("phone-a", out.single().getString("by"))
        assertEquals("phone-a", restored.get(saved.id)!!.boundDevice)
        assertTrue("an account bound here before joining is still bound here", restored.get(saved.id)!!.boundHere("phone-a"))
    }

    @Test
    fun leavingDropsOtherPhonesAccountsAndKeepsThisPhones() {
        val a = phone("phone-a", "Pixel")
        val b = phone("phone-b", "Galaxy")
        val fromA = a.save(identity("one"), now = 100)
        sync(b, sync(a))
        val mine = b.save(identity("two"), now = 200)
        assertEquals(1, b.leaveHive())
        assertNull(b.get(fromA.id))
        assertEquals(mine.id, b.all().single().id)
    }

    @Test
    fun theOutboxAndTombstonesSurviveARestart() {
        val a = phone("phone-a", "Pixel")
        val saved = a.save(identity(), now = 100)
        a.moveToTrash(saved.id, 200)
        a.purge(saved.id, 300)
        val again = IdentityBook.fromJson(a.toJson())
        assertEquals(setOf(saved.id), again.pending())
        assertEquals(300L, again.gone()[saved.id])
    }

    @Test
    fun theAadIsTheOneGlassBuilds() {
        assertEquals("cyclone-hive-item/v1\nidn_x\naccount\n2\n5\nglass\n1",
            String(HiveSync.aad("idn_x", "account", 2, 5, "glass", true), Charsets.UTF_8))
    }
}
