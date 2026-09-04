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
    const val SCHEMA = "cyclone-run-diagnostic-v39/1"
    const val MAX_BYTES = 384 * 1024

    data class Metrics(
        val toolCalls: Int,
        val failures: Int,
        val recoveries: Int,
        val visionChecks: Int,
        val verifiedActions: Int,
    )

    fun metrics(events: List<AiTraceEvent>): Metrics = Metrics(
        toolCalls = events.count { it.kind in setOf("ACTION_REQUESTED", "TOOL_REQUESTED", "TOOL_CALL") },
        failures = events.count {
            it.ok == false && it.kind in setOf("ANDROID_EXECUTION", "TOOL_RESULT", "VERIFICATION", "PROGRESS_CLASSIFIED")
        },
        recoveries = events.count { it.kind.startsWith("RECOVERY") || it.kind == "REPLAN" },
        visionChecks = events.count { it.kind.contains("VISION") },
        verifiedActions = events.count { it.kind == "VERIFICATION" && it.ok == true },
    )

    fun ensureCanonical(context: Context, sessionId: String): File? {
        AgentTraceRuntime.initialize(context)
        val session = AgentTraceRuntime.store.listSessions(200).firstOrNull { it.id == sessionId } ?: return null
        if (session.status == "RUNNING") return null
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

    fun writeToUri(context: Context, sessionId: String, uri: Uri): Boolean {
        val file = ensureCanonical(context, sessionId) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: error("Unable to open destination")
            true
        }.getOrDefault(false)
    }

    fun canonicalFile(context: Context, session: AiTraceSession): File {
        val safeId = session.id.replace(Regex("[^A-Za-z0-9._-]+"), "-")
        return File(File(context.filesDir, "Cyclone Brain/Run Logs"), "$safeId.txt")
    }

    fun format(session: AiTraceSession, events: List<AiTraceEvent>): String {
        val cleanGoal = clean(session.goal)
        val cleanResult = clean(session.result.orEmpty())
        val metrics = metrics(events)
        val duration = (session.endedAt ?: session.startedAt) - session.startedAt
        val header = buildString {
            appendLine("============================================================")
            appendLine("CYCLONE RUN DIAGNOSTIC")
            appendLine("============================================================")
            appendLine("Schema: $SCHEMA")
            appendLine("Cyclone version: ${BuildConfig.VERSION_NAME}")
            appendLine("Task ID: ${session.id}")
            appendLine("Started: ${formatTime(session.startedAt)}")
            session.endedAt?.let { appendLine("Ended: ${formatTime(it)}") }
            appendLine("Duration ms: ${duration.coerceAtLeast(0)}")
            appendLine("Status: ${session.status}")
            appendLine("Model: ${clean(session.model)}")
            appendLine("Goal: $cleanGoal")
            appendLine()
            appendLine("METRICS")
            appendLine("------------------------------------------------------------")
            appendLine("Model/decision turns: ${session.decisions}")
            appendLine("Tool calls: ${metrics.toolCalls}")
            appendLine("Verified actions: ${metrics.verifiedActions}")
            appendLine("Failures: ${metrics.failures}")
            appendLine("Recovery events: ${metrics.recoveries}")
            appendLine("Vision events: ${metrics.visionChecks}")
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
            appendLine("FINAL RESULT")
            appendLine("============================================================")
            appendLine("classification/status: ${session.status}")
            appendLine("verified completion: ${session.status == "COMPLETED"}")
            if (cleanResult.isNotBlank()) appendLine("message: $cleanResult")
            appendLine()
            appendLine("PRIVACY")
            appendLine("============================================================")
            appendLine("This file intentionally excludes hidden provider reasoning, credentials, raw typed values, screenshot pixels/Base64 and full accessibility trees.")
        }
        return bounded(header, timeline, tail)
    }

    private fun section(kind: String): String = when {
        kind in setOf("PAGE", "BRAIN", "MODEL_CONTEXT", "OBSERVE", "KNOWN_ROUTE_LOOKUP") -> "MODEL SAW / CONTEXT"
        kind in setOf("PLAN", "DECISION", "MODEL_DECISION") -> "MODEL DECISION"
        kind in setOf("ACTION_REQUESTED", "TOOL_REQUESTED", "TOOL_CALL") -> "TOOL REQUEST"
        kind in setOf("ANDROID_EXECUTION", "TOOL_RESULT") -> "TOOL RESULT"
        kind in setOf("AFTER_OBSERVATION", "VERIFICATION", "PROGRESS_CLASSIFIED") -> "VERIFICATION"
        kind.startsWith("RECOVERY") || kind == "REPLAN" -> "RECOVERY"
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
        .take(8_000)

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
