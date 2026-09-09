package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level guards for the consumer visual architecture; rendered screenshots remain a device gate. */
class CycloneVisual42ContractTest {
    @Test fun homeReadsRealReadinessRoutinesAndTaskState() {
        val home = source("CycloneV32App.kt")
        assertTrue(home.contains("Text(greeting"))
        assertTrue(home.contains("CyclonePermissionSetup.phoneControlSnapshot(context)"))
        assertTrue(home.contains("AutomationRuntime.store.listAutomations()"))
        assertTrue(home.contains("WorkspaceTasks.state.collectAsState()"))
        assertTrue(home.contains("takeIf { UiTask(it).active }"))
        assertTrue(home.contains("V39AiChatSessionRuntime.pendingRequest = request"))
        assertTrue(home.contains("CycloneAskTaskPanel(active)"))
        assertFalse(home.contains("Your phone, simplified"))
        assertFalse(home.contains("Button(onClick = onAi"))
    }

    @Test fun homeRoutinesAreObjectsNotRawTextButtons() {
        val home = source("CycloneV32App.kt")
        assertTrue(home.contains("private fun HomeRoutineRow"))
        assertTrue(home.contains("CycloneAppIcon(routine.appPackages.firstOrNull()"))
        assertTrue(home.contains("routine.v32TriggerSummary()"))
        assertTrue(home.contains("Icons.Rounded.ChevronRight"))
    }

    @Test fun taskCardIsOnePhysicalObjectWithExactTaskProjection() {
        val panel = source("CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("TaskGlassPresentation.current(task, resolvedApp)"))
        assertTrue(panel.contains("CycloneAppIcon(presentation.packageName"))
        assertTrue(panel.contains("UiTask(task).open(context)"))
        assertTrue(panel.contains("Orientation.Horizontal"))
        assertTrue(panel.contains("commitLeft"))
        assertTrue(panel.contains("commitRight"))
        assertFalse(panel.contains("BorderStroke"))
        assertFalse(panel.contains("WorkspaceTaskUi("))
    }

    @Test fun bottomNavigationOwnsInsetsAndDisappearsForKeyboard() {
        val nav = source("CycloneV32Components.kt")
        assertTrue(nav.contains("navigationBarsPadding()"))
        assertTrue(nav.contains("WindowInsets.ime"))
        assertTrue(nav.contains("if (imeVisible) return"))
        assertFalse(nav.contains("NavigationBar("))
        assertFalse(nav.contains("Modifier.height(74.dp)"))
        assertTrue(nav.contains("ic_cyclone_ai_42"))
        assertTrue(nav.contains("Modifier.size(48.dp)"))
    }

    @Test fun segmentedControlHasOneSlidingSelectedSurface() {
        val controls = source("CycloneV32Components.kt")
        assertTrue(controls.contains("BoxWithConstraints"))
        assertTrue(controls.contains("animateDpAsState"))
        assertTrue(controls.contains("targetOffset"))
        assertTrue(controls.contains("color = MaterialTheme.colorScheme.primary"))
        assertTrue(controls.contains("animateColorAsState"))
    }

    @Test fun designSystemUsesCycloneIdentityAndAvoidsCardSoupBorders() {
        val design = source("CycloneV32DesignSystem.kt")
        assertTrue(design.contains("Color(0xFF1A73FF)"))
        assertTrue(design.contains("Color(0xFFF5F9FE)"))
        assertTrue(design.contains("Color(0xFF07101F)"))
        assertTrue(design.contains("Color(0xFF0E1A2B)"))
        assertTrue(design.contains("val Page = 20.dp"))
        assertFalse(design.contains("border = BorderStroke"))
    }

    @Test fun productionSettingsUsesUtilityFirst426Surface() {
        val app = source("CycloneV32App.kt")
        val settings = source("CycloneSettings426.kt")
        assertTrue(app.contains("CycloneSettingsPage426(context, refreshTick)"))
        assertTrue(settings.contains("Settings426Row(\"Model & API\""))
        assertTrue(settings.contains("Settings426Row(\"Phone control\""))
        assertTrue(settings.contains("Settings426Row(\"Profile engine\""))
        assertTrue(settings.contains("Settings426Row(\"PC Gateway\""))
        assertTrue(settings.contains("CycloneModelPill"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
