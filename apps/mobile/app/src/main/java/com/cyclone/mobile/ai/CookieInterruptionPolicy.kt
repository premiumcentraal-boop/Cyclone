package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard

/** A local nuisance handler, scoped to one run. It never retries an unchanged consent action. */
class CookieInterruptionPolicy {
    private val attempted = mutableSetOf<String>()

    fun next(page: AgentPageCard?, goal: String): AgentElementCandidate? {
        if (page == null || !page.actionable || explicitAlternative(goal)) return null
        val scene = "${page.pageSummary} ${page.pageText} ${page.controls.joinToString { it.label }}".lowercase()
        if (!Regex("\\bcookies?\\b").containsMatchIn(scene)) return null
        val candidates = page.controls.filter { control ->
            control.observationId == page.observationId && control.elementId.isNotBlank() &&
                control.evidence.optBoolean("enabled", true) && control.evidence.optBoolean("visible", true) &&
                (control.evidence.optBoolean("clickable") || control.role.lowercase() in setOf("button", "link")) &&
                normalize(control.label) in rejectLabels
        }.distinctBy { it.elementId }
        // Ambiguity belongs to semantic/visual recovery, never an arbitrary first match.
        val target = candidates.singleOrNull() ?: return null
        val key = "${page.sessionId}|${page.displayId}|${page.packageName}|${normalize(target.label)}"
        return target.takeIf { attempted.add(key) }
    }

    private fun explicitAlternative(goal: String): Boolean {
        // Defer explicit cookie-choice instructions to the planner, including negated instructions.
        // This avoids silently reversing a user's choice through an over-broad intent parser.
        return Regex("(?i)\\b(accept|allow|enable|keep|manage|customize|customise)\\b.{0,40}\\bcookies?\\b|\\bcookies?\\b.{0,40}\\b(accept|allow|enable|keep|manage|customize|customise)\\b")
            .containsMatchIn(goal)
    }

    private fun normalize(label: String) = label.trim().lowercase().replace(Regex("\\s+"), " ")

    private val rejectLabels = setOf(
        "reject optional cookies", "reject non-essential cookies", "reject nonessential cookies",
        "reject all cookies", "decline optional cookies", "only necessary cookies",
        "necessary cookies only", "essential cookies only", "use necessary cookies only",
        "continue without accepting", "reject all", "decline all",
    )
}
