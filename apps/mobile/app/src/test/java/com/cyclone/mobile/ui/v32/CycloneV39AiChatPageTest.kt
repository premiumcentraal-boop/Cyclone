package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.QuickAgentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CycloneV39AiChatPageTest {
    @Test fun emptyRequestCannotSubmit() {
        val gate = V39AiSubmitGate()
        assertNull(gate.tryAccept("  \n ", hasKey = true))
    }

    @Test fun oneAcceptedSendBlocksDuplicateUntilCompletion() {
        val gate = V39AiSubmitGate()
        assertEquals("Go to ad.nl", gate.tryAccept("  Go to ad.nl  ", hasKey = true))
        assertNull(gate.tryAccept("Second request", hasKey = true))
        gate.complete()
        assertEquals("Second request", gate.tryAccept("Second request", hasKey = true))
    }

    @Test fun missingKeyCannotSubmitChat() {
        val gate = V39AiSubmitGate()
        assertNull(gate.tryAccept("Explain Android", hasKey = false))
    }

    @Test fun legacyModelReloadRestoresStoredId() {
        val chosen = OpenRouterModelPresets.all.last()
        assertEquals(chosen.id, V39AiChatContract.modelForStored(chosen.id).id)
        assertEquals(chosen.label, V39AiChatContract.modelForStored(chosen.id).label)
    }

    @Test fun emptySelectionNeverRestoresAnUnselectedDefault() {
        assertEquals("", V39AiChatContract.modelForStored(null).id)
        assertEquals("", V39AiChatContract.modelForStored(" ").id)
    }

    @Test fun existingSafetyProfileFeedsQuickAgentConfig() {
        CycloneAiAccessProfile.entries.forEach { profile ->
            val config = V39AiChatContract.config(OpenRouterModelPresets.DEFAULT.id, profile)
            assertEquals(profile, config.accessProfile)
            assertEquals(profile != CycloneAiAccessProfile.FULL, config.safeMode)
        }
    }

    @Test fun resultStatusIsVisibleContract() {
        assertEquals("Completed and checked", V39AiChatContract.finalStatus(QuickAgentResult(true, "done", 1, "model")))
        assertEquals("Stopped safely", V39AiChatContract.finalStatus(QuickAgentResult(false, "failed", 1, "model")))
    }

    @Test fun productionAiDestinationRoutesToV39Page() {
        val app = source("CycloneV32App.kt")
        assertTrue(app.contains("V32Destination.AI -> V39AiChatPage(context, refreshTick)"))
        assertFalse(app.contains("V32Destination.AI -> V32AiPage("))
    }

    @Test fun productionPageHasOneAutoRoutedComposerAndNoModeToggle() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("listOf(\"Chat\", \"Phone task\")"))
        assertFalse(page.contains("var phoneTask"))
        assertFalse(page.contains("Text(\"New request\""))
        assertTrue(page.contains("RequestIntentRouter.route(normalized"))
        assertTrue(page.indexOf("RequestIntentRouter.route(normalized") < page.indexOf("WorkspaceTasks.canStartRequest()"))
        assertTrue(page.contains("RequestDispatch.CHAT"))
        assertTrue(page.contains("RequestDispatch.START_PHONE_TASK"))
        assertTrue(page.contains("RequestDispatch.QUEUE_PHONE_TASK"))
    }

    @Test fun composerIsMultilineAndHasOneSendAction() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("BasicTextField("))
        assertTrue(page.contains("contentDescription = \"Ask Cyclone composer\""))
        assertTrue(page.contains("maxLines = 4"))
        assertTrue(page.contains("ImeAction.Send"))
        assertEquals(1, Regex("FilledIconButton\\(").findAll(page).count())
    }

    @Test fun modelPillCannotStealComposerWidthAndKeyboardDoesNotDoubleInset() {
        val page = source("CycloneV39AiChatPage.kt")
        val pill = page.indexOf("CycloneModelPill(")
        val askGlass = page.indexOf(".clip(RoundedCornerShape(32.dp))", pill)
        val composer = page.indexOf("BasicTextField(", askGlass)
        assertTrue(pill >= 0)
        assertTrue(askGlass > pill)
        assertTrue(composer > askGlass)
        assertTrue(page.contains("showModelPill = false"))
        assertTrue(page.contains("if (!keyboardOpen)"))
        assertFalse(page.contains(".imePadding()"))
        assertFalse(page.contains("CycloneIntelligenceControls(enabled = !session.busy, onChanged"))
    }

    @Test fun chatAndPhoneDispatchUseSeparateExistingPaths() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("agent.execute("))
        assertTrue(page.contains("CycloneTextChat.answer(context"))
        assertTrue(page.contains("WorkspaceTasks.queueRequest(normalized)"))
        assertTrue(page.contains("OverlayChromeRuntime.submitRequest(normalized)"))
        assertFalse(page.contains("AccessibilityService"))
        assertFalse(page.contains("MediaProjectionManager"))
    }

    @Test fun modelAndReasoningPreferencesStayCanonical() {
        val page = source("CycloneV39AiChatPage.kt")
        val controls = source("CycloneIntelligenceControls.kt")
        val reasoning = source("CycloneReasoningSelector.kt")
        assertTrue(page.contains("const val PREFS = \"cyclone_ai\""))
        assertTrue(page.contains("const val MODEL_KEY = \"openrouter_model\""))
        assertTrue(controls.contains("OpenRouterCatalogStore.setActive(context"))
        assertTrue(reasoning.contains("OpenRouterCatalogStore.setReasoningEffort(context, canonical, value)"))
        assertTrue(reasoning.contains("reasoningSelectorMode(options)"))
        assertTrue(page.contains("showModelPill = false"))
    }

    @Test fun missingKeyBlocksChatButNotPhoneRoutingContract() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("OpenRouter key required for chat"))
        assertTrue(page.contains("RequestIntent.PHONE_TASK -> true"))
        assertTrue(page.contains("RequestIntent.CHAT -> hasKey && !session.busy"))
    }

    @Test fun attachmentsAreTakenOnlyInsideChatAndRestoredOnFailure() {
        val page = source("CycloneV39AiChatPage.kt")
        val chat = page.indexOf("RequestDispatch.CHAT ->")
        val take = page.indexOf("PendingTaskAttachment.take()", chat)
        assertTrue(chat >= 0)
        assertTrue(take > chat)
        assertTrue(page.contains("restoreAttachmentAfterChatFailure(attachment)"))
        assertTrue(page.contains("WorkspaceTasks.queueRequest(normalized)"))
    }

    @Test fun stopReplyCancelsOnlyProviderResponse() {
        val page = source("CycloneV39AiChatPage.kt")
        val stop = page.indexOf("Stop reply")
        assertTrue(stop >= 0)
        val window = page.substring((stop - 300).coerceAtLeast(0), (stop + 300).coerceAtMost(page.length))
        assertTrue(window.contains("chatJob?.cancel()"))
        assertFalse(window.contains("WorkspaceTasks.command"))
    }

    @Test fun taskAndForegroundWorkAreaIsBoundedAboveComposer() {
        val page = source("CycloneV39AiChatPage.kt")
        val task = page.indexOf("CycloneAskTaskPanel(current)")
        val foreground = page.indexOf("CycloneForegroundWorkCard(foregroundSnapshot)")
        val queued = page.indexOf("CyclonePendingRequests()")
        val composer = page.lastIndexOf("BasicTextField(")
        assertTrue(task in 0 until composer)
        assertTrue(foreground in 0 until composer)
        assertTrue(queued in 0 until composer)
        assertTrue(page.contains("heightIn(max = if (keyboardOpen) 132.dp else 230.dp)"))
    }

    @Test fun translucentChatUsesClippedBackgroundsWithoutElevatedGhostBands() {
        val page = source("CycloneV39AiChatPage.kt")
        val design = source("CycloneV32DesignSystem.kt")
        assertTrue(page.contains(".background(MaterialTheme.colorScheme.surface.copy(alpha = .56f))"))
        assertTrue(page.contains(".background(color)"))
        assertFalse(page.contains("shadowElevation = 5.dp"))
        assertFalse(design.substringAfter("fun CycloneGlassSurface").contains("shadowElevation = 4.dp"))
    }

    @Test fun alpineEmptyStateUsesPreferredProgressComposition() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("CycloneAlpineBackdrop"))
        assertTrue(page.contains("\"Let’s make\\nprogress today.\""))
        assertTrue(page.contains("\"Ideas become real when you take the next step.\""))
        assertFalse(page.contains("Contributor · prompts and responses may be used for training."))
    }

    @Test fun routineBuilderHasNoCallSurfaceFromChatPage() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("buildWorkflow"))
        assertFalse(page.contains("V32RoutineBuilder"))
        assertFalse(page.contains("AutomationRuntime"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        val candidates = sequenceOf(
            File(relative),
            File("apps/mobile/app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "apps/mobile/app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not locate $relative from ${System.getProperty("user.dir")}", file)
        return file!!.readText()
    }
}
