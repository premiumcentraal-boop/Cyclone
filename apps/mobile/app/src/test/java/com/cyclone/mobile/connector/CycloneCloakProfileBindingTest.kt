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

    private fun identityConfig(key: ProfileConfigKey, cloakProfileId: String, name: String, model: String) = JSONObject()
        .put("profileId", key.profileId)
        .put("androidUserId", key.androidUserId)
        .put("packageName", key.packageName)
        .put("value", JSONObject().put("identityVersion", 1).put("cloakProfileId", cloakProfileId)
            .put("name", name).put("manufacturer", "Google").put("model", model)
            .put("androidRelease", "16").put("sdkInt", 36).put("hardwareId", "must-not-leak"))
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
            listOf(CycloneCloakBindingReference(profile.id, 10, "com.example.social", "pixel-identity-7", "ready")),
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

    @Test fun `identity summary chooses the most common matching app config and omits raw identifiers`() {
        val profile = record("Cyclone_dddddddddddddddd", packages = setOf("com.example.social", "com.example.video", "com.example.maps"))
        val keys = profile.packages.associateWith { ProfileConfigKey(profile.id, 10, it) }
        val configs = mapOf(
            keys.getValue("com.example.social") to identityConfig(keys.getValue("com.example.social"), "cloak-profile-a", "Pixel 9", "Pixel 9"),
            keys.getValue("com.example.video") to identityConfig(keys.getValue("com.example.video"), "cloak-profile-a", "Pixel 9", "Pixel 9"),
            keys.getValue("com.example.maps") to identityConfig(keys.getValue("com.example.maps"), "cloak-profile-b", "Other", "Other"),
        )

        val summary = CycloneCloakProfileBinding.readIdentities(listOf(profile)) { configs[it] }.single()

        assertEquals(profile.id, summary.profileId)
        assertEquals(10, summary.androidUserId)
        assertEquals(1, summary.identityVersion)
        assertEquals("Pixel 9", summary.name)
        assertEquals("Google", summary.manufacturer)
        assertEquals("Pixel 9", summary.model)
        assertEquals(2, summary.boundApps)
        assertEquals(1, summary.conflictingApps)
        assertTrue(summary.toString().contains("hardwareId").not())
        assertTrue(summary.toString().contains("cloak-profile-a").not())
    }

    @Test fun `unknown identity versions remain bound without forwarding unknown fields`() {
        val profile = record("Cyclone_eeeeeeeeeeeeeeee")
        val key = ProfileConfigKey(profile.id, 10, "com.example.social")
        val text = JSONObject().put("profileId", key.profileId).put("androidUserId", key.androidUserId)
            .put("packageName", key.packageName).put(
                "value", JSONObject().put("cloakProfileId", "cloak-profile").put("identityVersion", 2).put("name", "unknown-schema-name"),
            ).toString()

        val summary = CycloneCloakProfileBinding.readIdentities(listOf(profile)) { text }.single()

        assertEquals(null, summary.identityVersion)
        assertEquals(null, summary.name)
        assertEquals(1, summary.boundApps)
    }

    @Test fun `tied per-app configs do not export a guessed majority identity`() {
        val profile = record("Cyclone_ffffffffffffffff", packages = setOf("com.example.social", "com.example.video"))
        val keys = profile.packages.associateWith { ProfileConfigKey(profile.id, 10, it) }
        val configs = mapOf(
            keys.getValue("com.example.social") to identityConfig(keys.getValue("com.example.social"), "cloak-profile-a", "Pixel 9", "Pixel 9"),
            keys.getValue("com.example.video") to identityConfig(keys.getValue("com.example.video"), "cloak-profile-b", "Other", "Other"),
        )

        val summary = CycloneCloakProfileBinding.readIdentities(listOf(profile)) { configs[it] }.single()

        assertEquals(1, summary.boundApps)
        assertEquals(1, summary.conflictingApps)
        assertEquals(null, summary.identityVersion)
        assertEquals(null, summary.name)
        assertEquals(null, summary.model)
    }

    @Test fun `one strict reading - a string user id is not a binding, and the pill follows the worst state`() {
        val profile = record("Cyclone_abababababababab", packages = setOf("com.example.social", "com.example.video"))
        val social = ProfileConfigKey(profile.id, 10, "com.example.social")
        val video = ProfileConfigKey(profile.id, 10, "com.example.video")
        val stringUser = JSONObject(config(social, "pixel-identity-7")).put("androidUserId", "10").toString()
        assertTrue(CycloneCloakProfileBinding.readBindings(listOf(profile)) { stringUser }.isEmpty())
        assertTrue(CycloneCloakProfileBinding.readIdentities(listOf(profile)) { stringUser }.isEmpty())

        fun withState(key: ProfileConfigKey, state: String) = JSONObject(config(key, "pixel-identity-7")).put("state", state).toString()
        val states = mapOf(social to withState(social, "ready"), video to withState(video, "degraded"))
        val bindings = CycloneCloakProfileBinding.readBindings(listOf(profile)) { states[it] }
        assertEquals(setOf("ready", "degraded"), bindings.map { it.state }.toSet())
        assertEquals(CloakHealth.CHECK, CloakHealth.of(bindings))
        assertEquals(CloakHealth.NOT_WORKING, CloakHealth.of(bindings + bindings[0].copy(state = "failed")))
        assertEquals(CloakHealth.ROOTED, CloakHealth.of(listOf(bindings[0].copy(state = "unknown"))))
        assertEquals(CloakHealth.NATIVE, CloakHealth.of(emptyList()))
        assertEquals("unknown", CycloneCloakProfileBinding.readBindings(listOf(profile)) {
            if (it == social) JSONObject(config(social, "x")).put("state", "bogus").toString() else null
        }.single().state)
    }
}
