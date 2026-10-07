package com.cyclone.mobile.mind.signup

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class StarterSignupMapsTest {
    private fun asset(): String = listOf(
        File("src/main/assets/signup/instagram.json"),
        File("app/src/main/assets/signup/instagram.json"),
        File("apps/mobile/app/src/main/assets/signup/instagram.json"),
    ).first { it.isFile }.readText()

    @Test fun packagedInstagramRunsWithoutMappingOrPrivateSampleValues() {
        val map = StarterSignupMaps.resolve("com.instagram.android") { asset() }!!
        assertEquals(12, map.pages.size)
        assertEquals("I agree", map.finalLabel)
        assertEquals(2, map.pages.count { it.check == SignupCheck.SMS_CODE })
        assertEquals(listOf("phone", "password", "birthday", "full_name", "username"),
            map.pages.filter { it.check == null }.flatMap { it.fields }.map { it.key })
        val prompt = AccountSetupPlan(map, mapOf("username" to "myaccount")).promptText()
        assertTrue(prompt.contains("Add Instagram account > Create new account"))
        assertTrue(prompt.contains("setup_page changed=true"))
        assertTrue(prompt.contains("abbreviated month"))
        assertTrue(prompt.contains("native code autofill on this phone"))
        assertFalse(Regex("\\+\\d{6,}|\\b\\d{4}-\\d{2}-\\d{2}\\b|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}").containsMatchIn(asset()))
    }

    @Test fun unsupportedAppsNeverLoadAnotherAppsTemplate() {
        assertNull(StarterSignupMaps.resolve("com.other.app") { error("must not read") })
        assertNull(StarterSignupMaps.resolve("../instagram") { error("must not read") })
    }

    @Test fun malformedOrMismatchedAssetsCannotStartSetup() {
        assertNull(StarterSignupMaps.resolve("com.instagram.android") { "not json" })
        assertNull(StarterSignupMaps.resolve("com.instagram.android") { asset().replace("com.instagram.android", "com.other.app") })
        assertNull(StarterSignupMaps.resolve("com.instagram.android") { asset().replace("\"complete\": true", "\"complete\": false") })
    }
}
