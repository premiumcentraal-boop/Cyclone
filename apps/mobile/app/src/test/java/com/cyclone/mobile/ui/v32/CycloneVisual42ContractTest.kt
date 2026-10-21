package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Consumer redesign guards retain real state and exact task routing. */
class CycloneVisual42ContractTest {
    @Test fun homeReadsReadinessRoutinesAndTheSharedTaskStore() {
        val home = source("CycloneV32App.kt")
        assertTrue(home.contains("Text(greeting"))
        assertTrue(home.contains("CyclonePermissionSetup.phoneControlSnapshot(context)"))
        assertTrue(home.contains("if (ready.ready) \"Ready\""))
        assertTrue(home.contains("AutomationRuntime.store.listAutomations()"))
        assertTrue(home.contains("WorkspaceTasks.state.collectAsState()"))
        assertTrue(home.contains("takeIf { UiTask(it).active }"))
        assertTrue(home.contains("V39AiChatSessionRuntime.pendingRequest = request; onAi()"))
        assertFalse(home.contains("Your phone, simplified"))
    }

    @Test fun askTaskGlassKeepsExactTaskAndMovesDestructiveControlsIntoProgress() {
        val panel = source("CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("TaskGlassPresentation.current(task, resolvedApp)"))
        assertTrue(panel.contains("CycloneAppIcon(presentation.packageName"))
        assertTrue(panel.contains("UiTask(task).open(context)"))
        assertTrue(panel.contains("presentation.actionContentDescription"))
        assertFalse(panel.contains("WorkspaceTaskUi("))
        assertFalse(panel.contains("WorkspaceTasks.command(context, task, \"cancel\")"))
        assertFalse(panel.contains("Stop task"))
        assertFalse(panel.contains("Close task"))
        assertTrue(source("CyclonePresentation.kt").contains("ViewProgressRouter.intent(context, source)"))
        assertTrue(source("CyclonePresentation.kt").contains("WorkspaceTasks.command(context, task, \"cancel\")"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
