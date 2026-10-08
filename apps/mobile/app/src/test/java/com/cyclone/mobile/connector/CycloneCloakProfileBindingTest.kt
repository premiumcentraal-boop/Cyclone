package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneCloakProfileBindingTest {
    private fun record(
        id: String,
        user: Int? = 10,
        packages: Set<String> = setOf("com.example.social"),
        ready: Boolean = true,
        removedAtMs: Long? = null,
    ) = CycloneProfileRecord(id, id, user, 0, user != 0, packages, "READY", ready, removedAtMs)

    private fun config(key: ProfileConfigKey, cloakProfileId: String) = JSONObject()
        .put("profileId", key.profileId)
        .put("androidUserId", key.androidUserId)
        .put("packageName", key.packageName)
        .put("value", JSONObject().put("cloakProfileId", cloakProfileId))
        .put("state", "ready")
        .toString()

    @Test fun `reads Cloak identity only from matching per-app config`() {
        val profile = record("Cyclone_aaaaaaaaaaaaaaaa", packages = setOf("com.example.social", "com.example.video"))
        val configs = mapOf(
            ProfileConfigKey(profile.id, 10, "com.example.social") to config(
                ProfileConfigKey(profile.id, 10, "com.example.social"), "pixel-identity-7",
            ),
        )

        val bindings = CycloneCloakProfileBinding.readBindings(listOf(profile)) { configs[it] }

        assertEquals(
            listOf(CycloneCloakBindingReference(profile.id, 10, "com.example.social", "pixel-identity-7")),
            bindings,
        )
    }

    @Test fun `ignores missing malformed empty and mismatched configuration`() {
        val profile = record("Cyclone_bbbbbbbbbbbbbbbb")
        val key = ProfileConfigKey(profile.id, 10, "com.example.social")
        val wrongKey = ProfileConfigKey(profile.id, 11, "com.example.social")
        val inputs = listOf<String?>(
            null,
            "not-json",
            "{}",
            config(key, " "),
            config(wrongKey, "pixel-identity-7"),
        )

        inputs.forEach { text ->
            assertTrue(CycloneCloakProfileBinding.readBindings(listOf(profile)) { text }.isEmpty())
        }
    }

    @Test fun `does not label unfinished trashed or unregistered records as Rooted`() {
        val configured = CycloneProfileRecord("Cyclone_cccccccccccccccc", "Work", 10, 0, true,
            setOf("com.example.social"), "READY", true)
        val config = config(ProfileConfigKey(configured.id, 10, "com.example.social"), "pixel-identity-7")
        val ineligible = listOf(
            configured.copy(ready = false),
            configured.copy(removedAtMs = 1L),
            configured.copy(androidUserId = null),
        )

        ineligible.forEach { profile ->
            assertTrue(CycloneCloakProfileBinding.readBindings(listOf(profile)) { config }.isEmpty())
        }
    }
}
