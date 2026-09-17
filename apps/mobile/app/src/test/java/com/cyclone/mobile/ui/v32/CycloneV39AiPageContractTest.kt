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
        assertTrue(text.contains("heightIn(max = 230.dp)"))
    }

    @Test fun emptyStateMatchesDotFieldAskConcept() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(page.contains("\"Ask Cyclone\""))
        assertTrue(page.contains("\"Good morning\""))
        assertTrue(page.contains("\"Good afternoon\""))
        assertTrue(page.contains("\"Good evening\""))
        assertTrue(page.contains("\"What can I do for you?\""))
        assertTrue(page.contains("AskCycloneDotField(Modifier.matchParentSize())"))
        assertFalse(page.contains("CycloneAlpineBackdrop"))
        assertFalse(page.contains("cyclone_alpine"))
        assertFalse(page.contains("progress today"))
        assertFalse(page.contains("Ideas become real"))
        assertFalse(page.contains("Contributor · prompts and responses may be used for training."))
    }

    @Test fun keyboardMovesOnlyComposerAndPreservesAskLayout() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val app = source("com/cyclone/mobile/ui/v32/CycloneV32App.kt")
        assertTrue(page.contains("SOFT_INPUT_ADJUST_NOTHING"))
        assertTrue(page.contains("graphicsLayer { translationY = -composerLiftPx }"))
        assertTrue(page.contains("composerKeyboardGapPx = with(density) { 12.dp.toPx() }"))
        assertTrue(page.contains("onGloballyPositioned"))
        assertTrue(page.contains("contentPadding = PaddingValues(top = 4.dp, bottom = 8.dp)"))
        assertTrue(page.contains("heightIn(max = 230.dp)"))
        assertFalse(page.contains("heightIn(max = if (keyboardOpen)"))
        assertTrue(app.contains("keepLayoutWhileIme = destination == V32Destination.AI"))
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
        assertTrue(nav.contains("if (imeVisible && !keepLayoutWhileIme) return"))
        assertTrue(nav.contains("ic_cyclone_ai_42"))
        assertTrue(nav.contains("CycloneLiquidTray(height = 62.dp"))
        assertTrue(nav.contains("CycloneLiquidSelectionLens("))
        assertTrue(nav.contains("height = 48.dp"))
        assertTrue(nav.contains("horizontalInset = 4.dp"))
        assertFalse(nav.contains("NavigationBarItem("))
        assertFalse(nav.contains("Modifier.height(74.dp)"))
    }
}
