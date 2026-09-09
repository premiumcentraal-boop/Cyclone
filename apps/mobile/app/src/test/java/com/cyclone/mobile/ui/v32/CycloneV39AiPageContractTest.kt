package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneV39AiPageContractTest {
    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate source file $relative from ${File(".").absolutePath}")
    }

    @Test
    fun aiPageHasOneAutoRoutedComposer() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertFalse(text.contains("CycloneSegmentedControl"))
        assertFalse(text.contains("\"Phone task\""))
        assertFalse(text.contains("\"New request\""))
        assertTrue(text.contains("RequestIntentRouter.route(normalized"))
        assertTrue(text.indexOf("RequestIntentRouter.route(normalized") < text.indexOf("WorkspaceTasks.canStartRequest()"))
        assertFalse(text.contains("AccessibilityService"))
        assertFalse(text.contains("MediaProjectionManager"))
    }

    @Test
    fun chatAndPhoneDispatchUseSeparateExistingPaths() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(text.contains("RequestDispatch.CHAT"))
        assertTrue(text.contains("WorkspaceActivity::class.java"))
        assertTrue(text.contains("WorkspaceTasks.queueRequest(normalized)"))
        assertTrue(text.contains("PendingTaskAttachment.take()"))
        assertTrue(text.contains("restoreAttachmentAfterChatFailure"))
    }

    @Test
    fun taskAreaPrecedesBoundedComposerAndReplyStopIsChatOnly() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val current = text.indexOf("CycloneAskTaskPanel(current)")
        val queued = text.indexOf("CyclonePendingRequests()")
        val composer = text.lastIndexOf("BasicTextField(")
        assertTrue(current in 0 until composer)
        assertTrue(queued in 0 until composer)
        assertTrue(text.contains("heightIn(max = if (keyboardOpen) 132.dp else 230.dp)"))
        val stopReply = text.indexOf("Stop reply")
        assertTrue(stopReply >= 0)
        val stopWindow = text.substring((stopReply - 500).coerceAtLeast(0), (stopReply + 500).coerceAtMost(text.length))
        assertTrue(stopWindow.contains("chatJob?.cancel()"))
        assertFalse(stopWindow.contains("WorkspaceTasks.command"))
    }

    @Test
    fun lightSurfaceAndAiNavigationAvoidDoubleIndicators() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val nav = source("com/cyclone/mobile/ui/v32/CycloneV32Components.kt")
        assertTrue(page.contains("shadowElevation = 0.dp"))
        assertTrue(page.contains("outlineVariant.copy"))
        assertTrue(nav.contains("indicatorColor = if (isAi) Color.Transparent"))
        assertTrue(nav.contains(".size(44.dp)"))
        assertTrue(nav.contains("if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant"))
    }
}
