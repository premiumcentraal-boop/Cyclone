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

    @Test fun askExpansionKeepsTheOriginalTaskAndReviewRoute() {
        val panel = source("CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("rememberSaveable(task.taskId)"))
        assertTrue(panel.contains("if (task.confirmation != null) expanded = true"))
        assertTrue(panel.contains("CycloneTaskProgress(task)"))
        assertTrue(panel.contains("UiTask(task).open(context)"))
        assertFalse(panel.contains("WorkspaceTaskUi("))
        // 4.2.1 adds Stop/Close on the current-task card. It must route the exact existing
        // task identity into WorkspaceTasks instead of reconstructing or replacing the task.
        assertTrue(panel.contains("WorkspaceTasks.command(context, task, \"cancel\")"))
        assertTrue(source("CyclonePresentation.kt").contains("ViewProgressRouter.intent(context, source)"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
