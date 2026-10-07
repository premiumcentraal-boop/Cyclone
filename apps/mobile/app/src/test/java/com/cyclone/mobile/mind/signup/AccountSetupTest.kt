package com.cyclone.mobile.mind.signup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 43 T7: an Account Setup run follows the sign-up map with one row's values; the password comes from the vault. */
class AccountSetupTest {
    private fun map(): SignupMap {
        val r = SignupRecorder("com.instagram.android", "Instagram", "350.0") { 1L }
        r.page("Enter your email", listOf(mapOf("label" to "Email", "kind" to "email")), "Next", null)
        r.page("Confirm your email", listOf(mapOf("label" to "Confirmation code", "kind" to "text")), "Next", "email_code")
        r.page("Create a password", listOf(mapOf("label" to "Password", "kind" to "password")), "Next", null)
        r.page("Gender", listOf(mapOf("label" to "Gender", "kind" to "gender", "required" to false)), "Next", null)
        r.final("Sign up")
        return r.finish(true)
    }

    @Test fun thePlanNamesEveryPageAndValueButNeverAPassword() {
        val text = AccountSetupPlan(map(), mapOf("email" to "hello@brand.one")).promptText()
        assertTrue(text.contains("Page 1: \"Enter your email\""))
        assertTrue(text.contains("Email (email): \"hello@brand.one\""))
        assertTrue(text.contains("(a person's step: a code sent by email)"))
        assertTrue(text.contains("Password (password): use vault_fill what=password"))
        assertTrue(text.contains("Gender (gender, optional): leave empty"))
        assertTrue(text.contains("Confirmation code (text): not given: ask the owner"))
        assertTrue(text.contains("The account-creation control is \"Sign up\""))
        assertTrue(text.contains("without asking again"))
    }

    @Test fun valuesFromTheWireAreStrict() {
        assertEquals(mapOf("email" to "a@b.co"), AccountSetupPlan.values(JSONObject().put("email", "a@b.co")))
        assertNull(AccountSetupPlan.values(JSONObject().put("Email", "a@b.co")))
        assertNull(AccountSetupPlan.values(JSONObject().put("email", 5)))
        assertNull(AccountSetupPlan.values(JSONObject().put("email", "a\nb")))
        assertNull(AccountSetupPlan.values(null))
    }

    @Test fun progressGoesOutInTheShapeThePcChecks() {
        AccountSetupProgress.set("m1", AccountSetupProgress(AccountSetupProgress.VERIFICATION, 2, 4, 3, null, "a code sent by email"))
        val json = AccountSetupProgress.of("m1")!!.toJson()
        assertEquals(setOf("state", "page", "pages", "drift", "handle", "note"), json.keys().asSequence().toSet())
        assertEquals("verification", json.getString("state"))
        assertTrue(json.isNull("handle"))
        assertFalse(json.isNull("drift"))
    }

    @Test fun mappedFormatsAndChoicesReachTheAccountRunner() {
        val recorder = SignupRecorder("com.instagram.android", "Instagram", "350.0") { 1L }
        recorder.page("Birthday", listOf(mapOf("label" to "Date of birth", "kind" to "birthday",
            "hint" to "YYYY-MM-DD; picker uses abbreviated month, day, year")), "Next", null)
        recorder.page("Gender", listOf(mapOf("label" to "Gender", "kind" to "gender",
            "choices" to listOf("Female", "Male", "Prefer not to say"))), "Next", null)
        recorder.final("Create")
        val text = AccountSetupPlan(recorder.finish(true), mapOf("birthday" to "1990-05-17", "gender" to "Prefer not to say")).promptText()
        assertTrue(text.contains("Date of birth (birthday): \"1990-05-17\""))
        assertTrue(text.contains("Format hint: \"YYYY-MM-DD; picker uses abbreviated month, day, year\""))
        assertTrue(text.contains("Choices: \"Female\", \"Male\", \"Prefer not to say\""))
    }

    @Test fun aMappedSmsFieldUsesNativeRetrievalRatherThanAMissingRowValue() {
        val recorder = SignupRecorder("com.instagram.android", "Instagram", "350.0") { 1L }
        recorder.page("Enter the confirmation code", listOf(mapOf("label" to "Confirmation code", "kind" to "number")), "Next", "sms_code")
        recorder.final("Create")
        val text = AccountSetupPlan(recorder.finish(true), emptyMap()).promptText()
        assertTrue(text.contains("Confirmation code (number): use setup_page check=sms_code for native code autofill on this phone"))
        assertFalse(text.contains("not given: ask the owner"))
        assertTrue(text.contains("ask the owner only if it cannot retrieve the code"))
    }

    @Test fun verificationAfterCreationDoesNotAskForAnotherFinalClick() {
        val recorder = SignupRecorder("com.instagram.android", "Instagram", "350.0") { 1L }
        recorder.page("Terms", emptyList(), "I agree", null)
        recorder.page("Confirm profile", listOf(mapOf("label" to "Confirmation code", "kind" to "number")), "Next", "sms_code")
        recorder.final("I agree")
        val text = AccountSetupPlan(recorder.finish(true), emptyMap()).promptText()
        assertTrue(text.contains("Page 2: \"Confirm profile\""))
        assertTrue(text.contains("Complete any subsequent verification or onboarding"))
        assertFalse(text.contains("Finally press"))
    }
}
