package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level guard for the 4.7.2 conversation-system design contract. */
class CycloneConversationSystem472Test {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    @Test
    fun oneConversationPrimitiveServesInAppAndOverlay() {
        val page = source("ui/v32/CycloneV39AiChatPage.kt")
        val overlay = source("ui/overlay/OverlayChrome.kt")
        val bubble = source("ui/v32/CycloneConversationBubble.kt")

        assertTrue(page.contains("CycloneConversationBubble("))
        assertTrue(overlay.contains("CycloneConversationBubble("))
        assertTrue(bubble.contains("enum class CycloneConversationSpeaker { USER, CYCLONE }"))
        assertTrue(bubble.contains("CycloneConversationSpeaker.CYCLONE ->"))
        assertTrue(bubble.contains("CycloneConversationSpeaker.USER ->"))
        assertTrue(bubble.contains(".fillMaxWidth(.80f)"))
    }

    @Test
    fun inAppFirstRetractionRemainsARealComposer() {
        val page = source("ui/v32/CycloneV39AiChatPage.kt")
        val minimized = source("ui/v32/CycloneMinimizedComposerBar.kt")
        assertTrue(page.contains("CycloneMinimizedComposerBar("))
        assertFalse(page.contains("CycloneCollapsedAskPill("))
        assertTrue(minimized.contains("BasicTextField("))
        assertTrue(minimized.contains("Icons.Rounded.Add"))
        assertTrue(minimized.contains("Icons.Rounded.GraphicEq"))
        assertTrue(minimized.contains("Icons.Rounded.ArrowUpward"))
        assertTrue(minimized.contains("Model and intelligence"))
    }

    @Test
    fun taskCardAndDetailsReadFromOnePresentationSnapshot() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        val details = source("runtime/background/WorkspaceProgressActivity.kt")
        val notification = source("runtime/background/TaskNotificationProjection.kt")
        val projection = source("runtime/background/TaskPresentationSnapshot.kt")

        assertTrue(panel.contains("TaskPresentationProjector.project(projectedTask)"))
        assertTrue(details.contains("TaskPresentationProjector.project(task)"))
        assertTrue(notification.contains("TaskPresentationProjector.project(task)"))
        assertTrue(projection.contains("val milestones: List<TaskPresentationMilestone>"))
        assertTrue(projection.contains("object TaskFollowUpPolicy"))
    }

    @Test
    fun brainActiveTaskUsesTheSamePresentationProjection() {
        val brain = source("ui/v32/CycloneV39BrainPage.kt")
        assertTrue(brain.contains("TaskPresentationProjector.project("))
        assertTrue(brain.contains("projected.currentMilestone ?: projected.title"))
        assertFalse(brain.contains("TaskGlassPresentation.current(active"))
    }

    @Test
    fun overlayTaskCardIsNotWrappedInAnotherGlassCard() {
        val overlay = source("ui/overlay/OverlayChrome.kt")
        val taskIndex = overlay.indexOf("task != null -> CycloneAskTaskPanel(task)")
        assertTrue(taskIndex >= 0)
        val nearby = overlay.substring((taskIndex - 1400).coerceAtLeast(0), (taskIndex + 300).coerceAtMost(overlay.length))
        assertFalse(nearby.contains("OverlayAppleGlass("))
    }

    @Test
    fun attachmentHierarchyKeepsPhotosAndFilesDistinct() {
        val composer = source("ui/v32/CycloneHomeComposer.kt")
        val overlayComposer = source("ui/overlay/OverlayAppleLiquidComposer.kt")
        assertTrue(composer.contains("filesLabel: String = \"Files\""))
        assertTrue(composer.contains("\"Photos\""))
        assertFalse(composer.contains("\"Files & photos\""))
        assertTrue(overlayComposer.contains("OverlayAppleToolTile(Icons.Rounded.PhotoLibrary, \"Photos\""))
        assertTrue(overlayComposer.contains("OverlayAppleMenuRow(Icons.Rounded.AttachFile, \"Files\""))
    }
}
