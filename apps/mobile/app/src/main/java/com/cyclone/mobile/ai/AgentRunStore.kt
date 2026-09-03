package com.cyclone.mobile.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object AgentRunEventBus {
    private val listeners = CopyOnWriteArrayList<(AgentRunEvent) -> Unit>()

    fun publish(event: AgentRunEvent) {
        listeners.forEach { listener -> runCatching { listener(event) } }
    }

    fun subscribe(listener: (AgentRunEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}

object AgentRunRuntime {
    @Volatile private var initialized = false
    private lateinit var store: AgentRunStore

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        store = AgentRunStore(File(context.applicationContext.filesDir, "agent-runs-v388"))
        initialized = true
    }

    fun start(
        context: Context,
        goal: String,
        model: String,
        runId: String = "run-${UUID.randomUUID()}",
        startedAtMs: Long = System.currentTimeMillis(),
    ): String {
        initialize(context)
        store.start(runId, goal, model, startedAtMs)
        event(
            context = context,
            runId = runId,
            type = AgentRunEventType.TASK_STARTED,
            message = "Task started",
            timestampMs = startedAtMs,
        )
        return runId
    }

    fun event(
        context: Context,
        runId: String,
        type: AgentRunEventType,
        message: String = "",
        tool: String? = null,
        modelTurn: Int? = null,
        toolTurn: Int? = null,
        mutation: Boolean? = null,
        payload: JSONObject = JSONObject(),
        timestampMs: Long = System.currentTimeMillis(),
    ): AgentRunEvent {
        initialize(context)
        return store.append(
            runId = runId,
            type = type,
            message = message,
            tool = tool,
            modelTurn = modelTurn,
            toolTurn = toolTurn,
            mutation = mutation,
            payload = payload,
            timestampMs = timestampMs,
        ).also(AgentRunEventBus::publish)
    }

    fun finish(
        context: Context,
        runId: String,
        status: AgentRunStatus,
        finalClassification: String,
        summary: String,
        timestampMs: Long = System.currentTimeMillis(),
    ): AgentRunRecord? {
        initialize(context)
        val terminalType = if (status == AgentRunStatus.COMPLETE) AgentRunEventType.COMPLETE else AgentRunEventType.FAILED
        val current = store.get(runId) ?: return null
        if (current.events.lastOrNull()?.type !in setOf(AgentRunEventType.COMPLETE, AgentRunEventType.FAILED)) {
            event(context, runId, terminalType, summary, timestampMs = timestampMs)
        }
        return store.finish(runId, status, finalClassification, summary, timestampMs)
    }

    fun recent(context: Context, limit: Int = 60): List<AgentRunRecord> {
        initialize(context)
        val native = store.list(limit * 2)
        AgentTraceRuntime.initialize(context)
        val legacy = AgentTraceRuntime.store.listSessions(limit * 2).map { session ->
            legacyRecord(session, AgentTraceRuntime.store.events(session.id))
        }
        val nativeIds = native.mapTo(linkedSetOf()) { it.id }
        return (native + legacy.filterNot { it.id in nativeIds })
            .sortedByDescending { it.startedAtMs }
            .take(limit.coerceIn(1, 200))
    }

    fun get(context: Context, runId: String): AgentRunRecord? {
        initialize(context)
        store.get(runId)?.let { return it }
        AgentTraceRuntime.initialize(context)
        val session = AgentTraceRuntime.store.listSessions(200).firstOrNull { it.id == runId } ?: return null
        return legacyRecord(session, AgentTraceRuntime.store.events(runId))
    }

    private fun legacyRecord(session: AiTraceSession, events: List<AiTraceEvent>): AgentRunRecord {
        val status = when (session.status) {
            "RUNNING" -> AgentRunStatus.RUNNING
            "COMPLETED" -> AgentRunStatus.COMPLETE
            else -> AgentRunStatus.FAILED
        }
        return AgentRunRecord(
            id = session.id,
            goal = AgentRunSanitizer.cleanText(session.goal),
            model = AgentRunSanitizer.cleanText(session.model),
            startedAtMs = session.startedAt,
            endedAtMs = session.endedAt,
            status = status,
            summary = AgentRunSanitizer.cleanText(session.result.orEmpty()),
            finalClassification = session.status,
            events = events.mapIndexed { index, event ->
                AgentRunEvent(
                    id = event.id,
                    runId = session.id,
                    sequence = index + 1,
                    timestampMs = event.timestampMs,
                    type = legacyType(event),
                    message = AgentRunSanitizer.cleanText(event.displayText),
                    tool = legacyTool(event),
                    payload = JSONObject().apply {
                        event.code?.let { put("legacyCode", it) }
                        event.ok?.let { put("legacyOk", it) }
                        event.detail?.let { put("legacyDetail", AgentRunSanitizer.cleanText(it)) }
                    },
                )
            },
            schema = "cyclone-ai-history-v1/legacy-adapter",
        )
    }

    private fun legacyType(event: AiTraceEvent): AgentRunEventType = when (event.kind) {
        "START" -> AgentRunEventType.TASK_STARTED
        "PAGE", "OBSERVE" -> AgentRunEventType.READING_PAGE
        "BRAIN", "REPLAY" -> AgentRunEventType.USING_BRAIN
        "MODEL", "DECISION" -> AgentRunEventType.THINKING
        "VISION" -> AgentRunEventType.USING_VISION
        "RESULT" -> AgentRunEventType.VERIFYING
        "RECOVERY" -> AgentRunEventType.RECOVERING
        "BOUNDARY" -> AgentRunEventType.GATE_REQUIRED
        "LEARNING" -> if (event.ok == false) AgentRunEventType.LEARNING_REJECTED else AgentRunEventType.LEARNING_ACCEPTED
        "DONE" -> AgentRunEventType.COMPLETE
        "STOPPED" -> AgentRunEventType.FAILED
        else -> if (event.ok == false) AgentRunEventType.TOOL_RESULT else AgentRunEventType.THINKING
    }

    private fun legacyTool(event: AiTraceEvent): String? {
        val code = event.code.orEmpty()
        return code.takeIf { it.startsWith("phone.") || it.startsWith("agent.") }
    }
}

class AgentRunStore(private val root: File) {
    init {
        require(root.exists() || root.mkdirs()) { "Could not create Cyclone agent run directory" }
    }

    @Synchronized
    fun start(runId: String, goal: String, model: String, startedAtMs: Long): AgentRunRecord {
        val id = safeId(runId)
        require(id.isNotBlank()) { "runId is required" }
        val record = AgentRunSanitizer.sanitizeRecord(
            AgentRunRecord(id = id, goal = goal, model = model, startedAtMs = startedAtMs),
        )
        write(record)
        return record
    }

    @Synchronized
    fun append(
        runId: String,
        type: AgentRunEventType,
        message: String,
        tool: String?,
        modelTurn: Int?,
        toolTurn: Int?,
        mutation: Boolean?,
        payload: JSONObject,
        timestampMs: Long,
    ): AgentRunEvent {
        val current = get(runId) ?: error("Unknown run: ${safeId(runId)}")
        val nextSequence = (current.events.maxOfOrNull { it.sequence } ?: 0) + 1
        val event = AgentRunSanitizer.sanitizeEvent(
            AgentRunEvent(
                id = "evt-${UUID.randomUUID()}",
                runId = current.id,
                sequence = nextSequence,
                timestampMs = timestampMs,
                type = type,
                message = message,
                tool = tool,
                modelTurn = modelTurn,
                toolTurn = toolTurn,
                mutation = mutation,
                payload = AgentRunSanitizer.sanitizeObject(payload, tool),
            ),
        )
        write(current.copy(events = (current.events + event).takeLast(MAX_EVENTS)))
        return event
    }

    @Synchronized
    fun finish(
        runId: String,
        status: AgentRunStatus,
        finalClassification: String,
        summary: String,
        endedAtMs: Long,
    ): AgentRunRecord? {
        val current = get(runId) ?: return null
        val final = AgentRunSanitizer.sanitizeRecord(
            current.copy(
                endedAtMs = endedAtMs,
                status = status,
                finalClassification = finalClassification,
                summary = summary,
            ),
        )
        write(final)
        return final
    }

    @Synchronized
    fun get(runId: String): AgentRunRecord? {
        val file = fileFor(runId)
        if (!file.isFile) return null
        return runCatching { AgentRunCodec.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    @Synchronized
    fun list(limit: Int = 60): List<AgentRunRecord> = root.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && it.extension == "json" && !it.name.endsWith(".tmp.json") }
        .mapNotNull { file -> runCatching { AgentRunCodec.fromJson(JSONObject(file.readText())) }.getOrNull() }
        .sortedByDescending { it.startedAtMs }
        .take(limit.coerceIn(1, 200))
        .toList()

    private fun write(record: AgentRunRecord) {
        val safe = AgentRunSanitizer.sanitizeRecord(record)
        val target = fileFor(safe.id)
        val temp = File(root, target.nameWithoutExtension + ".tmp.json")
        temp.writeText(AgentRunCodec.toJson(safe).toString())
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Could not replace existing Cyclone run record")
        }
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(runId: String) = File(root, safeId(runId) + ".json")

    private fun safeId(value: String): String = value
        .trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)

    companion object { private const val MAX_EVENTS = 800 }
}
