package com.cyclone.mobile.ai

import android.content.Context
import android.net.Uri
import com.cyclone.mobile.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** High-signal, user-shareable projection of Cyclone's durable trace database. */
object AgentRunDiagnosticV39 {
    const val SCHEMA = "cyclone-run-diagnostic-v39/4"
    const val MAX_BYTES = 1024 * 1024

    data class Metrics(
        val toolCalls: Int,
        val toolFailures: Int,
        val verificationFailures: Int,
        val recoveries: Int,
        val visionChecks: Int,
        val verifiedActions: Int,
        val completionChecks: Int,
        val completionRejections: Int,
        val modelContextSnapshots: Int,
        val freeModeEntries: Int,
        val executorInvocations: Int,
    ) {
        /** Compatibility aggregate for existing Brain/result UI while diagnostics keep failure classes separate. */
        val failures: Int get() = toolFailures + verificationFailures
    }

    data class ExportResult(
        val ok: Boolean,
        val message: String,
    )

    fun metrics(events: List<AiTraceEvent>): Metrics {
        // TOOL_RESULT is the turn summary; ANDROID_EXECUTION/ACTION_REJECTED are per-action
        // results inside it. Count the detailed plane when present, and retain summary-only
        // rejections (the exact failure mode of the 4.3.4 Reddit run).
        val boundary = if (events.any { it.kind == "TOOL_REQUESTED" }) "TOOL_REQUESTED" else "ACTION_REQUESTED"
        val groups = mutableListOf<MutableList<AiTraceEvent>>()
        events.forEach { event ->
            if (groups.isEmpty() || event.kind == boundary) groups += mutableListOf<AiTraceEvent>()
            groups.last().add(event)
        }
        fun preferredCount(primary: String, fallback: String, predicate: (AiTraceEvent) -> Boolean): Int =
            events.filter { it.kind == primary }.takeIf { it.isNotEmpty() }
                ?.count(predicate) ?: events.count { it.kind == fallback && predicate(it) }
        return Metrics(
            toolCalls = groups.sumOf { group ->
                maxOf(group.count { it.kind in setOf("ACTION_REQUESTED", "TOOL_CALL") },
                    group.count { it.kind == "TOOL_REQUESTED" })
            },
            executorInvocations = events.count { it.kind == "ANDROID_EXECUTION" && it.detail.orEmpty().contains("executorInvoked=true") },
            toolFailures = groups.sumOf { group ->
                val detailed = group.filter { it.kind in setOf("ANDROID_EXECUTION", "ACTION_REJECTED") }
                (detailed.takeIf { it.isNotEmpty() } ?: group.filter { it.kind == "TOOL_RESULT" }).count { it.ok == false }
            },
            verificationFailures = groups.sumOf { group ->
                var accepted: Boolean? = null
                val detailed = group.filter { it.kind == "VERIFICATION" }
                if (detailed.isNotEmpty()) {
                    group.count { event ->
                        if (event.kind in setOf("ANDROID_EXECUTION", "ACTION_REJECTED")) accepted = event.ok
                        event.kind == "VERIFICATION" && event.ok == false && accepted != false
                    }
                } else group.count { it.kind == "VERIFY" && it.ok == false && !it.code.orEmpty().startsWith("completion.") }
            },
            recoveries = if (events.any { it.kind == "RECOVERY_CLASSIFIED" })
                events.count { it.kind == "RECOVERY_CLASSIFIED" && it.code != "progress.continue" }
                else preferredCount("RECOVERY_SELECTED", "REPLAN") { true },
            visionChecks = preferredCount("VISION", "VISION_ESCALATION") { true },
            verifiedActions = events.count { it.kind == "VERIFICATION" && it.ok == true },
            completionChecks = events.count {
                it.kind == "VERIFY" && it.code.orEmpty().startsWith("completion.")
            },
            completionRejections = events.count {
                it.kind == "VERIFY" && it.code.orEmpty() in setOf(
                    "completion.unverified",
                    "completion.still_unverified",
                )
            },
            modelContextSnapshots = events.count { it.kind == "MODEL_CONTEXT" },
            freeModeEntries = events.count { it.kind == "FREE_MODE_ENTER" },
        )
    }

