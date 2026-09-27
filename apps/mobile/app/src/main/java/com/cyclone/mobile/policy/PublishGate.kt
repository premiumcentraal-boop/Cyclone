package com.cyclone.mobile.policy

/**
 * Plan 33 (C3): while a Command Center task posts a file the owner's PC made, its final "Share" / "Post" /
 * "Publish" / "Upload" tap is a SEND gate, so it waits for the owner's OK like any message. Apps name that button
 * differently ("Share" on Instagram), and a plain "Share" is not a send elsewhere, so this is on only for that mission.
 */
object PublishGate {
    @Volatile var missionId: String? = null
    /** The running mission's id; the Command Center adapter installs it. */
    @Volatile var liveMission: () -> String? = { null }
    private val LABELS = setOf("share", "share now", "post", "post now", "publish", "publish now", "upload", "share post", "share reel", "share video")

    fun active(): Boolean = missionId != null && missionId == liveMission()

    fun gates(label: String): Boolean {
        val normalized = label.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!')
        return normalized in LABELS && active()
    }
}
