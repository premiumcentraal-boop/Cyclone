package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneAskTaskPanelContractTest {
    private fun source(): String = sequenceOf(
        File("src/main/java/com/cyclone/mobile/ui/v32/CycloneAskTaskPanel.kt"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/v32/CycloneAskTaskPanel.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun workingTaskStartsCompactAndExpandsProgressInline() {
        val text = source()
        assertTrue(text.contains("var progressExpanded by rememberSaveable"))
        assertTrue(text.contains("onToggleExpanded"))
        assertTrue(text.contains("\"View progress\""))
        assertTrue(text.contains("\"Show less\""))
        assertTrue(text.contains("CycloneTaskCheckpoints(task)"))
        assertTrue(text.contains("CycloneNineDotSpinner()"))
        assertTrue(text.contains("\"\$completed of \$total complete\""))
    }

    @Test
    fun taskCardUsesQuietElevationAndGroundedRecoveryActions() {
        val text = source()
        assertTrue(text.contains("shadowElevation = 0.dp"))
        assertTrue(text.contains("WorkspaceTasks.command(context, task, \"handoff\")"))
        assertTrue(text.contains("WorkspaceTasks.command(context, task, \"resume\")"))
        assertFalse(text.contains("Run again"))
    }
}
