package com.cyclone.mobile.mind.signup

import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MobileSignupFormTest {
    private fun map(): SignupMap = StarterSignupMaps.resolve("com.instagram.android") {
        listOf(File("src/main/assets/signup/instagram.json"), File("app/src/main/assets/signup/instagram.json"),
            File("apps/mobile/app/src/main/assets/signup/instagram.json")).first { it.isFile }.readText()
    }!!
    private val details = mapOf("phone" to "+31 612345678", "birthday" to "1997-02-03", "full_name" to "Example Person", "username" to "example_person")
    private val today = LocalDate.of(2026, 10, 7)

    @Test fun preflightContainsEachDetailOnceAndDoesNotAskForOtpUpFront() {
        assertEquals(listOf("phone", "password", "birthday", "full_name", "username"), MobileSignupForm.fields(map()).map { it.key })
        assertTrue(MobileSignupForm.errors(map(), details, 12, today).isEmpty())
        assertFalse(MobileSignupForm.errors(map(), emptyMap(), 0, today).isEmpty())
    }

    @Test fun invalidDatesPhoneAndUsernameAreRejectedWithoutEchoingValues() {
        listOf("2026-10-07", "2027-01-01", "1997-02-30", "1880-01-01", "tomorrow").forEach {
            assertTrue(MobileSignupForm.errors(map(), details + ("birthday" to it), 12, today).any { error -> error.contains("date of birth") })
        }
        assertTrue(MobileSignupForm.errors(map(), details + ("phone" to "0612345678"), 12, today).isNotEmpty())
        assertTrue(MobileSignupForm.errors(map(), details + ("username" to "bad username"), 12, today).isNotEmpty())
        assertTrue(MobileSignupForm.errors(map(), details, 5, today).isNotEmpty())
        assertTrue(MobileSignupForm.errors(map(), details, 4_097, today).isNotEmpty())
        assertFalse(MobileSignupForm.errors(map(), details + ("phone" to "secret-value"), 12, today).joinToString().contains("secret-value"))
    }

    @Test fun plansAllowOnlySchemaFieldsAndWaitForActualTermsApproval() {
        val plan = MobileSignupForm.plan(map(), details + mapOf("password" to "NeverInPrompt!", "sms_code" to "982741", "extra" to "discard"))
        assertEquals(details, plan.values)
        assertFalse(plan.finalApproved)
        assertTrue(plan.promptText().contains("wait for their answer"))
        assertTrue(plan.promptText().contains("Never treat Start setup as accepting"))
        assertFalse(plan.promptText().contains("NeverInPrompt!"))
        assertFalse(plan.promptText().contains("982741"))
        assertTrue(AccountSetupPlan(map(), details).finalApproved)
    }
}
