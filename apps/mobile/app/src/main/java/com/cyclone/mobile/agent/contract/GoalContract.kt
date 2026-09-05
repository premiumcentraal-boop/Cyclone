package com.cyclone.mobile.agent.contract

import org.json.JSONArray
import org.json.JSONObject

enum class GoalRequirementKind {
    WEB_HOST,
    VERIFIED_SCROLL,
    DISMISS_COOKIE_CONSENT,
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

        if (requirements.isEmpty() && Regex("(?i)\\b(click|tap|press|select|choose)\\b").containsMatchIn(clean)) {
            requirements += GoalRequirement(
                GoalRequirementKind.VERIFIED_TARGET_INTERACTION,
                terms = significantTerms(clean),
            )
        }

        if (requirements.isEmpty()) {
            requirements += GoalRequirement(
                GoalRequirementKind.GENERIC_SEMANTIC_EVIDENCE,
                terms = significantTerms(clean),
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
                val pageMatch = currentPage?.let(::pageHaystack)?.contains(host, ignoreCase = true) == true
                val launchMatch = successful.any { envelope ->
                    envelope.tool == "phone.launch_intent" &&
                        envelope.after?.let { isBrowserLike(it.packageName) } == true &&
                        (envelope.after?.let(::pageHaystack)?.contains(host, ignoreCase = true) == true ||
                            contract.sourceGoal.contains(host, ignoreCase = true))
                }
                GoalRequirementResult(
                    requirement,
                    pageMatch || launchMatch,
                    when {
                        pageMatch -> "requested host is present in the authoritative current scene"
                        launchMatch -> "verified browser launch-intent transition for the requested host"
                        else -> "requested host has not been verified"
                    },
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
                    if (matched != null) {
                        "verified click reduced or removed the consent surface"
                    } else {
                        "no verified consent-surface dismissal is present in the action ledger"
                    },
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
        if (page == null || terms.isEmpty()) return false
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

    private fun significantTerms(value: String): List<String> = wordPattern.findAll(value.lowercase())
        .map { it.value }
        .filter { it.length >= 3 && it !in stopWords }
        .distinct()
        .takeLast(8)
        .toList()

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

    /** Optimized distance<=1 test so typo tolerance never becomes an expensive fuzzy search. */
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
