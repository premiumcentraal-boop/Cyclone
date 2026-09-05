package com.cyclone.mobile.agent.contract

import org.json.JSONArray
import org.json.JSONObject

enum class GoalRequirementKind {
    WEB_HOST,
    VERIFIED_SCROLL,
    DISMISS_COOKIE_CONSENT,
    SITE_NOTIFICATION_PERMISSION,
    VERIFIED_TARGET_INTERACTION,
    GENERIC_SEMANTIC_EVIDENCE,
}

data class GoalRequirement(
    val kind: GoalRequirementKind,
    val value: String? = null,
    val terms: List<String> = emptyList(),
)

data class GoalContract(
    val sourceGoal: String,
    val requirements: List<GoalRequirement>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sourceGoal", sourceGoal.take(500))
        .put("requirements", JSONArray().also { array ->
            requirements.forEach { requirement ->
                array.put(
                    JSONObject()
                        .put("kind", requirement.kind.name)
                        .put("value", requirement.value ?: JSONObject.NULL)
                        .put("terms", JSONArray(requirement.terms)),
                )
            }
        })
}

data class GoalRequirementResult(
    val requirement: GoalRequirement,
    val satisfied: Boolean,
    val evidence: String,
)

data class GoalContractEvaluation(
    val satisfied: Boolean,
    val results: List<GoalRequirementResult>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("satisfied", satisfied)
        .put("requirements", JSONArray().also { array ->
            results.forEach { result ->
                array.put(
                    JSONObject()
                        .put("kind", result.requirement.kind.name)
                        .put("value", result.requirement.value ?: JSONObject.NULL)
                        .put("satisfied", result.satisfied)
                        .put("evidence", result.evidence.take(240)),
                )
            }
        })
}

/**
 * Compiles common phone-task intent into stable semantic completion predicates.
 *
 * This is deliberately not a site/app recipe system. It recognizes reusable task effects such as
 * reaching a web host, performing a verified scroll, dismissing a consent surface, or completing a
 * requested target interaction. The model still chooses how to achieve the goal; the contract only
 * defines what independently verifiable success means.
 */
object GoalContractCompiler {
    /** Only complete simple navigation locally; compound/content goals still need model decisions. */
    fun isSimpleWebNavigation(goal: String): Boolean = Regex(
        "(?i)^(?:please\\s+)?(?:open|go to|navigate to)\\s+(?:https?://)?(?:www\\.)?" +
            "(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}/?\\s*$",
    ).matches(goal.trim())

    private val hostPattern = Regex(
        "(?i)(?:https?://)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63})(?=[:/?#\\s]|$)",
    )
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val stopWords = setOf(
        "open", "go", "navigate", "take", "to", "the", "a", "an", "and", "then", "finally",
        "find", "show", "me", "on", "in", "for", "please", "page", "screen", "website", "site",
        "click", "tap", "press", "select", "choose", "button", "scroll", "swipe", "down", "up",
    )

    fun compile(goal: String): GoalContract {
        val clean = goal.trim()
        val lower = clean.lowercase()
        val requirements = mutableListOf<GoalRequirement>()

        val host = hostPattern.find(clean)?.groupValues?.getOrNull(1)
            ?.lowercase()
            ?.removePrefix("www.")
        if (!host.isNullOrBlank()) {
            requirements += GoalRequirement(GoalRequirementKind.WEB_HOST, host)
        }

        if (Regex("(?i)\\b(scroll|swipe)\\b").containsMatchIn(clean)) {
            val direction = when {
                Regex("(?i)\\b(up|upward|upwards)\\b").containsMatchIn(clean) -> "UP"
                else -> "DOWN"
            }
            requirements += GoalRequirement(GoalRequirementKind.VERIFIED_SCROLL, direction)
        }

        val cookieIntent = listOf("cookie", "cookies", "consent", "tracking").any(lower::contains)
        if (cookieIntent) {
            requirements += GoalRequirement(
                GoalRequirementKind.DISMISS_COOKIE_CONSENT,
                terms = significantTerms(clean),
            )
        }

        val notificationIntent = listOf("notification", "notifications").any(lower::contains)
        if (notificationIntent) {
            val desiredState = when {
                listOf("block", "deny", "disable", "don't allow", "dont allow").any(lower::contains) -> "DENY"
                listOf("allow", "enable").any(lower::contains) -> "ALLOW"
                else -> null
            }
            if (desiredState != null) {
                requirements += GoalRequirement(
                    GoalRequirementKind.SITE_NOTIFICATION_PERMISSION,
                    value = desiredState,
                    terms = significantTerms(clean),
                )
            }
        }

        if (requirements.isEmpty() && Regex("(?i)\\b(click|tap|press|select|choose)\\b").containsMatchIn(clean)) {
            requirements += GoalRequirement(
                GoalRequirementKind.VERIFIED_TARGET_INTERACTION,
                terms = significantTerms(finalGoalSegment(clean)),
            )
        }

        if (requirements.isEmpty()) {
            requirements += GoalRequirement(
                GoalRequirementKind.GENERIC_SEMANTIC_EVIDENCE,
                terms = significantTerms(finalGoalSegment(clean)),
            )
        }

        return GoalContract(clean, requirements.distinct())
    }

