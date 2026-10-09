package com.cyclone.mobile.runtime.workspaces

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 57 P2 (alpha.120): every profile complete: cornerstone apps, Cyclone's settings and saved work, and a report. */
class ProfileComplete57Test {

    // Settings ---------------------------------------------------------------------------------------------------------

    @Test fun `every settings file is classified once and secrets never travel`() {
        val files = PortableSettings.table.map { it.file }
        assertEquals(files.size, files.toSet().size)
        listOf("cyclone_vault_secrets_v1", "cyclone_ai_secrets", "cyclone_codes", "cyclone_sealed_leases", "cyclone_gateway_trust_v33",
            "cyclone_pc_gateway_v293", "cyclone_desktop_gateway_v1").forEach {
            assertEquals(it, PortableSettings.Kind.NEVER, PortableSettings.entry(it)?.kind)
            assertFalse(CarryRules.carriesSetting(it, "x", "y"))
        }
        assertEquals(PortableSettings.Kind.PER_PROFILE, PortableSettings.entry("cyclone_ai")?.kind)
        assertFalse(CarryRules.carriesSetting("cyclone_unclassified", "x", true))
    }

    @Test fun `Cyclone's own settings now travel, through the secret filter`() {
        assertTrue(CarryRules.carriesSetting("cyclone_hands", "style", "natural"))
        assertTrue(CarryRules.carriesSetting("cyclone_modes", "keep_listening_s", 8))
        assertTrue(CarryRules.carriesSetting("cyclone_fast", "enabled", true))
        assertTrue(CarryRules.carriesSetting("cyclone_planes", "mode", "automatic"))
        assertTrue(CarryRules.carriesSetting("cyclone_setup_cards", "seen", setOf("a", "b")))
        assertTrue(CarryRules.carriesSetting("cyclone_drive", "announce_apps", setOf("com.whatsapp")))
        assertTrue(CarryRules.carriesSetting("cyclone_cornerstones", "marked", setOf("org.lsposed.manager")))
        assertFalse(CarryRules.carriesSetting("cyclone_hands", "style", "password: hunter2"))
        assertFalse(CarryRules.carriesSetting("cyclone_hands", "auth_token", "x"))
        assertFalse(CarryRules.carriesSetting("cyclone_user_md", "pending_compress", 3))
        assertFalse(CarryRules.carriesSetting("cyclone_automation_studio", "runs", "[]"))
        assertFalse(CarryRules.carriesSetting("cyclone_setup_cards", "seen", setOf("my password is hunter2")))
    }

    private fun routine(id: String, name: String) = JSONObject().put("id", id).put("name", name).put("steps", JSONArray())

    @Test fun `routines merge by id, keep what is only here, and never carry a secret`() {
        val local = JSONArray().put(routine("r1", "Morning")).put(routine("r2", "Old")).toString()
        val incoming = JSONArray().put(routine("r2", "Renamed")).put(routine("r3", "New"))
            .put(routine("r4", "Type password hunter2")).put(JSONObject().put("name", "no id"))
        val safe = CarryRules.safeItems("cyclone_automation_studio", "automations", incoming.toString())
        assertEquals(listOf("r2", "r3"), (0 until safe.length()).map { safe.getJSONObject(it).getString("id") })
        val (text, changed) = CarryRules.mergeItems("cyclone_automation_studio", "automations", local, incoming)!!
        val merged = JSONArray(text)
        assertEquals(2, changed)
        assertEquals(listOf("r1", "r2", "r3"), (0 until merged.length()).map { merged.getJSONObject(it).getString("id") })
        assertEquals("Renamed", merged.getJSONObject(1).getString("name"))
        assertNull(CarryRules.mergeItems("cyclone_automation_studio", "automations", text, incoming))
        assertTrue(CarryRules.safeItems("cyclone_hands", "style", incoming.toString()).length() == 0)
    }

    // Files ------------------------------------------------------------------------------------------------------------

    private fun install(id: String, at: Long, lastRun: Long? = null, input: String = "Amsterdam") = JSONObject().put("id", id)
        .put("version", "1").put("inputs", JSONObject().put("city", input)).put("installedAt", at).put("source", "catalog")
        .put("runs", 0).put("lastRunAt", lastRun ?: JSONObject.NULL)

    @Test fun `market installs - the one used last wins, and secret inputs never leave`() {
        val local = JSONArray().put(install("weather", 10, lastRun = 50)).put(install("news", 10)).toString()
        val incoming = JSONArray().put(install("weather", 20, lastRun = 40)).put(install("news", 30)).put(install("bank", 5, input = "pin code 1234"))
        assertEquals(2, PortableFiles.safeInstalls(incoming.toString()).length())
        val (text, changed) = PortableFiles.mergeInstalls(local, incoming)!!
        assertEquals(1, changed)
        val merged = JSONArray(text)
        assertEquals(50L, merged.getJSONObject(0).getLong("lastRunAt"))
        assertEquals(30L, merged.getJSONObject(1).getLong("installedAt"))
        assertFalse(text.contains("bank"))
    }

