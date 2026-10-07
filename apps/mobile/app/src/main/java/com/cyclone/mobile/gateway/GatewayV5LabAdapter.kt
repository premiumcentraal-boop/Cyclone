package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.BuildConfig
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.mind.lab.MindLabVariant
import com.cyclone.mobile.mind.lab.MissionLab
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.MindRedaction
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.owner.OwnerMoment
import com.cyclone.mobile.owner.OwnerMomentsRuntime
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommandResult
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cyclone Lab on the phone (`lab.start` / `lab.status` / `lab.answer` / `lab.record`). The PC lab starts a Mind mission
 * with an experiment variant, watches it, plays the owner from a mission script, and reads back what happened.
 *
 * The lab is the owner at the PC, not a second executor: missions run through the same Mind, PhoneToolExecutor, GATE
 * and Secrets Card. Its owner answers go through Task Kit like any button. It can answer a question, fill values,
 * decline or stop, and it never supplies a secret. It approves nothing a person would care about: since alpha 108 it may
 * approve exactly two test-owned actions, checked here on the phone whatever the PC asks (deleting the lab's own file
 * [LAB_FILE], sending to the reserved lab address [LAB_RECIPIENT]); everything else stays a person's decision.
 */
internal object GatewayV5LabAdapter {
    const val MAX_GOAL = 2_000
    private val MISSION_ID = Regex("^m[a-z0-9]{6,40}$")
    private val INLINE_SECRET = Regex("(?i)(password|passcode|passwd|pin|otp|token|secret|api[_-]?key|authorization|cookie|cvv|credential)\\s*[:=]")
    val ANSWERS = setOf("reply", "fill", "decline", "stop", "steer", "approve")
    const val LAB_FILE = "cyclone-lab-note.txt"
    const val LAB_RECIPIENT = "cyclone-lab@example.com"
    const val NOT_LAB_RECIPIENT = "<not the lab address>"
    private val REQUEST_ID = Regex("^[A-Za-z0-9._:-]{1,120}$")
    private val FILE_NAME = Regex("(?i)[\\w.-]+\\.[a-z0-9]{2,5}\\b")