    fun evaluate(
        contract: GoalContract,
        currentPage: AgentPageCard?,
        history: List<AgentActionEnvelope>,
    ): GoalContractEvaluation {
        val results = contract.requirements.map { requirement ->
            evaluateRequirement(contract, requirement, currentPage, history)
        }
        return GoalContractEvaluation(results.isNotEmpty() && results.all { it.satisfied }, results)
    }

    private fun evaluateRequirement(
        contract: GoalContract,
        requirement: GoalRequirement,
        currentPage: AgentPageCard?,
        history: List<AgentActionEnvelope>,
    ): GoalRequirementResult {
        val successful = history.filter { it.androidExecutionOk && it.verification.passed }
        return when (requirement.kind) {
            GoalRequirementKind.WEB_HOST -> {
                val host = requirement.value.orEmpty()
                val pageMatch = currentPage?.let { pageShowsHost(it, host) } == true
                GoalRequirementResult(
                    requirement,
                    pageMatch,
                    if (pageMatch) "requested host is present in the authoritative current scene" else "requested host has not been verified",
                )
            }

            GoalRequirementKind.VERIFIED_SCROLL -> {
                val matched = successful.any { it.tool == "phone.scroll" }
                GoalRequirementResult(
                    requirement,
                    matched,
                    if (matched) "a scroll mutation produced verified semantic progress" else "no verified scroll outcome exists",
                )
            }

            GoalRequirementKind.DISMISS_COOKIE_CONSENT -> {
                val matched = successful.asReversed().firstOrNull { envelope ->
                    if (envelope.tool != "phone.click") return@firstOrNull false
                    val before = envelope.before ?: return@firstOrNull false
                    val after = envelope.after ?: return@firstOrNull false
                    val beforeScore = consentSurfaceScore(before, requirement.terms)
                    val afterScore = consentSurfaceScore(after, requirement.terms)
                    beforeScore > 0 && afterScore < beforeScore
                }
                GoalRequirementResult(
                    requirement,
                    matched != null,
                    if (matched != null) "verified click reduced or removed the consent surface" else "no verified consent-surface dismissal is present in the action ledger",
                )
            }

            GoalRequirementKind.SITE_NOTIFICATION_PERMISSION -> {
                val desired = requirement.value.orEmpty()
                val matched = successful.asReversed().firstOrNull { envelope ->
                    if (envelope.tool != "phone.click") return@firstOrNull false
                    val before = envelope.before ?: return@firstOrNull false
                    val after = envelope.after ?: return@firstOrNull false
                    val beforeScore = notificationSurfaceScore(before)
                    val afterScore = notificationSurfaceScore(after)
                    if (beforeScore <= 0 || afterScore >= beforeScore) return@firstOrNull false
                    val expectedLabels = if (desired == "ALLOW") listOf("allow", "enable", "yes") else listOf("block", "deny", "don't allow", "dont allow", "not now")
                    before.controls.any { control -> expectedLabels.any { expected -> control.label.contains(expected, ignoreCase = true) || control.semanticName.contains(expected, ignoreCase = true) } }
                }
                GoalRequirementResult(
                    requirement,
                    matched != null,
                    if (matched != null) "verified notification decision removed the relevant permission surface" else "notification decision is not yet proven by the verified action ledger",
                )
            }

            GoalRequirementKind.VERIFIED_TARGET_INTERACTION -> {
                val matched = successful.asReversed().firstOrNull { envelope ->
                    envelope.tool in setOf("phone.click", "phone.long_press") &&
                        (requirement.terms.isEmpty() || pageMatchesTerms(envelope.before, requirement.terms))
                }
                GoalRequirementResult(
                    requirement,
                    matched != null,
                    if (matched != null) "requested target interaction has a verified outcome" else "requested target interaction is not verified",
                )
            }

            GoalRequirementKind.GENERIC_SEMANTIC_EVIDENCE -> {
                val pageMatch = currentPage?.let { pageMatchesTerms(it, requirement.terms) } == true
                val verifiedMutation = successful.isNotEmpty()
                GoalRequirementResult(
                    requirement,
                    pageMatch || (requirement.terms.isEmpty() && verifiedMutation),
                    when {
                        pageMatch -> "current scene contains the goal's semantic evidence"
                        requirement.terms.isEmpty() && verifiedMutation -> "task has a verified mutation outcome"
                        else -> "goal evidence is not yet present in the current scene"
                    },
                )
            }
        }
    }

