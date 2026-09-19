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

    @Test fun aiPageHasOneAutoRoutedComposer() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertFalse(text.contains("listOf(\"Chat\", \"Phone task\")"))
        assertFalse(text.contains("\"New request\""))
        assertTrue(text.contains("RequestIntentRouter.route(normalized"))
        assertTrue(text.indexOf("RequestIntentRouter.route(normalized") < text.indexOf("WorkspaceTasks.canStartRequest()"))
        assertFalse(text.contains("AccessibilityService"))
        assertFalse(text.contains("MediaProjectionManager"))
    }

    @Test fun chatAndPhoneDispatchUseSeparateExistingPaths() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(text.contains("RequestDispatch.CHAT"))
        assertTrue(text.contains("OverlayChromeRuntime.submitRequest(normalized)"))
        assertTrue(text.contains("WorkspaceTasks.queueRequest(normalized)"))
        assertTrue(text.contains("PendingTaskAttachment.take()"))
        assertTrue(text.contains("restoreAttachmentAfterChatFailure"))
    }

    @Test fun taskAreaAndModelPillStayAboveLiquidComposer() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val current = text.indexOf("CycloneAskTaskPanel(current)")
        val queued = text.indexOf("CyclonePendingRequests()")
        val modelPill = text.indexOf("CycloneModelPill(")
        val liquidComposer = text.indexOf("CycloneLiquidPanel(", modelPill)
        val composer = text.lastIndexOf("BasicTextField(")
        assertTrue(current in 0 until composer)
        assertTrue(queued in 0 until composer)
        assertTrue(modelPill in 0 until liquidComposer)
        assertTrue(liquidComposer in 0 until composer)
        assertTrue(text.contains("CycloneModelIntelligencePanel("))
        assertTrue(text.contains("showModelSelector = false"))
        assertTrue(text.contains("heightIn(max = if (keyboardOpen) 132.dp else 230.dp)"))
    }

    @Test fun emptyStateIsQuietAndComposerDrawerOwnsProgressiveDisclosure() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val drawer = source("com/cyclone/mobile/ui/v32/CycloneChatDrawer.kt")
        assertTrue(page.contains("\"Ready when you are\""))
        assertTrue(page.contains("\"Tell Cyclone what to do on your phone.\""))
        assertTrue(page.contains("CycloneAlpineBackdrop"))
        assertTrue(page.contains("CycloneChatDrawerSurface("))
        assertTrue(page.contains("CycloneCollapsedAskPill("))
        assertTrue(page.contains("CycloneChatToolsPanel("))
        assertTrue(drawer.contains("Drag down or tap to minimize Cyclone chat"))
        assertTrue(page.contains("if (intelligenceOpen && !keyboardOpen)"))
        assertFalse(page.contains("Icons.Rounded.Tune"))
        assertFalse(page.contains("\"Ideas become real when you take the next step.\""))
    }

    @Test fun replyStopIsChatOnly() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val stopReply = text.indexOf("Stop reply")
        assertTrue(stopReply >= 0)
        val stopWindow = text.substring((stopReply - 500).coerceAtLeast(0), (stopReply + 500).coerceAtMost(text.length))
        assertTrue(stopWindow.contains("chatJob?.cancel()"))
        assertFalse(stopWindow.contains("WorkspaceTasks.command"))
    }

    @Test fun navigationIsLiquidInsetSafeAndUsesCycloneAiAsset() {
        val nav = source("com/cyclone/mobile/ui/v32/CycloneV32Components.kt")
        assertTrue(nav.contains("navigationBarsPadding()"))
        assertTrue(nav.contains("WindowInsets.ime"))
        assertTrue(nav.contains("if (imeVisible) return"))
        assertTrue(nav.contains("ic_cyclone_ai_42"))
        assertTrue(nav.contains("CycloneLiquidTray(height = 62.dp"))
        assertTrue(nav.contains("CycloneLiquidSelectionLens("))
        assertTrue(nav.contains("height = 48.dp"))
        assertTrue(nav.contains("horizontalInset = 4.dp"))
        assertFalse(nav.contains("NavigationBarItem("))
        assertFalse(nav.contains("Modifier.height(74.dp)"))
    }
}
