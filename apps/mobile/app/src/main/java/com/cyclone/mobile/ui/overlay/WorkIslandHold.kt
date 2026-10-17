package com.cyclone.mobile.ui.overlay

/**
 * The folded work bar after its task ends. If the bar was showing when the task finished, it stays as a finished bar
 * (done, couldn't finish, or stopped) instead of folding back into the Ask bar. It goes when the owner acts: opens the
 * stack, closes it, starts typing, or a new task starts working. Pure: [step] is fed each frame's inputs and is
 * idempotent for the same inputs.
 */
class WorkIslandHold {
    enum class End { DONE, FAILED, STOPPED }

    /** A finished bar: whose task it was, how it ended, and the title it carried while working. */
    data class Finished(val key: String, val end: End, val title: String)

    var held: Finished? = null
        private set
    private var wasWorkingBar = false
    private var lastKey: String? = null
    private var lastTitle: String = ""

    /**
     * One frame: [minimized] (the stack is folded), [working] (a task works), [key] and [title] of the task, how it
     * [end]ed (when not working), whether the owner is [typing], and the key the owner [dismissed] (closed) last.
     * Returns the finished bar to show, or null.
     */
    fun step(minimized: Boolean, working: Boolean, key: String?, title: String?, end: End, typing: Boolean, dismissed: String?): Finished? {
        if (working) {
            // A working task shows its own bar; remember what it was, in case it ends while folded.
            held = null
            wasWorkingBar = minimized
            lastKey = key
            title?.takeIf { it.isNotBlank() }?.let { lastTitle = it }
            return null
        }
        if (wasWorkingBar && held == null) {
            val k = lastKey ?: key
            if (k != null && k != dismissed) held = Finished(k, end, lastTitle.ifBlank { title.orEmpty() })
        }
        wasWorkingBar = false
        val h = held
        if (h != null && (!minimized || typing || h.key == dismissed)) held = null
        return held
    }

    companion object {
        /** The finished bar's two lines: how it ended, and the task's title. */
        fun lines(finished: Finished): Pair<String, String> = when (finished.end) {
            End.DONE -> "Done"
            End.FAILED -> "Couldn't finish"
            End.STOPPED -> "Stopped"
        } to finished.title.ifBlank { "Cyclone" }
    }
}