    /** Seams for JVM tests; production uses the overlay, the Mind and Task Kit. */
    internal var overlayReady: () -> Boolean = { OverlayChromeRuntime.isAttached() }
    internal var busy: () -> Boolean = { OverlayChromeRuntime.hasExecutingTask() || MindMissions.isLive() || !WorkspaceTasks.canStartRequest() }
    internal var humanHasControl: () -> Boolean = { DeviceState.controller == DeviceState.Controller.HUMAN }
    internal var start: (String, String, MindLabVariant) -> String? = { goal, runId, variant -> MindMissions.startLab(app(), goal, runId, variant) }
    internal var live: () -> Mission? = { MindMissions.live.value }
    internal var load: (String) -> Mission? = { MindMissions.store(app()).load(it) }
    internal var liveMetrics: (String) -> JSONObject? = { MindMissions.liveMetrics(it) }
    internal var moment: () -> OwnerMoment? = { OwnerMomentsRuntime.current() }
    internal var send: (String, TaskCommand) -> TaskCommandResult = { taskId, command -> TaskCommands.send(app(), taskId, command) }
    internal var appVersion: () -> Pair<String, Long> = { BuildConfig.VERSION_NAME to BuildConfig.VERSION_CODE.toLong() }

    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }
    private fun app(): Context = checkNotNull(context) { "lab adapter not installed" }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "lab.start" -> start(args)
        "lab.status" -> status(args)
        "lab.answer" -> answer(args)
        "lab.record" -> record(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported lab operation: $op")
    }

    fun start(args: JSONObject): JSONObject {
        requireOnly(args, setOf("goal", "runId", "variant"))
        val goal = (args.opt("goal") as? String)?.trim().orEmpty()
        if (goal.isBlank() || goal.length > MAX_GOAL) throw invalid("goal must be 1..$MAX_GOAL characters of text.")
        if (INLINE_SECRET.containsMatchIn(goal)) throw invalid("Do not put secrets in a goal; Cyclone will ask on the phone.")
        val runId = (args.opt("runId") as? String).orEmpty()
        if (!MissionLab.RUN_ID.matches(runId)) throw invalid("runId must be 6..80 letters, digits, dash or underscore.")
        val variant = MindLabVariant.parse(args.optJSONObject("variant")).getOrElse { throw invalid(it.message ?: "variant is malformed.") }
        if (!overlayReady()) throw GatewayProtocolException("OVERLAY_UNAVAILABLE", "Turn on Cyclone's accessibility service on the phone.")
        if (humanHasControl()) throw GatewayProtocolException("HUMAN_HAS_CONTROL", "You have control of the phone. Give it back to Cyclone first.")
        if (busy()) throw GatewayProtocolException("ASK_BUSY", "The phone is already running a task.")
        val id = start(goal, runId, variant) ?: throw GatewayProtocolException("ASK_BUSY", "The phone is already running a mission.")
        return JSONObject().put("accepted", true).put("missionId", id).put("taskId", "mission-$id")
    }

    fun status(args: JSONObject): JSONObject {
        val id = missionId(args, setOf("missionId"))
        val running = live()?.takeIf { it.id == id }
        val mission = running ?: load(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "No such mission.")
        val open = moment()?.takeIf { running != null && it.taskId == "mission-$id" }
        return JSONObject()
            .put("missionId", id)
            .put("status", mission.status.name.lowercase())
            .put("live", running != null)
            .put("turns", mission.turns)
            .put("workingMs", mission.workingMs)
            .put("costUsd", mission.usage.costUsd)
            .put("moment", open?.let(::momentJson) ?: JSONObject.NULL)
            // Alpha 108: failed actions so far, so the PC lab can stop a run that is going nowhere.
            .put("errors", (if (running != null) liveMetrics(id) else mission.metrics)?.optInt("errors", 0) ?: 0)
    }

    fun answer(args: JSONObject): JSONObject {
        requireOnly(args, setOf("missionId", "action", "text", "values", "requestId"))
        val id = missionId(args, null)
        val action = (args.opt("action") as? String).orEmpty()
        if (action !in ANSWERS) throw invalid("action must be one of ${ANSWERS.joinToString()}.")
        live()?.takeIf { it.id == id } ?: throw GatewayProtocolException("RUN_NOT_FOUND", "That mission is not running.")
        val command = when (action) {
            "reply" -> (args.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 }
                ?.also { if (INLINE_SECRET.containsMatchIn(it)) throw invalid("Do not put secrets in a lab answer.") }
                ?.let { TaskCommand.Reply(it) } ?: throw invalid("reply needs text of 1..500 characters.")
            "fill" -> TaskCommand.Fill(values(args.optJSONObject("values")), remember = false)
            "decline" -> TaskCommand.Decline
            // Plan 38: the lab changes the task mid-run, the same Steer the owner sends from the Ask bar.
            "steer" -> (args.opt("text") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 500 }
                ?.also { if (INLINE_SECRET.containsMatchIn(it)) throw invalid("Do not put secrets in a lab answer.") }
                ?.let { TaskCommand.Steer(it) } ?: throw invalid("steer needs text of 1..500 characters.")
            "approve" -> {
                val requestId = (args.opt("requestId") as? String).orEmpty()
                if (!REQUEST_ID.matches(requestId)) throw invalid("approve needs the requestId of the open approval.")
                val open = moment()?.takeIf { it.taskId == "mission-$id" && it.requestId == requestId }
                    ?: throw GatewayProtocolException("MOMENT_CHANGED", "Cyclone is not waiting for that any more.")
                if (!labMayApprove(open)) throw GatewayProtocolException("ANSWER_ON_PHONE",
                    "The lab only approves deleting its own test file or sending to the lab address. Approve anything else on the phone.")
                TaskCommand.Approve
            }
            else -> TaskCommand.Stop
        }
        val result = send("mission-$id", command)
        return JSONObject().put("handled", result.handled).put("detail", result.detail.take(200))
    }

    fun record(args: JSONObject): JSONObject {
        val id = missionId(args, setOf("missionId"))
        val running = live()?.takeIf { it.id == id }
        val mission = running ?: load(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "No such mission.")
        val metrics = (if (running != null) liveMetrics(id) else null) ?: mission.metrics ?: JSONObject()
        return recordJson(mission, metrics, running != null, appVersion())
    }

    /** The one-line-per-field view of an Owner Moment the PC needs to answer it. Never includes typed values. */
    internal fun momentJson(moment: OwnerMoment): JSONObject = JSONObject()
        .put("kind", moment.kind.name.lowercase())
        .put("text", MindRedaction.scrubText(moment.text).take(300))
        .put("choices", JSONArray(moment.choices.take(6).map { it.take(80) }))
        .put("fields", JSONArray().also { out ->
            moment.fields.take(8).forEach { out.put(JSONObject().put("label", it.label.take(60)).put("kind", it.kind.take(20))) }
        })
        .put("requestId", moment.requestId ?: JSONObject.NULL)
        // Alpha 108: the approval's gate and whether a send goes to the lab address. The lab only needs that yes or no,
        // so any other recipient is never sent to the PC at all.
        .put("gate", moment.gate?.take(40) ?: JSONObject.NULL)
        .put("send", moment.send?.let {
            val recipient = if (it.recipient.trim().equals(LAB_RECIPIENT, ignoreCase = true)) LAB_RECIPIENT else NOT_LAB_RECIPIENT
            JSONObject().put("text", MindRedaction.scrubText(it.text).take(1_000)).put("recipient", recipient.take(200)).put("app", it.app.take(120))
        } ?: JSONObject.NULL)

    /**
     * Alpha 108: the only approvals the lab may give, decided here on the phone. Deleting exactly the lab's own file, or
     * sending to exactly the lab address; nothing hidden in what is approved. Pay, grant and every other target are no.
     */
    internal fun labMayApprove(moment: OwnerMoment): Boolean {
        if (moment.kind != com.cyclone.mobile.owner.MomentKind.APPROVAL || moment.requestId == null) return false
        if (MindRedaction.scrubText(moment.text) != moment.text) return false
        return when (moment.gate?.lowercase()) {
            "delete" -> {
                val files = FILE_NAME.findAll(moment.text).map { it.value.lowercase() }.toSet()
                files == setOf(LAB_FILE)
            }
            "send" -> moment.send?.recipient?.trim()?.equals(LAB_RECIPIENT, ignoreCase = true) == true
            else -> false
        }
    }

    internal fun recordJson(mission: Mission, metrics: JSONObject, live: Boolean, version: Pair<String, Long>): JSONObject = JSONObject()
        .put("missionId", mission.id)
        .put("goal", MindRedaction.scrub(mission.goal).take(600))
        .put("status", mission.status.name.lowercase())
        .put("live", live)
        .put("summary", MindRedaction.scrubText(mission.summary).take(600))
        .put("evidence", MindRedaction.scrubText(mission.evidence).take(600))
        .put("turns", mission.turns)
        .put("workingMs", mission.workingMs)
        .put("resumes", mission.resumes)
        .put("createdAt", mission.createdAtMs)
        .put("updatedAt", mission.updatedAtMs)
        .put("modelId", mission.modelId.take(120))
        .put("modelLabel", mission.modelLabel.take(120))
        .put("usage", JSONObject().put("promptTokens", mission.usage.promptTokens).put("completionTokens", mission.usage.completionTokens)
            .put("costUsd", mission.usage.costUsd))
        .put("traceId", mission.traceId ?: JSONObject.NULL)
        .put("lab", mission.lab?.toJson() ?: JSONObject.NULL)
        .put("metrics", metrics)
        .put("events", JSONArray().also { out ->
            // Alpha 91: the start and the end of a long run (the first 10 and the last 50), not only its last 20 events.
            val events = mission.events
            (if (events.size <= 60) events else events.take(10) + events.takeLast(50)).forEach { out.put(JSONObject().put("at", it.atMs).put("text", MindRedaction.scrubText(it.text).take(200)).put("ok", it.ok)) }
        })
        .put("app", JSONObject().put("versionName", version.first).put("versionCode", version.second))

    private fun values(json: JSONObject?): Map<String, String> {
        json ?: throw invalid("fill needs values.")
        val out = linkedMapOf<String, String>()
        json.keys().forEach { key ->
            val value = json.opt(key) as? String ?: throw invalid("fill values must be text.")
            if (key.length > 60 || value.length > 300) throw invalid("fill values are too long.")
            if (INLINE_SECRET.containsMatchIn("$key: $value") || INLINE_SECRET.containsMatchIn("$key=")) throw invalid("Secrets never go through a lab fill.")
            if (value.isNotBlank()) out[key] = value.trim()
        }
        if (out.isEmpty() || out.size > 8) throw invalid("fill needs 1..8 values.")
        return out
    }

    private fun missionId(args: JSONObject, only: Set<String>?): String {
        only?.let { requireOnly(args, it) }
        val id = (args.opt("missionId") as? String).orEmpty()
        if (!MISSION_ID.matches(id)) throw invalid("missionId is malformed.")
        return id
    }

    private fun invalid(message: String) = GatewayProtocolException("INVALID_REQUEST", message)

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        if (args.keys().asSequence().any { it !in allowed }) throw invalid("Unexpected lab field.")
    }
}
