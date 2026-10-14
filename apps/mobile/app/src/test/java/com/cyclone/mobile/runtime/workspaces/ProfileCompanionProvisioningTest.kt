package com.cyclone.mobile.runtime.workspaces

import com.cyclone.mobile.connector.ConnectorManifest
import com.cyclone.mobile.connector.DiscoveredConnector
import org.junit.Assert.*
import org.junit.Test

class ProfileCompanionProvisioningTest {
    private fun connector(pkg: String = "dev.cyclone.cloak", id: String = "cyclone-cloak") = DiscoveredConnector(
        pkg, "Cloak", ConnectorManifest(pkg, id, "Cloak", 3, emptySet(), emptyList(), ".MainActivity", ".CycloneWake"),
        null, emptyList(), null, false, false)

    @Test fun installedCompanionUsesConnectorIdentityNotApprovalOrGuessedPackage() {
        assertEquals("dev.cyclone.cloak", ProfileCornerstones.cloakPackage(listOf(connector())))
        assertEquals("org.example.companion", ProfileCornerstones.cloakPackage(listOf(connector("org.example.companion"))))
    }
    @Test fun absentInvalidOrConflictingCompanionIsNotProvisioned() {
        assertNull(ProfileCornerstones.cloakPackage(emptyList()))
        assertNull(ProfileCornerstones.cloakPackage(listOf(connector(id = "other-tool"))))
        assertNull(ProfileCornerstones.cloakPackage(listOf(connector().copy(manifest = null))))
        assertNull(ProfileCornerstones.cloakPackage(listOf(connector().copy(idConflict = true))))
        assertNull(ProfileCornerstones.cloakPackage(listOf(connector().copy(problem = "Invalid manifest"))))
        assertNull(ProfileCornerstones.cloakPackage(listOf(connector(), connector("org.example.companion"))))
    }
    @Test fun companionIsRequiredAndDeduplicatedWithoutChangingOtherSelectedApps() {
        val packageName = ProfileCornerstones.cloakPackage(listOf(connector()))
        val items = ProfileCornerstones.resolve("com.cyclone.mobile", setOf("com.android.settings"), null,
            packageName, setOf("dev.cyclone.cloak", "com.android.chrome"))
        val companion = items.single { it.packageName == packageName }
        assertEquals(ProfileCornerstones.Role.CLOAK, companion.role)
        assertTrue(companion.required)
        assertFalse(items.single { it.packageName == "com.android.chrome" }.required)
    }
    @Test fun perUserInstallCommandUsesAssignedUserWithoutCopyingData() {
        assertEquals("/system/bin/cmd package install-existing --user 16 dev.cyclone.cloak",
            ProfileSetupPlan.shell(ProfileSetupPlan.installExisting(16, "dev.cyclone.cloak")))
    }
}
