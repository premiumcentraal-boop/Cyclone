package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.identity.IdentityBook
import com.cyclone.mobile.identity.IdentityCard
import com.cyclone.mobile.identity.SavedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountsPresenterTest {
    private val food = "Cyclone_0123456789abcdef"

    private fun saved(handle: String, profile: String?, label: String?, was: String? = null) = SavedIdentity(
        SavedIdentity.newId(), "com.instagram.android", handle, IdentityCard(email = "$handle@gmail.com"), "pw", profile, label, was, 0, 0)

    @Test
    fun badgesNameTheProfilePlainly() {
        val labels = mapOf(food to "Food (renamed)")
        assertEquals("Food (renamed)", AccountsPresenter.badge(saved("a", food, "Food"), labels))
        assertEquals("Main", AccountsPresenter.badge(saved("a", IdentityBook.MAIN, "Main"), labels))
        assertEquals("Not bound (was Food)", AccountsPresenter.badge(saved("a", null, null, "Food"), labels))
        assertEquals("Not bound", AccountsPresenter.badge(saved("a", null, null), labels))
    }

    @Test
    fun filtersAndSearchNarrowTheList() {
        val book = IdentityBook()
        val a = book.save(saved("brandone", food, "Food"), 1)
        book.save(saved("solo", null, null, "Old"), 1)
        val c = book.save(saved("trashed", food, "Food"), 1)
        book.moveToTrash(c.id, 2)
        assertEquals(listOf("brandone", "solo"), AccountsPresenter.visible(book, "all", "").map { it.handle })
        assertEquals(listOf(a.id), AccountsPresenter.visible(book, food, "").map { it.id })
        assertEquals(listOf("solo"), AccountsPresenter.visible(book, "unbound", "").map { it.handle })
        assertEquals(listOf("trashed"), AccountsPresenter.visible(book, "trash", "").map { it.handle })
        assertEquals(listOf("brandone"), AccountsPresenter.visible(book, "all", "BRAND").map { it.handle })
    }

    @Test
    fun theListMasksEmailsAndSaysWhenTheTrashEmpties() {
        assertEquals("h•••@gmail.com", AccountsPresenter.maskedEmail("hello@gmail.com"))
        assertNull(AccountsPresenter.maskedEmail(""))
        val gone = saved("x", null, null).copy(trashedAtMs = 0)
        assertEquals("Deleted for good in 30 days", AccountsPresenter.purgeLine(gone, 0))
        assertEquals("Deleted for good in 1 day", AccountsPresenter.purgeLine(gone, IdentityBook.TRASH_MS - 1000))
    }
}
