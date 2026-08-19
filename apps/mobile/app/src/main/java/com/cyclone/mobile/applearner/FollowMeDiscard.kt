package com.cyclone.mobile.applearner

import android.content.Context
import com.cyclone.mobile.guided.RoutineTeachingOverlayRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import java.io.File

/**
 * Cancels the active Follow Me teaching session without opening a report or running model analysis.
 *
 * The live App Graph remains observation-backed phone knowledge, but the temporary 2.9.1 teaching
 * session, its screenshots and its Obsidian teaching-history mirror are discarded. No workflow is
 * compiled and no post-session OpenRouter analysis is started.
 */
fun discardFollowMeSession(context: Context) {
    val app = context.applicationContext
    val sessionId = RoutineTeachingRuntime.activeSessionId()

    // Cancel any explicit guided placement first so it cannot compile into an Automation.
    RoutineTeachingOverlayRuntime.dismiss()

    // Clear RoutineTeachingRuntime's active pointer without going through Follow Me's review path.
    if (sessionId != null) {
        RoutineTeachingRuntime.finish(
            appsSeen = 0,
            pagesSeen = 0,
            actionsSeen = 0,
            pathsLearned = 0,
        )
        File(app.filesDir, "cyclone-teaching-sessions/$sessionId").deleteRecursively()
        val suffix = "-${sessionId.take(8)}"
        File(app.filesDir, "Cyclone Brain/Routine Teachings")
            .listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.endsWith(suffix) }
            .forEach(File::deleteRecursively)
    }

    // With the teaching session already cleared, stop() restores controller ownership but cannot
    // create a report or schedule RoutineTeachingAnalyzer.
    FollowMeLearnerRuntime.stop()
}