    private fun pageMatchesTerms(page: AgentPageCard?, terms: List<String>): Boolean {
        if (page == null || page.packageName == "com.cyclone.mobile" || terms.isEmpty()) return false
        val words = normalizedWords(pageHaystack(page))
        if (words.isEmpty()) return false
        val matched = terms.count { term -> words.any { word -> fuzzyEquivalent(term, word) } }
        val required = if (terms.size == 1) 1 else minOf(2, terms.size)
        return matched >= required
    }

    private fun consentSurfaceScore(page: AgentPageCard, goalTerms: List<String>): Int {
        val generic = listOf("cookie", "cookies", "consent", "tracking", "privacy", "accept", "agree", "reject", "necessary")
        val terms = (generic + goalTerms).distinct()
        var score = 0
        page.controls.forEach { control ->
            val words = normalizedWords("${control.label} ${control.semanticName}")
            if (words.any { word -> terms.any { term -> fuzzyEquivalent(term, word) } }) score += 2
        }
        val textWords = normalizedWords("${page.pageSummary} ${page.pageText}")
        if (textWords.any { word -> generic.any { term -> fuzzyEquivalent(term, word) } }) score += 1
        return score
    }

    private fun notificationSurfaceScore(page: AgentPageCard): Int {
        val notificationTerms = listOf("notification", "notifications")
        val actionTerms = listOf("block", "allow", "deny")
        var score = 0
        page.controls.forEach { control ->
            val text = "${control.label} ${control.semanticName}".lowercase()
            if (actionTerms.any(text::contains)) score += 2
        }
        val surfaceText = "${page.pageSummary} ${page.pageText}".lowercase()
        if (notificationTerms.any(surfaceText::contains)) score += 2
        return score
    }

    private fun pageHaystack(page: AgentPageCard): String = buildString {
        append(page.packageName).append(' ')
        append(page.activity.orEmpty()).append(' ')
        append(page.pageSummary.toString()).append(' ')
        append(page.pageText.toString()).append(' ')
        append(page.pageEvidence.toString()).append(' ')
        page.controls.take(64).forEach { control ->
            append(control.label).append(' ')
            append(control.semanticName).append(' ')
            append(control.evidence.optString("resourceId")).append(' ')
        }
    }.lowercase()

    private fun pageShowsHost(page: AgentPageCard, host: String): Boolean {
        if (!isBrowserLike(page.packageName) || host.isBlank()) return false
        val exactHost = Regex("(?i)(?<![a-z0-9_.@-])(?:https?://)?(?:[a-z0-9-]+\\.)*" +
            Regex.escape(host) + "(?=[:/?#\\s\"']|$)")
        val addressControls = page.controls.filter {
            val id = it.evidence.optString("resourceId").lowercase()
            listOf("url_bar", "urlbar", "location_bar", "address_bar").any(id::contains)
        }
        val text = if (addressControls.isNotEmpty()) addressControls.joinToString(" ") {
            "${it.label} ${it.semanticName}"
        } else "${page.pageSummary} ${page.pageText}"
        return exactHost.containsMatchIn(text)
    }

    private fun finalGoalSegment(value: String): String = value
        .split(Regex("(?i)\\bthen\\b|\\bfinally\\b|->|→|;|,"))
        .map(String::trim)
        .lastOrNull(String::isNotBlank)
        ?: value

    private fun significantTerms(value: String): List<String> = wordPattern.findAll(value.lowercase())
        .map { it.value }
        .filter { it.length >= 3 && it !in stopWords }
        .distinct()
        .toList()
        .takeLast(8)

    private fun normalizedWords(value: String): List<String> = wordPattern.findAll(value.lowercase())
        .map { it.value }
        .filter { it.length >= 2 }
        .take(600)
        .toList()

    private fun fuzzyEquivalent(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
        return editDistanceAtMostOne(a, b)
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }
            edits++
            if (edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    private fun isBrowserLike(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p.contains("chrome") || p.contains("browser") || p.contains("webview") || p.contains("firefox") || p.contains("edge")
    }
}
