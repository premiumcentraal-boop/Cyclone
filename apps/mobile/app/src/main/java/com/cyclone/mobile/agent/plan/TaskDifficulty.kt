package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.agent.contract.GoalContractCompiler
import com.cyclone.mobile.agent.contract.GoalRequirementKind
import com.cyclone.mobile.fastpath.FastPathLanding

enum class TaskDifficultyTier {
    EASY,
    MEDIUM,
    HARD,
}

/**
 * Three execution tiers:
 * EASY — named app / website open only. Local landing, no planner.
 * MEDIUM — one app or site, short in-scene work. Current page agent after landing.
 * HARD — multi-app or long-horizon. One waypoint plan, then local execute.
 */
object TaskDifficulty {
    private val THEN = Regex("(?i)\\b(then|after that|afterwards|and then)\\b")
    private val CROSS_APP = Regex("(?i)\\b(send|share|email|message|whatsapp|gmail|order|checkout|post|forward)\\b")
    private val EXTRA_WORK = Regex("(?i)\\b(search|type|send|buy|order|post|message|log\\s*in|sign\\s*in|scroll|click|tap|then)\\b")
    private val HOST = Regex("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,}\\b")

    fun classify(goal: String): TaskDifficultyTier {
        val clean = goal.trim()
        if (clean.isBlank()) return TaskDifficultyTier.MEDIUM
        if (isHard(clean)) return TaskDifficultyTier.HARD
        if (isEasy(clean)) return TaskDifficultyTier.EASY
        return TaskDifficultyTier.MEDIUM
    }

    fun isNamedAppOpenOnly(goal: String): Boolean {
        val named = FastPathLanding.namedApp(goal) ?: return false
        if (GoalContractCompiler.isSimpleWebNavigation(goal)) return false
        if (EXTRA_WORK.containsMatchIn(goal)) return false
        val leftover = goal.lowercase()
            .replace(named.first, " ", ignoreCase = true)
            .replace(Regex("(?i)\\b(open|launch|start|go to|please|the|app)\\b"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return leftover.isEmpty()
    }

    fun isEasy(goal: String): Boolean =
        GoalContractCompiler.isSimpleWebNavigation(goal) || isNamedAppOpenOnly(goal)

    private fun isHard(goal: String): Boolean {
        if (namedAppCount(goal) >= 2) return true
        if (HOST.findAll(goal).map { it.value.lowercase().removePrefix("www.") }.distinct().count() >= 2) return true
        return THEN.containsMatchIn(goal) && CROSS_APP.containsMatchIn(goal)
    }

    fun namedAppCount(goal: String): Int {
        val lower = goal.lowercase()
        return FastPathLanding.APP_PACKAGE_ALIASES.keys.count { alias ->
            Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(alias) + "(?![\\p{L}\\p{N}])").containsMatchIn(lower)
        }
    }

    fun hasAuthenticatedSession(goal: String): Boolean =
        GoalContractCompiler.compile(goal).requirements.any { it.kind == GoalRequirementKind.AUTHENTICATED_SESSION }
}
