package com.cyclone.mobile.connector

import com.cyclone.mobile.runtime.workspaces.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfilePackageInventoryTest {
    private val id = "Cyclone_aaaaaaaaaaaaaaaa"
    private val profile = CycloneProfileRecord(id, "Work", 11, 0, true, setOf("com.example.app", "com.gone.app"), "READY", true)
    private fun inventory(user: Int = 11, profileId: String = id) = ProfilePackageInventory.build(profileId, user,
        setOf("com.example.app", "com.android.settings", "dev.cyclone.cloak"), setOf("com.android.settings"),
        setOf("com.android.settings", "dev.cyclone.cloak")) { it }

    @Test fun ordinaryBundledAndSystemAppsAreUniqueActualInstalledRecords() {
        val apps = inventory()
        assertEquals(3, apps.size)
        assertTrue(apps.all { it.installed && it.androidUserId == 11 && it.profileId == id })
        assertFalse(apps.single { it.packageName == "com.example.app" }.system)
        assertTrue(apps.single { it.packageName == "com.android.settings" }.system)
        assertTrue(apps.single { it.packageName == "dev.cyclone.cloak" }.bundled)
        assertFalse(apps.any { it.packageName == "com.gone.app" })
        assertTrue(ProfilePackageInventory.build(id, 11, emptySet(), setOf("com.android.settings"), setOf("dev.cyclone.cloak")) { it }.isEmpty())
    }

    @Test fun multiUserPackagesHaveSeparateIdentitiesAndNeverDefaultToZero() {
        val all = inventory() + inventory(15, "Cyclone_bbbbbbbbbbbbbbbb")
        assertEquals(6, all.map { Triple(it.profileId, it.androidUserId, it.packageName) }.toSet().size)
        assertEquals(setOf(11, 15), all.map { it.androidUserId }.toSet())
        assertFalse(ProfileConfigKey(id, 11, "com.android.settings").storageKey() == ProfileConfigKey(id, 15, "com.android.settings").storageKey())
    }

    @Test fun inventoryCommandsIncludeSystemAppsButNotUninstalledAppsAndStayReadOnly() {
        assertEquals("/system/bin/pm list packages --user 11", ProfileSetupPlan.shell(ProfileSetupPlan.listProfileInventory(11)))
        assertEquals("/system/bin/pm list packages -s --user 11", ProfileSetupPlan.shell(ProfileSetupPlan.listProfileInventory(11, true)))
        assertEquals("/system/bin/pm list packages --user 0", ProfileSetupPlan.shell(ProfileSetupPlan.listProfileInventory(0)))
        assertEquals(setOf("android", "com.android.settings"), ProfilePackageInventory.parse("package:android\npackage:com.android.settings\npackage:com.android.settings"))
        assertTrue(runCatching { ProfilePackageInventory.parse("package:com.android.settings\nError: denied") }.isFailure)
        assertTrue(runCatching { ProfileSetupPlan.listProfileInventory(-1) }.isFailure)
    }

    @Test fun largeInventoriesAreCompleteBeyondTheOldEightKiBCommandCap() {
        val output = (1..2000).joinToString("\n") { "package:com.example.app$it" }
        assertTrue(output.length > 8192)
        assertEquals(2000, ProfilePackageInventory.parse(output).size)
        assertTrue(runCatching { ProfilePackageInventory.parse("x".repeat(ProfilePackageInventory.MAX_OUTPUT_CHARS + 1)) }.isFailure)
    }

    @Test fun collectionReadsTheSameExplicitUserForMembershipAndClassificationAndFailsWithoutFallback() {
        val commands = mutableListOf<String>()
        val apps = ProfilePackageInventory.collect(id, 15, setOf("com.absent.essential"), { command ->
            val shell = ProfileSetupPlan.shell(command)
            commands += shell
            if (shell.contains(" -s ")) "package:com.android.settings" else "package:com.android.settings\npackage:com.example.app"
        }) { "Label for $it" }
        assertEquals(2, commands.size)
        assertTrue(commands.all { it.endsWith("--user 15") })
        assertEquals(setOf(15), apps.map { it.androidUserId }.toSet())
        assertEquals(2, apps.size)
        assertTrue(apps.single { it.system }.label == "Label for com.android.settings")
        assertFalse(apps.any { it.packageName == "com.absent.essential" })
        assertTrue(runCatching {
            ProfilePackageInventory.collect(id, 15, emptySet(), { error("Android denied inventory") }) { it }
        }.isFailure)
    }

    private inner class Backend : ConnectorBackend {
        val manifest = ConnectorManifest.parse("com.acme.plugin", mapOf("contract" to "cyclone.connector/1", "id" to "acme-plugin",
            "label" to "Plugin", "scopes" to "profiles.read profiles.apps.read profiles.config"))
        var granted = manifest.scopes
        var reads = 0
        var fail = false
        override fun approvals() = listOf(ConnectorApproval(manifest.id, manifest.packageName, "cert", granted, manifest.label, 1))
        override fun profiles() = listOf(profile)
        override fun profileApps(profileId: String): List<ProfilePackageInventory.App> {
            reads++
            if (fail) error("private shell detail")
            return inventory(if (profileId == "owner") 0 else 11, profileId)
        }
        override fun setExt(profileId: String, connectorId: String, json: String?) = Unit
        override fun entries(connectorId: String) = emptyList<ConnectorEntry>()
        override fun setEntries(connectorId: String, entries: List<ConnectorEntry>) = Unit
        override fun events(since: Long, now: Long) = JSONObject()
        override fun now() = 1L
    }

    private fun call(b: Backend, method: String, args: JSONObject = JSONObject()): JSONObject = JSONObject(ConnectorCore(b).handle(
        ConnectorCaller(10001, b.manifest.packageName, listOf("cert"), b.manifest),
        JSONObject().put("method", method).put("args", args).toString()))

    @Test fun legacyPackagesAndConfigBindingsUseActualInventoryInsteadOfSetupSelections() {
        val b = Backend()
        val p = call(b, "profiles").getJSONObject("result").getJSONArray("profiles").getJSONObject(1)
        val pkgs = p.getJSONArray("packages").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(3, p.getInt("appCount"))
        assertTrue("com.android.settings" in pkgs)
        assertFalse("com.gone.app" in pkgs)
        val key = JSONObject().put("profileId", id).put("androidUserId", 11).put("packageName", "com.android.settings")
        assertTrue(call(b, "config.get.v1", key).getBoolean("ok"))
        key.put("packageName", "com.gone.app")
        assertEquals("BAD_REQUEST", call(b, "config.get.v1", key).getJSONObject("error").getString("code"))
        key.put("packageName", "com.android.settings").put("androidUserId", 0)
        assertEquals("NO_SUCH_PROFILE", call(b, "config.get.v1", key).getJSONObject("error").getString("code"))
    }

    @Test fun pagesAreCompleteAndKeepExistingScopeAndFailureBoundaries() {
        val b = Backend()
        val args = JSONObject().put("profileId", id).put("limit", 2)
        val first = call(b, "profiles.apps.v1", args).getJSONObject("result")
        assertEquals(2, first.getJSONArray("apps").length())
        assertEquals(2, first.getInt("nextOffset"))
        args.put("offset", first.getInt("nextOffset"))
        val last = call(b, "profiles.apps.v1", args).getJSONObject("result")
        assertEquals(1, last.getJSONArray("apps").length())
        assertTrue(last.isNull("nextOffset"))
        args.put("offset", 0).put("limit", 101)
        assertFalse(call(b, "profiles.apps.v1", args).getBoolean("ok"))
        b.granted = setOf(ConnectorScope.PROFILES_READ)
        b.reads = 0
        assertEquals("SCOPE_NOT_GRANTED", call(b, "profiles.apps.v1", args).getJSONObject("error").getString("code"))
        assertFalse(call(b, "profiles").getJSONObject("result").getJSONArray("profiles").getJSONObject(1).has("packages"))
        assertEquals(0, b.reads)
        b.granted = b.manifest.scopes
        b.fail = true
        val failure = call(b, "profiles.apps.v1", JSONObject().put("profileId", id))
        assertEquals("INTERNAL", failure.getJSONObject("error").getString("code"))
        assertFalse(failure.toString().contains("private shell"))
    }
}