    @Test fun `owner skills and their anchors are only added, manuals only when missing`() {
        val skill = { id: String -> JSONObject().put("id", id).put("name", "Open chats").put("goal", "open chats") }
        val local = JSONArray().put(skill("you.aaa")).toString()
        val incoming = JSONArray().put(skill("you.aaa").put("name", "changed")).put(skill("you.bbb")).put(skill("market.x"))
        val (text, added) = PortableFiles.mergeSkills(local, incoming)!!
        assertEquals(1, added)
        assertTrue(text.contains("you.bbb") && !text.contains("changed") && !text.contains("market.x"))
        val anchors = PortableFiles.safeAnchors(JSONObject().put("you.bbb", JSONObject().put("screen", "chats"))
            .put("you.zzz", JSONObject()).toString(), JSONArray().put(skill("you.bbb")))
        assertEquals(setOf("you.bbb"), anchors.keys().asSequence().toSet())
        assertNull(PortableFiles.mergeAnchors(JSONObject().put("you.bbb", JSONObject()).toString(), anchors))

        val manuals = mapOf("com.whatsapp.json" to "{\"words\":[]}", "com.bank.json" to "{\"note\":\"password hunter2\"}",
            "../evil.json" to "{}", "org.app.json" to "not json")
        assertEquals(setOf("com.whatsapp.json"), PortableFiles.safeManuals(manuals).keys)
        assertTrue(PortableFiles.manualsToAdd(setOf("com.whatsapp.json"), manuals).isEmpty())
        assertEquals(setOf("com.whatsapp.json"), PortableFiles.manualsToAdd(emptySet(), manuals).keys)
    }

    // Cornerstones -----------------------------------------------------------------------------------------------------

    @Test fun `cornerstones - Cyclone and root first and required, Cloak and the owner's apps best effort`() {
        val items = ProfileCornerstones.resolve("com.cyclone.mobile",
            setOf("moe.shizuku.privileged.api", "com.topjohnwu.magisk", "io.hidden.mgsk"), "io.hidden.mgsk",
            "com.cyclone.cloak", setOf("org.lsposed.manager", "com.cyclone.cloak", "bad name"))
        assertEquals(listOf("com.cyclone.mobile", "com.topjohnwu.magisk", "io.hidden.mgsk", "moe.shizuku.privileged.api",
            "com.cyclone.cloak", "org.lsposed.manager"), items.map { it.packageName })
        assertEquals(listOf(true, true, true, true, false, false), items.map { it.required })
        assertEquals(ProfileCornerstones.Role.ROOT_MANAGER, items[2].role)
        assertEquals(ProfileCornerstones.Role.CLOAK, items[4].role)
        assertFalse(ProfileCornerstones.canMark("com.cyclone.mobile", "com.cyclone.mobile", emptySet()))
        val full = (1..ProfileCornerstones.MAX_MARKED).map { "com.app.a$it" }.toSet()
        assertFalse(ProfileCornerstones.canMark("com.app.extra", "com.cyclone.mobile", full))
        assertTrue(ProfileCornerstones.canMark("com.app.a1", "com.cyclone.mobile", full))
        assertEquals(ProfileCornerstones.MAX_MARKED + 1,
            ProfileCornerstones.resolve("com.cyclone.mobile", emptySet(), null, null, full + "com.app.zz").size)
    }

    // The report -------------------------------------------------------------------------------------------------------

    @Test fun `Profile C has - one honest line, and it reads back`() {
        val apps = listOf(
            ProfileInventory.App("com.cyclone.mobile", ProfileCornerstones.Role.CYCLONE, "Cyclone", true),
            ProfileInventory.App("com.topjohnwu.magisk", ProfileCornerstones.Role.ROOT_MANAGER, "Magisk", true, rootShared = true),
            ProfileInventory.App("moe.shizuku.privileged.api", ProfileCornerstones.Role.SUPPORT, "Shizuku", true),
            ProfileInventory.App("com.cyclone.cloak", ProfileCornerstones.Role.CLOAK, "Cloak", true),
            ProfileInventory.App("org.lsposed.manager", ProfileCornerstones.Role.OWNER, "LSPosed", false, note = "Android didn't install it"),
        )
        val inventory = ProfileInventory("Cyclone_0123456789abcdef", 1L, "5.0.0-alpha.120.dev1", "MAGISK", true, apps, 47, 312, 3)
        assertEquals("Cyclone 5.0.0-alpha.120.dev1 ✓ · Magisk ✓ root ✓ · Shizuku ✓ · Cloak ✓ · 0 of 1 of your apps ✗ · 47 settings ✓ · 312 skills ✓",
            inventory.line())
        assertEquals(listOf("org.lsposed.manager"), inventory.missing.map { it.packageName })
        assertEquals(inventory, ProfileInventory.fromJson(inventory.toJson()))
        val unknown = inventory.copy(rootProven = null, settings = -1, skills = -1)
        assertTrue(unknown.line().contains("root ?") && !unknown.line().contains("settings"))
    }

    @Test fun `a carry report reads old and new bundles`() {
        val report = CarryReport(1, "Work", 2, 0, 3, 4, files = 5, settingsInPlace = 40, skillsTotal = 300)
        assertEquals(report, CarryReport.fromJson(report.toJson()))
        val old = CarryReport.fromJson(JSONObject().put("at", 1).put("from", "Main").put("skills", 2))
        assertEquals(-1, old.skillsTotal)
        assertEquals(0, old.files)
        assertEquals("Brought 3 skills, your settings and 5 saved items from Work", CarryRules.line(report.copy(memories = 0)))
    }

    @Test fun `the debug file carries what each profile has`() {
        val inventory = ProfileInventory("Cyclone_0123456789abcdef", 1L, "x", null, false, emptyList())
        val facts = ProfileDebugReport.Facts(1L, mapOf("versionName" to "x"), null, null, null, null, emptyList(), emptyMap(), emptyList(),
            emptyList(), null, null, inventories = listOf(inventory))
        assertTrue(ProfileDebugReport.json(facts).toString().contains("\"inventories\""))
        assertTrue(ProfileDebugReport.summary(facts).contains("Cyclone_0123456789abcdef has: root ✗"))
    }
}
