package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.PageContext

/**
 * Evidence-only promotion. The ask starts the tier; the screen can only raise it.
 * A router model is never required. Easy cannot be demoted back after a bump.
 */
object TaskDifficultyEscalator {
    fun next(
        current: TaskDifficultyTier,
        goal: String,
        page: PageContext,
        packagesSeen: Set<String>,
        consecutiveNoProgress: Int,
    ): TaskDifficultyTier {
        if (current == TaskDifficultyTier.HARD) return current
        val apps = userApps(packagesSeen)
        if (apps.size >= 2) return TaskDifficultyTier.HARD
        if (consecutiveNoProgress >= 2 && TaskDifficulty.namedAppCount(goal) >= 2) {
            return TaskDifficultyTier.HARD
        }
        if (current == TaskDifficultyTier.EASY && leftoverNeedsScene(goal, page)) {
            return TaskDifficultyTier.MEDIUM
        }
        return current
    }

    fun leftoverNeedsScene(goal: String, page: PageContext): Boolean {
        if (!TaskDifficulty.isEasy(goal)) return true
        return TaskTrajectory.looksLikeLoginWall(page) && TaskDifficulty.hasAuthenticatedSession(goal)
    }

    fun userApps(packagesSeen: Set<String>): Set<String> = packagesSeen.filterNot { packageName ->
        val lower = packageName.lowercase()
        lower.contains("launcher") ||
            lower.startsWith("com.cyclone.") ||
            lower == "com.android.systemui" ||
            lower.contains("inputmethod")
    }.toSet()
}
