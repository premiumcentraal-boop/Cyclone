package com.cyclone.mobile.mind.one

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 75 alpha 148: control roles in two languages, recipes found by meaning, and a done check nobody answered. */
class Alpha148Test {
    private fun control(label: String, role: String = "button") = StepControl("e", label, role)

    @Test fun `a target names a role in the owner's languages, and a control with that role matches whatever its language`() {
        assertTrue(ControlRoles.matches("mijn profiel", control("Profiel", "tab")))
        assertTrue(ControlRoles.matches("profile", control("Profile", "tab")))
        assertTrue(ControlRoles.matches("open my profile", control("Profiel")))
        assertTrue(ControlRoles.matches("zoeken", control("Search")))
        assertFalse("a different role never matches", ControlRoles.matches("profile", control("Search")))
        assertFalse("a target with no role names nothing", ControlRoles.matches("the blue lamp", control("Profile")))
        assertEquals("send", ControlRoles.of("Verstuur"))
        assertEquals(null, ControlRoles.of("hamster"))
    }

    @Test fun `the shortlist keeps the control of the target's role even when the screen has many others`() {
        val many = (1..30).map { StepControl("e$it", "Row $it") }
        val screen = StepScreen("com.instagram.android", "Instagram", "feed", "f", many + StepControl("e99", "Profiel", "tab"), listOf("Feed"))
        val short = FusedBoard.shortlist(screen, Step("s", "tap", target = "mijn profiel"))
        assertTrue("the Profiel tab is among the choices", short.any { it.ref == "e99" })
        assertTrue(short.size <= FusedBoard.MAX_CONTROLS)
    }

    @Test fun `a recipe is found by the goal's words, so the filler doesn't tell two requests for one thing apart`() {
        assertEquals(Recipes.key("open my profile"), Recipes.key("Open the profile"))
        assertEquals(Recipes.key("open mijn profiel"), Recipes.key("open profiel"))
        assertFalse("another app is another recipe", Recipes.key("open my instagram profile") == Recipes.key("open my profile"))
        assertFalse("another goal is another recipe", Recipes.key("send anna a message") == Recipes.key("open my profile"))
    }
}
