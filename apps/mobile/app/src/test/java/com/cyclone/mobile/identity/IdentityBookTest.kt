package com.cyclone.mobile.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBookTest {
    private val food = "Cyclone_0123456789abcdef"
    private val fit = "Cyclone_fedcba9876543210"

    private fun identity(handle: String = "brandone", profile: String? = food, label: String? = "Food") = SavedIdentity(
        id = SavedIdentity.newId(), app = "com.instagram.android", handle = handle,
        card = IdentityCard.fromSignup(mapOf("full_name" to "Brand One", "birthday" to "1990-04-02", "email" to "hello@brand.one", "gender" to "x")),
        password = "s3cret-Password!", profileId = profile, profileLabel = label, wasProfileLabel = null, createdAtMs = 0, updatedAtMs = 0,
    )

    @Test
    fun aSavedAccountKeepsItsCardAndPasswordThroughJson() {
        val book = IdentityBook()
        val saved = book.save(identity(), now = 10)
        val again = IdentityBook.fromJson(book.toJson()).get(saved.id)!!
        assertEquals("Brand One", again.card.fullName)
        assertEquals("1990-04-02", again.card.birthday)
        assertEquals("s3cret-Password!", again.password)
        assertEquals(food, again.profileId)
        assertEquals(10L, again.createdAtMs)
        assertFalse("the password never shows in toString", again.toString().contains("s3cret"))
    }

    @Test
    fun theSameAccountSavedTwiceIsUpdatedNotDuplicated() {
        val book = IdentityBook()
        val first = book.save(identity(), now = 10)
        val second = book.save(identity(handle = "BrandOne").copy(password = "new"), now = 20)
        assertEquals(first.id, second.id)
        assertEquals(1, book.all().size)
        assertEquals("new", book.get(first.id)!!.password)
        assertEquals(10L, book.get(first.id)!!.createdAtMs)
    }

    @Test
    fun aDeletedProfileLeavesItsAccountsNotBound() {
        val book = IdentityBook()
        val saved = book.save(identity(), now = 10)
        val main = book.save(identity(handle = "other", profile = IdentityBook.MAIN, label = "Main"), now = 10)
        assertEquals(0, book.applyRoster(setOf(food), now = 20))
        assertEquals(1, book.applyRoster(emptySet(), now = 30))
        val after = book.get(saved.id)!!
        assertNull(after.profileId)
        assertEquals("Food", after.wasProfileLabel)
        assertEquals("s3cret-Password!", after.password)
        assertTrue("Main is never gone", book.get(main.id)!!.bound)
        val rebound = book.bind(saved.id, fit, "Fitness", now = 40)!!
        assertEquals(fit, rebound.profileId)
        assertNull(rebound.wasProfileLabel)
    }

    @Test
    fun theTrashKeepsAnAccountThirtyDays() {
        val book = IdentityBook()
        val saved = book.save(identity(), now = 0)
        book.moveToTrash(saved.id, now = 1_000)
        assertEquals(listOf(saved.id), book.trash().map { it.id })
        assertTrue(book.active().isEmpty())
        assertEquals(0, book.expire(now = 1_000 + IdentityBook.TRASH_MS - 1))
        book.restore(saved.id, now = 2_000)
        assertEquals(listOf(saved.id), book.active().map { it.id })
        assertFalse("only something in the trash is deleted forever", book.purge(saved.id))
        book.moveToTrash(saved.id, now = 3_000)
        assertEquals(1, book.expire(now = 3_000 + IdentityBook.TRASH_MS))
        assertTrue(book.all().isEmpty())
    }

    @Test
    fun junkIsRefusedWhenRead() {
        val json = IdentityBook().apply { save(identity(), now = 1) }.toJson()
        json.getJSONArray("identities").getJSONObject(0).put("app", "../../etc")
        assertTrue(IdentityBook.fromJson(json).all().isEmpty())
        assertTrue(IdentityBook.fromJson(null).all().isEmpty())
        val card = IdentityCard.fromSignup(mapOf("full_name" to "A\u0000B", "gender" to "x"))
        assertEquals("A B", card.fullName)
        assertEquals(listOf("Full name" to "A B"), card.rows())
    }
}
