package com.cyclone.mobile.mind.one

/** Plan 69 §5.3: why a mind is called. PLAN is the first call of a run (and of a handover). */
enum class WakeKind {
    PLAN, GLANCE, CONSULT, REPLAN, WHEEL;

    /** Glances and consults run at low effort; planning, replans and the wheel at the owner's setting. */
    val effort: Effort get() = if (this == GLANCE || this == CONSULT) Effort.LOW else Effort.OWNER
}

enum class Effort { LOW, OWNER }

/**
 * Plan 69 §5.1: what a mind is told when it is woken, built by code. Routine steps never enter the conversation one by
 * one: a briefing carries the events since the mind last looked, the trigger, the plan's position, the facts (copied
 * exactly), the standing orders and focused evidence: the screen's text, an image only when the trigger is visual,
 * and the screen before for a surprise. Pure.
 */
object Briefing {
    data class Evidence(val screen: StepScreen?, val before: StepScreen? = null)

    data class Brief(val text: String, val imageDataUrl: String?, val lastSeq: Long)

    /** Triggers that are about what the screen looks like: the mind gets the screenshot. */
    val VISUAL = setOf("surprise", "popup", "blocked", "drift", "divergence", "unsure", "stuck", "unreadable", "previous_missed")

    const val HEADER = "BRIEFING"
    const val MAX_EVENTS = 40
    const val MAX_CONTROLS = 30
    const val MAX_TEXT = 40

    fun build(
        wake: Int,
        kind: WakeKind,
        trigger: String,
        reason: String,
        score: Score,
        sinceSeq: Long,
        evidence: Evidence? = null,
        notes: List<String> = emptyList(),
        withImage: Boolean = kind == WakeKind.PLAN || trigger in VISUAL,
    ): Brief {
        val b = StringBuilder()
        b.append("$HEADER $wake · ${kind.name} · trigger: $trigger\n")
        if (reason.isNotBlank()) b.append("Why: $reason\n")
        b.append("Goal (version ${score.goal.version}${if (score.goal.version > 1) ", changed by the owner" else ""}): ${score.goal.text}\n")
        score.plan?.let { p -> b.append(position(p)) }

        val events = score.events.filter { it.seq > sinceSeq }
        if (events.isNotEmpty()) {
            b.append("\nSince you last looked:\n")
            if (events.size > MAX_EVENTS) b.append("- (${events.size - MAX_EVENTS} earlier events)\n")
            events.takeLast(MAX_EVENTS).forEach { e -> b.append("- [${e.kind}] ${e.line}\n") }
        }
        if (score.facts.isNotEmpty()) {
            b.append("\nFacts (exact; refer to them by name as {name}):\n")
            score.facts.groupBy { it.name }.values.map { it.last() }.forEach { f -> b.append("- ${f.name} = ${f.value} (from ${f.source})\n") }
        }
        if (score.orders.isNotEmpty()) {
            b.append("\nYour standing orders:\n")
            score.orders.forEach { o -> b.append("- ${o.id} ${o.kind.name.lowercase()}: ${o.text}\n") }
        }
        evidence?.screen?.let { s -> b.append("\n").append(screen("Screen now", s)) }
        evidence?.before?.let { s -> b.append("\n").append(screen("Screen before", s)) }
        notes.filter { it.isNotBlank() }.forEach { b.append("\nNote: $it\n") }
        b.append("\n").append(OneMindPrompt.replyFor(kind))
        val image = evidence?.screen?.takeIf { withImage && !it.sensitive }?.imageDataUrl
        return Brief(b.toString().trimEnd(), image, score.events.lastOrNull()?.seq ?: sinceSeq)
    }

    /** "Plan v2: milestone 2 of 3 "Write the reply", step s5 (type_text · Message); 4 of 9 steps done." */
    fun position(p: Plan): String {
        val all = p.steps
        val done = all.count { it.state == StepState.DONE || it.state == StepState.SKIPPED }
        val current = p.current()
        val b = StringBuilder("Plan version ${p.version}: $done of ${all.size} steps done.\n")
        p.milestones.forEachIndexed { i, m ->
            val mark = when (m.state) {
                MilestoneState.DONE -> "done"
                MilestoneState.FAILED -> "failed"
                MilestoneState.RUNNING -> "running"
                MilestoneState.TODO -> "to do"
            }
            b.append("  ${i + 1}. [${m.id}] ${m.title} ($mark)\n")
            if (current?.first?.id == m.id) m.steps.forEach { s ->
                val here = if (s.id == current.second.id) " ← now" else ""
                b.append("     - ${s.id} ${s.action}${s.target?.let { " · $it" }.orEmpty()} (${s.state.name.lowercase()})$here\n")
            }
        }
        return b.toString()
    }

    /** The screen as text: app, controls (refs, never secret values) and visible text. */
    fun screen(title: String, s: StepScreen): String {
        if (s.sensitive) return "$title: ${s.appLabel} — a sensitive screen (password, code or banking): its content is not shown.\n"
        val b = StringBuilder("$title: ${s.appLabel} (${s.packageName})${if (s.weak) " — the screen tree is weak" else ""}\n")
        s.controls.take(MAX_CONTROLS).forEach { c ->
            b.append("  ${c.ref} ${c.role.ifBlank { "control" }} \"${c.label}\"")
            if (c.focused) b.append(" (focused)")
            c.value?.takeIf { it.isNotBlank() }?.let { b.append(" = \"${it.take(60)}\"") }
            b.append("\n")
        }
        if (s.controls.size > MAX_CONTROLS) b.append("  (${s.controls.size - MAX_CONTROLS} more controls)\n")
        if (s.texts.isNotEmpty()) b.append("  text: ").append(s.texts.take(MAX_TEXT).joinToString(" · ") { it.take(120) }).append("\n")
        return b.toString()
    }

    /** True for a message that is a briefing (compaction folds old ones to their first line). */
    fun isBriefing(text: String): Boolean = text.startsWith(HEADER)
}
