package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileAppSelectionTest {
    private val essentials = listOf(
        ProfileApp("Cyclone", "com.cyclone.mobile", true),
        ProfileApp("Settings", "com.android.settings", true),
        ProfileApp("Cloak", "dev.cyclone.cloak", true),
        ProfileApp("Magisk", "io.hidden.manager", true),
    )
    private val extra = ProfileApp("Browser", "com.example.browser")

    @Test fun essentialsAreSelectedWithoutAnyOptionalApps() {
        assertEquals(essentials.map { it.packageName }.toSet(), ProfileAppSelection.normalize(essentials, emptySet()))
        assertTrue(ProfileAppSelection.canConfirm(essentials, emptySet()))
        assertFalse(ProfileAppSelection.canConfirm(emptyList(), emptySet()))
    }

    @Test fun essentialsCannotBeUncheckedButOptionalAppsCan() {
        val apps = essentials + extra
        val selected = ProfileAppSelection.normalize(apps, setOf(extra.packageName))
        essentials.forEach { assertEquals(selected, ProfileAppSelection.toggle(apps, selected, it.packageName, false)) }
        assertEquals(essentials.map { it.packageName }.toSet(), ProfileAppSelection.toggle(apps, selected, extra.packageName, false))
    }

    @Test fun restorationKeepsRealChoicesAndDiscardsUninstalledPackages() {
        val selected = ProfileAppSelection.normalize(essentials + extra, setOf(extra.packageName, "com.removed.app"))
        assertTrue(extra.packageName in selected)
        assertFalse("com.removed.app" in selected)
        assertEquals(selected, ProfileAppSelection.normalize(essentials + extra, selected))
    }

    @Test fun essentialsDoNotConsumeTheFiftyOptionalAppLimit() {
        val optional = (1..51).map { ProfileApp("App $it", "com.example.app$it") }
        val fifty = optional.take(50).map { it.packageName }.toSet()
        assertEquals(50, ProfileAppSelection.optionalCount(essentials + optional, fifty))
        assertTrue(ProfileAppSelection.canConfirm(essentials + optional, fifty))
        assertFalse(ProfileAppSelection.canConfirm(essentials + optional, fifty + optional.last().packageName))
    }

    @Test fun installedCloakAndHiddenRootManagerAreMandatoryAndDeduplicated() {
        val items = ProfileCornerstones.resolve("com.cyclone.mobile", setOf("com.android.settings", "io.hidden.manager"),
            "io.hidden.manager", "dev.cyclone.cloak", setOf("dev.cyclone.cloak"))
        assertEquals(4, items.size)
        assertTrue(items.all { it.required })
        assertEquals(ProfileCornerstones.Role.ROOT_MANAGER, items.single { it.packageName == "io.hidden.manager" }.role)
        assertEquals(ProfileCornerstones.Role.CLOAK, items.single { it.packageName == "dev.cyclone.cloak" }.role)
        assertTrue("com.android.settings" in ProfileRequiredPackages.supportAllowlist)
    }
}
