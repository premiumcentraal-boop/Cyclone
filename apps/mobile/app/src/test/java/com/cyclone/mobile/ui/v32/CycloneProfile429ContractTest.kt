package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneProfile429ContractTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    @Test fun profilesDefaultToActiveThenAllThenAppGroups() {
        val source = source("ui/v32/CycloneProfilesPage.kt")
        assertTrue(source.contains("private enum class ProfilesTab { ACTIVE, ALL, GROUPS }"))
        assertTrue(source.contains("mutableStateOf(ProfilesTab.ACTIVE)"))
        assertTrue(source.contains("\"Active (\${activeProfiles.size})\", \"All profiles\", \"Groups\""))
    }

    @Test fun profilesAggregateWorkspacesByAndroidIdentityNotByApp() {
        val source = source("ui/v32/CycloneProfilesPage.kt")
        assertTrue(source.contains("val workspacesByUser = workspaces.groupBy { it.androidUserId }"))
        assertTrue(source.contains("recordByUser[userId]"))
        assertTrue(source.contains("packages = (record?.packages.orEmpty() + spaces.map { it.appPackage }).toSet()"))
    }

    @Test fun activeProfilesShowRealAppTaskStateAndWholeProfileAction() {
        val source = source("ui/v32/CycloneProfilesPage.kt")
        assertTrue(source.contains("CycloneAppIcon(packageName"))
        assertTrue(source.contains("\"\$app · \${profile.label}\""))
        assertTrue(source.contains("Text(\"View progress\")"))
        assertTrue(source.contains("Text(\"Open profile\")"))
        assertTrue(source.contains("ProfileSetupRuntime.openProfile(context, recordId)"))
    }

    @Test fun allProfilesAreIdentityCardsWithAppCountsAndIcons() {
        val source = source("ui/v32/CycloneProfilesPage.kt")
        assertTrue(source.contains("private fun ProfileIdentityCard429"))
        assertTrue(source.contains("profile.packages.take(5).forEach"))
        assertTrue(source.contains("if (profile.packages.size == 1) \"app\" else \"apps\""))
    }

    @Test fun groupsAreAppCentricAndReportProfileCounts() {
        val source = source("ui/v32/CycloneProfilesPage.kt")
        assertTrue(source.contains("private data class AppProfileGroup"))
        assertTrue(source.contains("profile.packages.map { it to profile }"))
        assertTrue(source.contains("AppGroupDetail429"))
        assertTrue(source.contains("if (group.profiles.size == 1) \"profile\" else \"profiles\""))
    }

    @Test fun profileCreationNamesIdentityBeforeSelectingApps() {
        val source = source("ui/ProfileSetup429.kt")
        assertTrue(source.contains("Text(\"Name your profile\""))
        assertTrue(source.contains("label = { Text(\"Profile name\") }"))
        assertTrue(source.contains("placeholder = { Text(\"Creator 1\") }"))
        assertTrue(source.contains("Text(\"Choose apps\")"))
        assertTrue(source.contains("JOURNAL_DISPLAY_LABEL"))
    }

    @Test fun profileManagementUsesIconsAndCompactOpenActionsNotLegacyGreenBars() {
        val source = source("ui/ProfileSetup429.kt")
        assertTrue(source.contains("ProfileAppIcon429"))
        assertTrue(source.contains("getApplicationIcon(packageName)"))
        assertTrue(source.contains("Text(\"Open\")"))
        assertFalse(source.contains("Open Chrome"))
        assertFalse(source.contains("Open AnyDesk"))
        assertFalse(source.contains("Two spaces. One phone."))
    }

    @Test fun setupChoiceListOwnsItsContinueActionInsideScrollableContent() {
        val source = source("ui/ProfileSetup429.kt")
        assertTrue(source.contains("onContinue: () -> Unit"))
        assertTrue(source.contains("Text(\"Continue with \${selected.size}"))
        assertTrue(source.contains("Box(Modifier.weight(1f).fillMaxWidth())"))
    }

    @Test fun friendlyNameIsPersistedSeparatelyFromOpaqueRootProfileId() {
        val source = source("runtime/workspaces/ProfileRegistryStore.kt")
        assertTrue(source.contains("const val JOURNAL_DISPLAY_LABEL = \"display_label\""))
        assertTrue(source.contains("requestedLabel ?: previous?.label ?: fallbackLabel"))
        assertTrue(source.contains("fun rename(context: Context, id: String, label: String)"))
    }

    @Test fun overlayModelControlIncludesCompactModelSelector() {
        val controls = source("ui/v32/CycloneIntelligenceControls.kt")
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertTrue(controls.contains("fun CycloneModelPill"))
        assertTrue(controls.contains("showModelSelector: Boolean = true"))
        assertTrue(controls.contains("OpenRouterModelPresets.all.forEach"))
        assertTrue(overlay.contains("CycloneModelIntelligencePanel("))
    }

    @Test fun inAppComposerDoesNotDuplicateTheModelPillInsideSettings() {
        val source = source("ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(source.contains("CycloneModelPill("))
        assertTrue(source.contains("showModelPill = false"))
    }
}
