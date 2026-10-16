package com.cyclone.mobile.mind.one

import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.MindListener
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindUsage
import org.json.JSONObject

/**
 * Plan 69 checkpoint 1: today's Mind loop writes what happens into a Score and every model call into the ledger,
 * beside the run and without changing it. The Score and ledger are saved with the mission's metrics, so the
 * switch-over (checkpoint 3) starts from real runs' records. Lines are the tools' own one-line briefs, which the
 * toolbox already scrubs of secrets.
 */
class ScoreRecorder(
    runId: String,
    difficulty: Difficulty,
    goal: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : MindListener {
    @Volatile var score: Score = Score.start(runId, difficulty, goal, clock())
        private set
    val ledger = Ledger()
    private var lastUsage = MindUsage()
    private var model: String = ""
    private var modelStartedMs = 0L

    @Synchronized override fun onModelStart(turn: Int, model: MindModel) {
        this.model = model.id
        modelStartedMs = clock()
    }

    @Synchronized override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) {
        val kind = when {
            !result.ok -> "failed"
            call.name in MIND_TOOLS -> "mind"
            else -> "act"
        }
        score = score.event(kind, "${call.name}: ${result.brief}", clock())
        if (call.name == "steer") score = score.steer(result.brief, clock())
    }

    @Synchronized override fun onNotice(turn: Int, text: String) {
        score = score.event("notice", text.lineSequence().firstOrNull().orEmpty(), clock())
    }

    /** Usage is cumulative in checkpoints: the difference since the last one is this turn's call. */
    @Synchronized override fun checkpoint(checkpoint: MindCheckpoint) {
        val now = checkpoint.usage
        val delta = MindUsage(now.promptTokens - lastUsage.promptTokens, now.completionTokens - lastUsage.completionTokens, now.costUsd - lastUsage.costUsd)
        lastUsage = now
        if (delta.promptTokens > 0 || delta.completionTokens > 0) {
            ledger.add(LedgerEntry(clock(), if (score.difficulty == Difficulty.FLASH) "flash" else "mind", checkpoint.modelId.ifBlank { model },
                inputTokens = delta.promptTokens, outputTokens = delta.completionTokens, costUsd = delta.costUsd,
                ms = (clock() - modelStartedMs).coerceAtLeast(0)))
        }
    }

    @Synchronized fun toJson(): JSONObject = JSONObject()
        .put("score", ScoreCodec.encode(score.copy(events = score.events.takeLast(400))))
        .put("ledger", ledger.summary())

    companion object {
        /** Tools that are the mind's own bookkeeping, not actions on the phone. */
        val MIND_TOOLS = setOf("plan_update", "note", "remember", "forget", "recall", "owner_ask", "task_finish", "task_give_up")
    }
}