    /**
     * Produce a point-in-time snapshot for every run state, including RUNNING and SUSPENDED.
     * Diagnostics are most useful while a task is broken, so export must never depend on terminal state.
     */
    fun ensureCanonical(context: Context, sessionId: String): File? {
        AgentTraceRuntime.initialize(context)
        val session = AgentTraceRuntime.store.session(sessionId) ?: return null
        val file = canonicalFile(context, session)
        file.parentFile?.mkdirs()
        file.writeText(format(session, AgentTraceRuntime.store.events(session.id)))
        return file
    }

    fun suggestedFilename(session: AiTraceSession): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(session.startedAt))
        val status = session.status.uppercase(Locale.US).replace(Regex("[^A-Z0-9_-]+"), "-")
        return "Cyclone-run-$stamp-$status-${session.id.takeLast(12)}.txt"
    }

    fun writeToUri(context: Context, sessionId: String, uri: Uri): Boolean =
        writeToUriDetailed(context, sessionId, uri).ok

    fun writeToUriDetailed(context: Context, sessionId: String, uri: Uri): ExportResult = runCatching {
        val file = ensureCanonical(context, sessionId)
            ?: return ExportResult(false, "Run data could not be found.")
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: return ExportResult(false, "Android could not open the selected destination for writing.")
        output.use { target -> file.inputStream().use { source -> source.copyTo(target) } }
        ExportResult(true, "Diagnostic log saved")
    }.getOrElse { error ->
        val reason = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName }
        ExportResult(false, "Could not save diagnostic log: ${TracePrivacy.clean(reason).take(180)}")
    }

    fun canonicalFile(context: Context, session: AiTraceSession): File {
        val safeId = session.id.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        return File(File(context.filesDir, "Cyclone Brain/Run Logs"), "$safeId.txt")
    }

    fun format(session: AiTraceSession, events: List<AiTraceEvent>): String {
        val cleanGoal = clean(session.goal)
        val cleanResult = clean(session.result.orEmpty())
        val metrics = metrics(events)
        val duration = (session.endedAt ?: System.currentTimeMillis()) - session.startedAt
        val effectiveTurns = maxOf(
            session.decisions,
            events.count { it.kind == "PLAN" && it.code == "model.page_decision" },
        )
        val firstDoneAt = events.firstOrNull {
            it.kind == "PLAN" && it.code == "done"
        }?.timestampMs
        val timeAfterFirstDone = firstDoneAt?.let { doneAt ->
            ((session.endedAt ?: System.currentTimeMillis()) - doneAt).coerceAtLeast(0)
        }
        val header = buildString {
            appendLine("============================================================")
            appendLine("CYCLONE RUN DIAGNOSTIC")
            appendLine("============================================================")
            appendLine("Schema: $SCHEMA")
            appendLine("Cyclone version: ${BuildConfig.VERSION_NAME}")
            appendLine("Task ID: ${session.id}")
            appendLine("Started: ${formatTime(session.startedAt)}")
            session.endedAt?.let { appendLine("Ended: ${formatTime(it)}") }
            appendLine("Exported: ${formatTime(System.currentTimeMillis())}")
            appendLine("Duration ms: ${duration.coerceAtLeast(0)}")
            appendLine("Status at export: ${session.status}")
            appendLine("Model: ${clean(session.model)}")
            appendLine("Goal: $cleanGoal")
            appendLine()
            appendLine("METRICS")
            appendLine("------------------------------------------------------------")
            appendLine("Model/decision turns: $effectiveTurns")
            appendLine("Tool calls: ${metrics.toolCalls}")
            appendLine("Canonical executor invocations (explicit evidence): ${metrics.executorInvocations}")
            appendLine("Verified actions: ${metrics.verifiedActions}")
            appendLine("Tool failures: ${metrics.toolFailures}")
            appendLine("Verification failures: ${metrics.verificationFailures}")
            appendLine("Actual recovery cycles: ${metrics.recoveries}")
            appendLine("Completion checks: ${metrics.completionChecks}")
            appendLine("Completion rejections: ${metrics.completionRejections}")
            appendLine("Model context snapshots: ${metrics.modelContextSnapshots}")
            appendLine("Free Mode entries: ${metrics.freeModeEntries}")
            appendLine("Vision events: ${metrics.visionChecks}")
            timeAfterFirstDone?.let { appendLine("Time after first DONE ms: $it") }
            appendLine()
            appendLine("TIMELINE")
            appendLine("============================================================")
        }
        val timeline = buildString {
            events.forEachIndexed { index, event ->
                appendLine("#${index + 1} ${formatClock(event.timestampMs)}  ${section(event.kind)}")
                appendLine("event: ${clean(event.kind)}")
                event.code?.takeIf(String::isNotBlank)?.let { appendLine("code/tool: ${clean(it)}") }
                event.ok?.let { appendLine("ok: $it") }
                appendLine("message: ${clean(event.displayText)}")
                event.detail?.takeIf(String::isNotBlank)?.let { appendLine("detail: ${clean(it)}") }
                appendLine()
            }
        }
        val tail = buildString {
            appendLine("FINAL / CURRENT RESULT")
            appendLine("============================================================")
            appendLine("classification/status: ${session.status}")
            appendLine("verified completion: ${session.status == "COMPLETED"}")
            if (cleanResult.isNotBlank()) appendLine("message: $cleanResult")
            if (session.status in setOf("RUNNING", "SUSPENDED", "HUMAN_OR_GATE")) {
                appendLine("note: This is a point-in-time snapshot; the task was not terminal when exported.")
            }
            appendLine()
            appendLine("PRIVACY")
            appendLine("============================================================")
            appendLine("This file includes sanitized model-visible context summaries, decisions, canonical tool/verification events and recovery. It intentionally excludes hidden provider reasoning, credentials, raw typed secret values, screenshot pixels/Base64 and full accessibility trees.")
        }
        return bounded(header, timeline, tail)
    }

    private fun section(kind: String): String = when {
        kind in setOf("PAGE", "BRAIN", "MODEL_CONTEXT", "OBSERVE", "KNOWN_ROUTE_LOOKUP") -> "MODEL SAW / CONTEXT"
        kind in setOf("PLAN", "DECISION", "MODEL_DECISION") -> "MODEL DECISION"
        kind in setOf("ACTION_REQUESTED", "TOOL_REQUESTED", "TOOL_CALL") -> "TOOL REQUEST"
        kind in setOf("ANDROID_EXECUTION", "TOOL_RESULT", "ACTION_REJECTED") -> "TOOL RESULT"
        kind in setOf("AFTER_OBSERVATION", "VERIFICATION", "PROGRESS_CLASSIFIED", "VERIFY") -> "VERIFICATION"
        kind.startsWith("RECOVERY") || kind == "REPLAN" -> "RECOVERY"
        kind == "FREE_MODE_ENTER" || kind == "FREE_MODE_EXIT" -> "ADAPTIVE FREE MODE"
        kind.contains("VISION") -> "VISION"
        kind.contains("GATE") || kind == "BOUNDARY" -> "GATE / HUMAN BOUNDARY"
        kind.startsWith("LEARNING") || kind == "LEARNING" -> "BRAIN LEARNING"
        kind in setOf("DONE", "STOPPED", "CANCELLED") -> "FINAL RESULT"
        else -> kind.replace('_', ' ')
    }

    private fun clean(value: String): String = TracePrivacy.clean(value)
        .replace(Regex("(?is)\\\"(?:nodes|accessibilityTree|rawTree)\\\"\\s*:\\s*\\[.*?]"), "\"rawTree\":[REDACTED_TREE]")
        .replace(Regex("(?is)\\\"(?:text|value)\\\"\\s*:\\s*\\\"[^\\\"]*\\\"")) { match ->
            if (match.value.contains("typed", ignoreCase = true)) "\"value\":\"[REDACTED_TYPED_VALUE]\"" else match.value
        }
        .take(12_000)

    private fun bounded(header: String, timeline: String, tail: String): String {
        val fixed = header.toByteArray().size + tail.toByteArray().size
        val budget = (MAX_BYTES - fixed - 160).coerceAtLeast(0)
        val bytes = timeline.toByteArray()
        if (bytes.size <= budget) return header + timeline + tail
        var cut = budget.coerceAtMost(bytes.size)
        while (cut > 0 && (bytes[cut - 1].toInt() and 0xC0) == 0x80) cut--
        val clipped = bytes.copyOfRange(0, cut).toString(Charsets.UTF_8)
        return header + clipped + "\n[DIAGNOSTIC TIMELINE TRUNCATED AT ${MAX_BYTES / 1024} KiB]\n\n" + tail
    }

    private fun formatTime(value: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(value))
    private fun formatClock(value: Long) = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(value))
}
