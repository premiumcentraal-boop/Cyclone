package com.cyclone.mobile.ui.overlay

/**
 * Plan 27: the apps a task has worked in, in the order it last visited them, so the card can show them: the header's
 * stack shows the last three, the expanded strip all of them. Only the display remembers this (never stored); the
 * last [LIMIT] distinct apps, the current one last (a revisited app moves to the end).
 */
object TaskAppTrail {
    const val LIMIT = 12
    private val trails = LinkedHashMap<String, List<String>>()

    @Synchronized
    fun record(taskId: String, packageName: String?): List<String> {
        val current = trails[taskId].orEmpty()
        if (packageName.isNullOrBlank() || packageName == "com.cyclone.mobile") return current
        val next = (current.filter { it != packageName } + packageName).takeLast(LIMIT)
        trails[taskId] = next
        while (trails.size > 8) trails.remove(trails.keys.first())
        return next
    }

    @Synchronized
    fun of(taskId: String): List<String> = trails[taskId].orEmpty()
}
