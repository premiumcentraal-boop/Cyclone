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
        assertFalse(gate.busy)
    }

    @Test fun oneAcceptedSendBlocksDuplicateUntilCompletion() {
        val gate = V39AiSubmitGate()
        assertEquals("Go to ad.nl", gate.tryAccept("  Go to ad.nl  ", hasKey = true))
        assertTrue(gate.busy)
        assertNull(gate.tryAccept("Second request", hasKey = true))
        gate.complete()
        assertEquals("Second request", gate.tryAccept("Second request", hasKey = true))
    }

    @Test fun missingKeyCannotSubmit() {
        val gate = V39AiSubmitGate()
        assertNull(gate.tryAccept("Go to ad.nl", hasKey = false))
        assertFalse(gate.busy)
    }

    @Test fun modelSelectorUsesCanonicalPresetsAndReloadRestoresStoredId() {
        assertEquals(OpenRouterModelPresets.all, V39AiChatContract.models())
        val chosen = OpenRouterModelPresets.all.last()
        assertEquals(chosen.id, V39AiChatContract.modelForStored(chosen.id).id)
        assertEquals(chosen.label, V39AiChatContract.modelForStored(chosen.id).label)
    }

    @Test fun missingStoredModelUsesCanonicalDefault() {
        assertEquals(OpenRouterModelPresets.DEFAULT.id, V39AiChatContract.modelForStored(null).id)
        assertEquals(OpenRouterModelPresets.DEFAULT.id, V39AiChatContract.modelForStored(" ").id)
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
        assertTrue(app.contains("V32Destination.AI -> V39AiChatPage(context, refreshTick) { settingsOpen = true }"))
        assertFalse(app.contains("V32Destination.AI -> V32AiPage("))
    }

    @Test fun legacyActionsAndSegmentationAreAbsentFromProductionChatPage() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("Do it"))
        assertFalse(page.contains("Make routine"))
        assertFalse(page.contains("Control phone"))
        assertFalse(page.contains("Ask Brain"))
        assertFalse(page.contains("CycloneSegmentedControl"))
    }

    @Test fun composerIsMultilineAndHasOneSendAction() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("minLines = 1"))
        assertTrue(page.contains("maxLines = 5"))
        assertTrue(page.contains("ImeAction.Send"))
        assertEquals(1, Regex("FilledIconButton\\(").findAll(page).count())
    }

    @Test fun oneSendFlowCallsAdaptiveAgentExecuteExactlyOnce() {
        val page = source("CycloneV39AiChatPage.kt")
        assertEquals(1, Regex("agent\\.execute\\(").findAll(page).count())
    }

    @Test fun modelSelectionPersistsOnlyExpectedPreference() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("const val PREFS = \"cyclone_ai\""))
        assertTrue(page.contains("const val MODEL_KEY = \"openrouter_model\""))
        assertTrue(page.contains("prefs.edit().putString(V39AiChatContract.MODEL_KEY, model.id).apply()"))
    }

    @Test fun missingKeyHasClearSettingsAffordance() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("Add API key in Settings"))
        assertTrue(page.contains("hasKey && composer.isNotBlank() && !session.busy"))
    }

    @Test fun composerClearsOnlyAfterAcceptedSubmit() {
        val page = source("CycloneV39AiChatPage.kt")
        val accept = page.indexOf("tryAccept(composer, hasKey) ?: return")
        val clear = page.indexOf("composer = \"\"")
        assertTrue(accept >= 0)
        assertTrue(clear > accept)
    }

    @Test fun statusAndResultAreRenderedInCurrentSession() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("session.status"))
        assertTrue(page.contains("session.messages"))
        assertTrue(page.contains("V39ChatRole.CYCLONE, run.message"))
        assertTrue(page.contains("View run"))
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
