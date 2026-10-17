package com.cyclone.mobile.mind.one

import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.mind.MindBudget
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindListener
import com.cyclone.mobile.mind.MindLoop
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindModelRequest
import com.cyclone.mobile.mind.MindStatus
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.MindToolSpec
import com.cyclone.mobile.mind.MindToolbox
import com.cyclone.mobile.mind.OpenRouterMindModel
import com.cyclone.mobile.mind.PhoneMindToolbox
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 69 checkpoint 2: the minds' phone side, ready for the switch-over (checkpoint 3) to plug into a mission.
 *
 * - [brain]: the owner's selected model, at low effort for glances and consults and at the owner's effort otherwise.
 * - [luna]: the one GPT-6 Luna call compaction and the handover use (any failure falls back to code).
 * - [owner]: questions and the check-in card through the mission's owner port (the usual card).
 * - [wheel]: today's [MindLoop], driving inside OneMind's conversation until the mind hands back with plan_write.
 * - [crew]: the step engine on the Mind's own toolbox (gates unchanged).
 */
object AndroidMinds {
    const val LUNA = "openai/gpt-6-luna"
    const val LUNA_MS = 30_000L
    const val OWNER_WAIT_MS = 30 * 60_000L

    fun brain(key: String, modelId: String, label: String, vision: Boolean, ownerEffort: String?, traceId: String,
              cancellation: ProviderCancellation, cancelled: () -> Boolean): (Effort) -> MindModel {
        val low = OpenRouterMindModel(key, modelId, label, vision, "low", traceId, cancellation, cancelled)
        val full = OpenRouterMindModel(key, modelId, label, vision, ownerEffort, traceId, cancellation, cancelled)
        return { effort -> if (effort == Effort.LOW) low else full }
    }

    fun luna(key: String, traceId: String, cancellation: ProviderCancellation, cancelled: () -> Boolean, ledger: Ledger,
             clock: () -> Long = System::currentTimeMillis): StoryWriter {
        val model = OpenRouterMindModel(key, LUNA, "GPT-6 Luna", false, null, traceId, cancellation, cancelled)
        return StoryWriter { instructions, material ->
            val started = clock()
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", instructions))
                .put(JSONObject().put("role", "user").put("content", material))
            val reply = runCatching { model.complete(MindModelRequest(messages, emptyList(), true, LUNA_MS)) }.getOrNull()
            ledger.add(LedgerEntry(clock(), "luna", LUNA, reply?.usage?.promptTokens ?: 0, 0, reply?.usage?.completionTokens ?: 0,
                reply?.usage?.costUsd ?: 0.0, clock() - started, reply != null))
            reply?.text?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /** The owner through the mission's own owner port: questions and the check-in use the same card as every other ask. */
    fun owner(port: com.cyclone.mobile.mind.MindOwnerPort, cancelled: () -> Boolean, steers: () -> List<String>,
              messages: () -> List<String>, paused: () -> Boolean): OwnerLine = object : OwnerLine {
        override fun cancelled(): Boolean = cancelled()
        override fun steers(): List<String> = steers()
        override fun messages(): List<String> = messages()
        override fun paused(): Boolean = paused()

        override fun ask(question: String, choices: List<String>): String? =
            port.ask(question, choices, OWNER_WAIT_MS).takeIf { it.answered }?.text?.takeIf { it.isNotBlank() }

        override fun checkIn(reason: String): CheckIn {
            val reply = port.ask(OneMindPrompt.checkIn(reason), listOf(OneMindPrompt.KEEP_GOING, OneMindPrompt.STOP), OWNER_WAIT_MS)
            if (!reply.answered) return CheckIn.Stop
            return when (reply.text.trim()) {
                OneMindPrompt.KEEP_GOING -> CheckIn.KeepGoing
                OneMindPrompt.STOP, "" -> CheckIn.Stop
                else -> CheckIn.Steer(reply.text.trim())
            }
        }
    }

    /** The tool the mind uses at the wheel to hand back to the crew. */
    val PLAN_WRITE = MindToolSpec("plan_write",
        "Hand the rest of the task back to the crew: the plan for what remains, one JSON object per line (milestone, step, " +
            "order and done lines, as in your instructions). Use it once the rest is routine.",
        MindToolSpec.objectSchema("lines" to MindToolSpec.string("The plan lines, one JSON object per line."), required = listOf("lines")))

    fun wheel(primary: MindModel, backup: MindModel?, toolbox: MindToolbox, compactAt: CompactAt, listener: MindListener,
              cancelled: () -> Boolean, paused: () -> Boolean): Wheel = object : Wheel {
        override fun specs(): List<MindToolSpec> = toolbox.specs() + PLAN_WRITE

        override fun drive(conversation: MindConversation, why: String): WheelEnd {
            var lines: String? = null
            val hands = object : MindToolbox by toolbox {
                override fun specs(): List<MindToolSpec> = toolbox.specs() + PLAN_WRITE
                override fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult {
                    if (call.name != PLAN_WRITE.name) return toolbox.execute(call, arguments)
                    val text = arguments.optString("lines")
                    if (PlanLines.read(text, "The rest").plan() == null) return MindToolResult.error("No step lines in that plan; write at least one step line.")
                    lines = text
                    return MindToolResult("The crew takes over with this plan.")
                }
            }
            val chars = (compactAt.tokens ?: OFF_TOKENS) * Compactor.CHARS_PER_TOKEN
            val budget = MindBudget(maxContextChars = chars, workspaceChars = chars)
            val outcome = MindLoop(primary, backup, hands, budget, listener, cancelled = cancelled, paused = paused,
                handBack = { lines != null }).run(conversation)
            return when (outcome.status) {
                MindStatus.HANDED_BACK -> WheelEnd.HandedBack(lines.orEmpty())
                MindStatus.COMPLETED -> WheelEnd.Finished(outcome.summary, outcome.evidence)
                MindStatus.GAVE_UP -> WheelEnd.GaveUp(outcome.summary)
                MindStatus.CANCELLED -> WheelEnd.Cancelled
                MindStatus.FAILED, MindStatus.OUT_OF_BUDGET -> WheelEnd.Failed(outcome.summary)
            }
        }
    }

    /** "Off" compacts only near the model's own limit. */
    const val OFF_TOKENS = 400_000

    fun crew(toolbox: PhoneMindToolbox, key: String, ledger: Ledger, zdr: Boolean, breaker: Breaker, record: (String) -> Unit): Crew =
        EngineCrew(AndroidCrew.eyes(toolbox), AndroidCrew.board(key, ledger, zdr), AndroidCrew.doneBoard(key, ledger, zdr), toolbox, breaker,
            shadow = AndroidCrew.visionShadow(key, ledger, record))
}
