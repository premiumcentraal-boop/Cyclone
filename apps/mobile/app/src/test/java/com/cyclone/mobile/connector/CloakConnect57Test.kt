package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.CycloneProfileRecord
import com.cyclone.mobile.runtime.workspaces.ProfileCornerstones
import com.cyclone.mobile.runtime.workspaces.ProfileInventory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 57 P3 (alpha.121): Cyclone Cloak's connection done right (Cloak handoff CC1-CC6). */
class CloakConnect57Test {
    private val cert = "a".repeat(64)
    private val manifest = ConnectorManifest.parse("com.cyclone.cloak", mapOf("contract" to "cyclone.connector/1.2", "id" to "cyclone-cloak",
        "label" to "Cyclone Cloak", "scopes" to "profiles.read profiles.config"))
    private val carried = ConnectorApproval("cyclone-cloak", "com.cyclone.cloak", cert,
        setOf(ConnectorScope.PROFILES_READ, ConnectorScope.PROFILE_CONFIG, ConnectorScope.EVENTS_PROFILES), "Cyclone Cloak", 100L)
    private val here = ConnectorCarry.Here("com.cyclone.cloak", manifest, listOf("b".repeat(64), cert), false)

    @Test fun `Cloak's approval follows only when this install is the same app, key and id`() {
        assertEquals(ConnectorCarry.Outcome.ADOPTED, ConnectorCarry.decide(carried, here, null, null))
        val adopted = ConnectorCarry.adopted(carried, here)
        // Never more than this install's manifest asks for.
        assertEquals(setOf(ConnectorScope.PROFILES_READ, ConnectorScope.PROFILE_CONFIG), adopted.scopes)
        assertEquals(ConnectorCarry.Outcome.NOT_INSTALLED, ConnectorCarry.decide(carried, null, null, null))
        assertEquals(ConnectorCarry.Outcome.SIGNER_DIFFERS, ConnectorCarry.decide(carried, here.copy(certHistory = listOf("c".repeat(64))), null, null))
        assertEquals(ConnectorCarry.Outcome.NOT_A_CONNECTOR, ConnectorCarry.decide(carried, here.copy(manifest = null), null, null))
        assertEquals(ConnectorCarry.Outcome.NOT_A_CONNECTOR, ConnectorCarry.decide(carried, here.copy(idConflict = true), null, null))
        assertEquals(ConnectorCarry.Outcome.NOT_A_CONNECTOR, ConnectorCarry.decide(carried, here.copy(packageName = "com.evil.cloak"), null, null))
        assertEquals(ConnectorCarry.Outcome.ALREADY, ConnectorCarry.decide(carried, here, carried, null))
    }

    @Test fun `a revoke in this profile wins over an older approval, a newer approval wins over it`() {
        assertEquals(ConnectorCarry.Outcome.REVOKED_HERE, ConnectorCarry.decide(carried, here, null, revokedAt = 150L))
        assertEquals(ConnectorCarry.Outcome.ADOPTED, ConnectorCarry.decide(carried.copy(approvedAt = 200L), here, null, revokedAt = 150L))
        assertFalse(ConnectorCarry.Outcome.REVOKED_HERE.approved)
        // Only Cloak travels.
        val other = carried.copy(connectorId = "acme-profiles", packageName = "com.acme")
        assertEquals(listOf(carried), ConnectorCarry.outgoing(listOf(carried, other)))
    }

    private fun record(id: String, user: Int?, removed: Long? = null) =
        CycloneProfileRecord(id, id, user, 0, true, setOf("com.example.social"), "READY", true, removed)
    private fun tuple(id: String, user: Int, pkg: String) = JSONObject().put("profileId", id).put("androidUserId", user)
        .put("packageName", pkg).put("value", JSONObject().put("cloakProfileId", "k")).put("state", "ready").toString()

    @Test fun `bindings go with their profile or when Android says the app is gone, never with a stale app list`() {
        val b = "Cyclone_aaaaaaaaaaaaaaaa"
        val text = tuple(b, 10, "com.example.later")
        // The registry's app list doesn't name the app (added later): kept.
        assertTrue(ProfileConfigLifecycle.keep(text, listOf(record(b, 10))))
        assertTrue(ProfileConfigLifecycle.keep(text, listOf(record(b, 10, removed = 5))))
        assertFalse(ProfileConfigLifecycle.keep(text, emptyList()))
        assertFalse(ProfileConfigLifecycle.keep("not json", listOf(record(b, 10))))
        assertTrue(ProfileConfigLifecycle.stale(text, b, 10, setOf("com.cyclone.mobile")))
        assertFalse(ProfileConfigLifecycle.stale(text, b, 10, setOf("com.example.later")))
        assertFalse(ProfileConfigLifecycle.stale(text, b, 11, emptySet()))
    }

