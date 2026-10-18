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
        GatewayV5AskAdapter.stopTask = { stopped += it; true }
        GatewayV5AskAdapter.stopInstant = { stopped += "instant" }
        com.cyclone.mobile.mind.modes.AskLedger.clear()
    }

    private val stopped = mutableListOf<String>()

    @Test
    fun eachRequestHasAnIdItsLaneAndAStopThatGoesThroughTaskKit() {
        val ledger = com.cyclone.mobile.mind.modes.AskLedger
        val id = GatewayV5AskAdapter.start(JSONObject().put("goal", "make it louder")).getString("requestId")
        assertTrue(id.startsWith("req-"))
        assertEquals("waiting", GatewayV5AskAdapter.status(JSONObject().put("requestId", id)).getJSONObject("request").getString("state"))
        ledger.routed("make it louder", "instant", "phone", "the phone model knows this one", 3)
        val running = GatewayV5AskAdapter.status(JSONObject().put("requestId", id))
        assertEquals("instant", running.getJSONObject("request").getString("lane"))
        assertEquals("phone", running.getJSONObject("request").getString("decider"))
        // An Instant request never shows the task before it as its own.
        snapshot = null
        assertEquals("idle", running.getString("state"))
        val cancel = GatewayV5AskAdapter.cancel(JSONObject().put("requestId", id))
        assertTrue(cancel.getBoolean("cancelled"))
        assertEquals(listOf("instant"), stopped)
        assertEquals("cancelled", cancel.getJSONObject("request").getString("state"))
        assertFalse(GatewayV5AskAdapter.cancel(JSONObject().put("requestId", id)).getBoolean("cancelled"))
        ledger.finished("thanks", "ignore", true, null)
        assertEquals("INVALID_REQUEST", code { GatewayV5AskAdapter.status(JSONObject().put("requestId", "req-0000000000000")) })
        assertEquals("INVALID_REQUEST", code { GatewayV5AskAdapter.status(JSONObject().put("requestId", "../x")) })
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
    fun askIsForegroundOnlyAndDefaultsToTheMainScreen() {
        // Alpha 91: the main screen is the default; only another screen is refused.
        assertTrue(GatewayV5AskAdapter.start(JSONObject().put("goal", "open clock")).getBoolean("accepted"))
        submitted.clear()
        assertEquals("SESSION_REQUIRED", code { GatewayV5AskAdapter.start(JSONObject().put("goal", "open clock").put("sessionId", "").put("displayId", 0)) })
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

    private fun task(id: String, state: TaskConsumerState) = TaskPresentationSnapshot(
        taskId = id, app = "Instagram", packageName = "com.instagram.android", title = "Open my profile", state = state,
        currentMilestone = null, milestones = emptyList(), completedMilestones = emptyList(), completedCount = 0, totalCount = 0,
        progressFraction = 0f, supportingCopy = null, outcomeCopy = null, followUps = emptyList(),
    )

    @Test
    fun aNewRequestNeverShowsTheTaskBeforeItAndEndsWithItsOwnMission() {
        // Field test 143 (B5): the first poll of a new request read the last task's "failed".
        val ledger = com.cyclone.mobile.mind.modes.AskLedger
        snapshot = task("mission-old", TaskConsumerState.FAILED)
        val id = GatewayV5AskAdapter.start(JSONObject().put("goal", "open my profile")).getString("requestId")
        val first = GatewayV5AskAdapter.status(JSONObject().put("requestId", id))
        assertEquals("idle", first.getString("state"))
        assertEquals("waiting", first.getJSONObject("request").getString("state"))
        // B3: handed up to Flash, it says why; B4: it ends with its own mission, not "running" for good.
        ledger.routed("open my profile", "instant", "grammar", "a clear command", 0)
        ledger.handed("open my profile", "flash", "the tap on \"Profile\" changed nothing on the screen", taskId = "mission-new")
        snapshot = task("mission-new", TaskConsumerState.WORKING)
        val running = GatewayV5AskAdapter.status(JSONObject().put("requestId", id))
        assertEquals("flash", running.getJSONObject("request").getString("lane"))
        assertTrue(running.getJSONObject("request").getString("why").startsWith("handed up to flash: the tap"))
        assertEquals("running", running.getJSONObject("request").getString("state"))
        // Another mission's snapshot is not this request's.
        snapshot = task("mission-other", TaskConsumerState.FAILED)
        assertEquals("idle", GatewayV5AskAdapter.status(JSONObject().put("requestId", id)).getString("state"))
        ledger.missionEnded("mission-new", ok = true, say = "Opened your profile.")
        val done = GatewayV5AskAdapter.status(JSONObject().put("requestId", id)).getJSONObject("request")
        assertEquals("done", done.getString("state"))
        assertEquals("Opened your profile.", done.getString("say"))
        // Stopping goes to the request's own mission, through Task Kit.
        val again = GatewayV5AskAdapter.start(JSONObject().put("goal", "open reels")).getString("requestId")
        ledger.routed("open reels", "flash", "rules", "more than one step", 0)
        ledger.handed("open reels", "flash", taskId = "mission-reels")
        assertTrue(GatewayV5AskAdapter.cancel(JSONObject().put("requestId", again)).getBoolean("cancelled"))
        assertEquals(listOf("mission-reels"), stopped)
    }

    @Test
    fun aBusyPhoneSaysWhatIsInTheWay() {
        // Field test 143 (B6): five requests came back as a bare "rejected".
        busy = true
        snapshot = task("mission-1", TaskConsumerState.WORKING)
        val refused = runCatching { GatewayV5AskAdapter.start(JSONObject().put("goal", "open clock")) }.exceptionOrNull() as GatewayProtocolException
        assertEquals("ASK_BUSY", refused.code)
        assertTrue(refused.message!!, refused.message!!.contains("Open my profile") && refused.message!!.contains("Try again"))
    }

    @Test
    fun askOpsAreAdvertisedAndOnlyStatusIsReadOnly() {
        assertTrue(GatewayProtocol.operations.containsAll(setOf("ask.start", "ask.status")))
        assertTrue("ask.status" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("ask.start" in GatewayProtocol.legacyReadOnlyOperations)
    }
}
