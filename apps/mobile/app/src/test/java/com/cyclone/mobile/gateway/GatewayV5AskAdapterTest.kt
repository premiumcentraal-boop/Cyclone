package com.cyclone.mobile.gateway

import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.TaskConsumerState
import com.cyclone.mobile.runtime.background.TaskPresentationMilestone
import com.cyclone.mobile.runtime.background.TaskPresentationSnapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayV5AskAdapterTest {
    private val submitted = mutableListOf<String>()
    private var overlay = true
    private var busy = false
    private var human = false
    private var snapshot: TaskPresentationSnapshot? = null

    @Before
    fun install() {
        GatewayV5AskAdapter.overlayReady = { overlay }
        GatewayV5AskAdapter.busy = { busy }
        GatewayV5AskAdapter.humanHasControl = { human }
        GatewayV5AskAdapter.submit = { submitted += it }
        GatewayV5AskAdapter.currentTask = { snapshot }
    }

    @After
    fun reset() {
        submitted.clear()
    }

    private fun foreground() = JSONObject().put("sessionId", "default-foreground").put("displayId", 0)

    private fun code(block: () -> Unit): String =
        (runCatching(block).exceptionOrNull() as GatewayProtocolException).code

    @Test
    fun askStartSubmitsTheSentenceUnchangedToTheOverlayRun() {
        val goal = "open Gmail, check my current logged in email, then go to facebook and find the dm of Louella"
        val result = GatewayV5AskAdapter.dispatch("ask.start", foreground().put("goal", goal))
        assertTrue(result.getBoolean("accepted"))
        assertEquals(listOf(goal), submitted)
    }

    @Test
    fun askIsForegroundOnlyAndNeedsASession() {
        assertEquals("SESSION_REQUIRED", code { GatewayV5AskAdapter.start(JSONObject().put("goal", "open clock").put("displayId", 0)) })
        assertEquals(
            "SESSION_DISPLAY_MISMATCH",
            code { GatewayV5AskAdapter.start(JSONObject().put("goal", "open clock").put("sessionId", "vd-mail").put("displayId", 3)) },
        )
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun secretsNeverTravelInAGoal() {
        assertEquals("INVALID_REQUEST", code { GatewayV5AskAdapter.start(foreground().put("goal", "log in with password: hunter2")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5AskAdapter.start(foreground().put("goal", "open clock").put("password", "x")) })
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun busyHumanAndMissingOverlayAreRefusedNotQueuedSilently() {
        busy = true
        assertEquals("ASK_BUSY", code { GatewayV5AskAdapter.start(foreground().put("goal", "open clock")) })
        busy = false
        human = true
        assertEquals("HUMAN_HAS_CONTROL", code { GatewayV5AskAdapter.start(foreground().put("goal", "open clock")) })
        human = false
        overlay = false
        assertEquals("OVERLAY_UNAVAILABLE", code { GatewayV5AskAdapter.start(foreground().put("goal", "open clock")) })
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun statusMirrorsThePhonePresentationSnapshot() {
        assertEquals("idle", GatewayV5AskAdapter.status(foreground()).getString("state"))

        snapshot = TaskPresentationSnapshot(
            taskId = "task-1",
            app = "Facebook",
            packageName = "com.facebook.katana",
            title = "Gmail → Facebook",
            state = TaskConsumerState.NEEDS_SECRET,
            currentMilestone = "Finding the dm of Louella",
            milestones = listOf(
                TaskPresentationMilestone("Finding the signed-in email address", SemanticStepState.DONE),
                TaskPresentationMilestone("Finding the dm of Louella", SemanticStepState.ACTION_NEEDED),
            ),
            completedMilestones = emptyList(),
            completedCount = 1,
            totalCount = 2,
            progressFraction = 0.5f,
            supportingCopy = "Secure input is required to continue.",
            outcomeCopy = null,
            followUps = emptyList(),
        )
        val status = GatewayV5AskAdapter.status(foreground())
        assertEquals("needs-secret", status.getString("state"))
        assertEquals("Gmail → Facebook", status.getString("title"))
        assertEquals("action-needed", status.getJSONArray("milestones").getJSONObject(1).getString("state"))
        // Keys stay out of the PC gateway's secret-name guard.
        assertFalse(status.keys().asSequence().any { it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun askOpsAreAdvertisedAndOnlyStatusIsReadOnly() {
        assertTrue(GatewayProtocol.operations.containsAll(setOf("ask.start", "ask.status")))
        assertTrue("ask.status" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("ask.start" in GatewayProtocol.legacyReadOnlyOperations)
    }
}
