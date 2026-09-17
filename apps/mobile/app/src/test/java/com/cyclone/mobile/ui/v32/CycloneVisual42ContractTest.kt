package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source-level guards for the consumer visual architecture; rendered screenshots remain a device gate. */
class CycloneVisual42ContractTest {
    @Test fun homeReadsRealReadinessRoutinesAndTaskState() {
        val home = source("CycloneV32App.kt")
        assertTrue(home.contains("CyclonePageHeader("))
        assertTrue(home.contains("title = greeting"))
        assertTrue(home.contains("CyclonePermissionSetup.phoneControlSnapshot(context)"))
        assertTrue(home.contains("AutomationRuntime.store.listAutomations()"))
        assertTrue(home.contains("WorkspaceTasks.state.collectAsState()"))
        assertTrue(home.contains("takeIf { it.phase != TaskPhase.STOPPED }"))
        assertTrue(home.contains("V39AiChatSessionRuntime.pendingRequest = request"))
        assertTrue(home.contains("CycloneAskTaskPanel(current)"))
        assertTrue(home.contains("cyclonePageInsets()"))
        assertTrue(home.contains("CycloneStatusPill(readinessLabel"))
        assertTrue(home.contains("ProfileRescueBar()"))
        assertFalse(home.contains("Your phone, simplified"))
        assertFalse(home.contains("Button(onClick = onAi"))
        assertFalse(home.contains("Settings ·"))
        assertFalse(home.contains("bottom = 96.dp"))
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
        assertTrue(panel.contains("CycloneTaskStatusPill(visualState)"))
        assertTrue(panel.contains("task.canTakeOverFromUi()"))
        assertTrue(panel.contains("task.canContinueAfterHumanFromUi()"))
        assertFalse(panel.contains("BorderStroke"))
        assertFalse(panel.contains("WorkspaceTaskUi("))
    }

    @Test fun bottomNavigationOwnsInsetsAndUsesOneInsetLiquidSelectionLens() {
        val nav = source("CycloneV32Components.kt")
        assertTrue(nav.contains("navigationBarsPadding()"))
        assertTrue(nav.contains("WindowInsets.ime"))
        assertTrue(nav.contains("if (imeVisible && !keepLayoutWhileIme) return"))
        assertTrue(nav.contains("CycloneLiquidTray(height = 62.dp"))
        assertTrue(nav.contains("CycloneLiquidSelectionLens("))
        assertTrue(nav.contains("selectedIndex = selected.ordinal"))
        assertTrue(nav.contains("height = 48.dp"))
        assertTrue(nav.contains("horizontalInset = 4.dp"))
        assertFalse(nav.contains("NavigationBar("))
        assertFalse(nav.contains("Modifier.height(74.dp)"))
        assertTrue(nav.contains("ic_cyclone_ai_42"))
    }

    @Test fun segmentedControlUsesOneCompactTrayAndInsetMovingLens() {
        val controls = source("CycloneV32Components.kt")
        val segmented = controls.substringAfter("fun CycloneSegmentedControl(")
        assertTrue(segmented.contains("CycloneLiquidTray(modifier = modifier.fillMaxWidth(), height = 48.dp"))
        assertTrue(segmented.contains("BoxWithConstraints"))
        assertTrue(segmented.contains("CycloneLiquidSelectionLens("))
        assertTrue(segmented.contains("selectedIndex = selected"))
        assertTrue(segmented.contains("height = 38.dp"))
        assertTrue(segmented.contains("horizontalInset = 4.dp"))
        assertTrue(segmented.contains("animateColorAsState"))
        assertTrue(segmented.contains("selectableGroup()"))
    }

    @Test fun designSystemUsesCycloneIdentityAndAvoidsCardSoupBorders() {
        val design = source("CycloneV32DesignSystem.kt")
        assertTrue(design.contains("Color(0xFF1A73FF)"))
        assertTrue(design.contains("Color(0xFFF5F9FE)"))
        assertTrue(design.contains("Color(0xFF07101F)"))
        assertTrue(design.contains("Color(0xFF0E1A2B)"))
        assertTrue(design.contains("val Page = 20.dp"))
        assertTrue(design.contains("val ScreenBottom = 24.dp"))
        assertTrue(design.contains("fun cyclonePageInsets"))
        assertFalse(design.contains("border = BorderStroke"))
    }

    @Test fun productionSettingsUsesUtilityFirst426Surface() {
        val app = source("CycloneV32App.kt")
        val settings = source("CycloneSettings426.kt")
        assertTrue(app.contains("CycloneSettingsPage426(context, refreshTick,"))
        assertTrue(settings.contains("Settings426Row(\"Model & API\""))
        assertTrue(settings.contains("Settings426Row(\"User notes\""))
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
