package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard

enum class CookieInterruptionOutcome { HANDLED, NOT_APPLICABLE, AMBIGUOUS, BLOCKED_BY_USER_INTENT, UNRESOLVED }
data class CookieInterruptionDecision(val outcome: CookieInterruptionOutcome, val target: AgentElementCandidate? = null, val reason: String)

/** Allowlisted local consent policy. No arbitrary close buttons or permission/auth grants. */
class CookieInterruptionPolicy {
    private val attempted = mutableSetOf<String>()

    fun next(page: AgentPageCard?, goal: String): AgentElementCandidate? = evaluate(page, goal).target

    fun evaluate(page: AgentPageCard?, goal: String): CookieInterruptionDecision {
        if (page == null || !page.actionable) return CookieInterruptionDecision(CookieInterruptionOutcome.NOT_APPLICABLE, reason = "cookie.no_current_page")
        if (explicitAlternative(goal)) return CookieInterruptionDecision(CookieInterruptionOutcome.BLOCKED_BY_USER_INTENT, reason = "cookie.user_choice")
        val scene = "${page.pageSummary} ${page.pageText} ${page.controls.joinToString { it.label }}".lowercase()
        if (!Regex("\\bcookies?\\b").containsMatchIn(scene)) return CookieInterruptionDecision(CookieInterruptionOutcome.NOT_APPLICABLE, reason = "cookie.no_consent_scene")
        val consentChoices = page.controls.any { normalize(it.label) in consentLabels }
        val advertisedReject = page.controls.any { isRejectLabel(it.label) }
        // An article/footer mentioning cookies is not itself an interruption.
        if (!consentChoices && !advertisedReject) return CookieInterruptionDecision(CookieInterruptionOutcome.NOT_APPLICABLE, reason = "cookie.no_consent_controls")
        val candidates = page.controls.filter { control ->
            control.observationId == page.observationId && control.elementId.isNotBlank() &&
                control.evidence.optBoolean("enabled", true) && control.evidence.optBoolean("visibleToUser", true) &&
                (control.evidence.optBoolean("clickable") || control.role.lowercase() in setOf("button", "link")) &&
                isRejectLabel(control.label) && (normalize(control.label).contains("cookie") || consentChoices)
        }.distinctBy { it.elementId }
        if (candidates.size > 1) return CookieInterruptionDecision(CookieInterruptionOutcome.AMBIGUOUS, reason = "cookie.ambiguous_reject")
        val target = candidates.singleOrNull()
            ?: return CookieInterruptionDecision(CookieInterruptionOutcome.UNRESOLVED, reason = "cookie.reject_unavailable")
        val key = "${page.sessionId}|${page.displayId}|${page.packageName}|${normalize(target.label)}"
        if (!attempted.add(key)) return CookieInterruptionDecision(CookieInterruptionOutcome.UNRESOLVED, reason = "cookie.effect_unverified")
        return CookieInterruptionDecision(CookieInterruptionOutcome.HANDLED, target, "cookie.reject_optional")
    }

    private fun explicitAlternative(goal: String): Boolean =
        Regex("(?i)\\b(accept|allow|enable|keep|manage|customize|customise|accepteer|accepteren|toestaan|beheer)\\b.{0,40}\\bcookies?\\b|\\bcookies?\\b.{0,40}\\b(accept|allow|enable|keep|manage|customize|customise|accepteer|accepteren|toestaan|beheer)\\b").containsMatchIn(goal)

    companion object {
        private fun normalize(label: String) = label.trim().lowercase().replace(Regex("\\s+"), " ")
        fun isRejectLabel(label: String): Boolean = normalize(label) in rejectLabels
        fun explanation(reason: String): String = when (reason) {
            "cookie.ambiguous_reject" -> "More than one reject-cookie control is available. Your original task is paused because the target is ambiguous."
            "cookie.reject_unavailable" -> "A cookie consent choice is visible, but no unique enabled reject control is available. Review the dialog, then continue your original task."
            "cookie.effect_unverified" -> "Cookie rejection was already attempted, but consent removal is still unverified. I will not repeat the click. Review the dialog, then continue your original task."
            else -> "The cookie interruption needs review before the original task can continue."
        }
        private val consentLabels = setOf("accept all", "accept all cookies", "accept cookies", "cookie settings", "manage cookies",
            "alles accepteren", "alle cookies accepteren", "cookies accepteren", "cookie-instellingen", "cookies beheren")
        private val rejectLabels = setOf(
            "reject optional cookies", "reject non-essential cookies", "reject nonessential cookies", "reject all cookies",
            "decline optional cookies", "only necessary cookies", "necessary cookies only", "essential cookies only",
            "use necessary cookies only", "continue without accepting", "reject all", "decline all",
            "optionele cookies weigeren", "alle cookies weigeren", "alleen noodzakelijke cookies",
            "alleen essentiële cookies", "doorgaan zonder accepteren", "alles weigeren",
        )
    }
}
