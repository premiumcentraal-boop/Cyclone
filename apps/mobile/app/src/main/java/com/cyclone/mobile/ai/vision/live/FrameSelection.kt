package com.cyclone.mobile.ai.vision.live

/** A frame ID alone cannot prove post-action capture: a decoder may publish a queued old image. */
data class ActionFrameBoundary(
    val sessionId: String,
    val displayId: Int,
    val lastFrameId: Long,
    val mutationFinishedAtMonotonicMs: Long,
)

object FrameSelection {
    fun eligible(
        frame: LiveFrame,
        sessionId: String,
        displayId: Int,
        nowMonotonicMs: Long,
        maxAgeMs: Long,
        after: ActionFrameBoundary? = null,
    ): Boolean {
        require(maxAgeMs >= 0)
        if (frame.sessionId != sessionId || frame.displayId != displayId) return false
        val age = nowMonotonicMs - frame.capturedAtMonotonicMs
        if (age < 0 || age > maxAgeMs) return false
        if (after == null) return true
        return after.sessionId == sessionId && after.displayId == displayId &&
            frame.frameId > after.lastFrameId &&
            frame.capturedAtMonotonicMs > after.mutationFinishedAtMonotonicMs
    }
}