    @Test fun `a profile under a new Android user id keeps its bindings`() {
        val b = "Cyclone_aaaaaaaaaaaaaaaa"
        assertEquals(listOf(Triple(b, 10, 14)), ProfileConfigLifecycle.moves(listOf(record(b, 10)), listOf(record(b, 14), record("Cyclone_bbbbbbbbbbbbbbbb", 12))))
        val (key, rewritten) = ProfileConfigLifecycle.moved(tuple(b, 10, "com.example.social"), b, 10, 14)!!
        assertEquals(ProfileConfigKey(b, 14, "com.example.social"), key)
        val binding = CycloneCloakProfileBinding.readBindings(listOf(record(b, 14))) { if (it == key) rewritten else null }.single()
        assertEquals("k", binding.cloakProfileId)
        assertNull(ProfileConfigLifecycle.moved(tuple(b, 10, "com.example.social"), b, 11, 14))
        assertNull(ProfileConfigLifecycle.moved(tuple(b, 10, "com.example.social"), b, 10, 10))
    }

    private fun inventory(id: String, at: Long, manager: String?, rooted: Boolean?) =
        ProfileInventory(id, at, "x", manager, rooted, emptyList())

    @Test fun `root status is facts from the last switches, nothing more`() {
        val status = ConnectorRootStatus.build(listOf(inventory("Cyclone_bbbbbbbbbbbbbbbb", 5, "KERNELSU", false),
            inventory("Cyclone_aaaaaaaaaaaaaaaa", 9, "MAGISK", true), inventory("Cyclone_cccccccccccccccc", 3, null, null)), 8 to true)
        assertEquals(1, status.getInt("version"))
        assertEquals("magisk", status.getString("rootManager"))
        assertEquals(8, status.getJSONObject("profileRoom").getInt("limit"))
        val profiles = status.getJSONArray("profiles")
        assertEquals("Cyclone_aaaaaaaaaaaaaaaa", profiles.getJSONObject(0).getString("id"))
        assertTrue(profiles.getJSONObject(0).getBoolean("rootProven"))
        assertTrue(profiles.getJSONObject(2).isNull("rootProven") && profiles.getJSONObject(2).isNull("checkedAt"))
        val empty = ConnectorRootStatus.build(emptyList(), null)
        assertTrue(empty.isNull("rootManager") && empty.isNull("profileRoom") && empty.getJSONArray("profiles").length() == 0)
        assertFalse(status.toString().contains("com.") || status.toString().contains("/data"))
    }

    @Test fun `root status needs its own scope, and hello says at least minor 2`() {
        assertTrue(ConnectorContract.MINOR >= 2)
        assertEquals(ConnectorScope.DEVICE_ROOT_READ, ConnectorScope.of("device.root.read"))
    }

    @Test fun `the has-line says whether Cloak is approved there`() {
        val apps = listOf(ProfileInventory.App("com.cyclone.cloak", ProfileCornerstones.Role.CLOAK, "Cloak", true))
        val base = ProfileInventory("Cyclone_aaaaaaaaaaaaaaaa", 1, null, null, null, apps)
        assertEquals("Cloak ✓", base.line())
        assertEquals("Cloak ✓ approved ✓", base.copy(cloakApproved = true).line())
        val refused = base.copy(cloakApproved = false, cloakNote = ConnectorCarry.Outcome.SIGNER_DIFFERS.text)
        assertEquals("Cloak ✓ approved ✗", refused.line())
        assertEquals(refused, ProfileInventory.fromJson(refused.toJson()))
    }

    @Test fun `the carry report brings back what became of Cloak's approval`() {
        val report = com.cyclone.mobile.runtime.workspaces.CarryReport(1, "Main", 0, 0, 0, 0, cloak = "SIGNER_DIFFERS")
        assertEquals(report, com.cyclone.mobile.runtime.workspaces.CarryReport.fromJson(report.toJson()))
        assertNull(com.cyclone.mobile.runtime.workspaces.CarryReport.fromJson(report.toJson().put("cloak", "drop table")).cloak)
    }
}
