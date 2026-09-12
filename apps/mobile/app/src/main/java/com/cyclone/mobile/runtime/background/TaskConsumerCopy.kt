package com.cyclone.mobile.runtime.background

/** Provider prose is never used as consumer status. */
object TaskConsumerCopy {
    fun subtitle(task: WorkspaceTaskUi): String {
        task.interruption?.let { return it.prompt }
        task.semanticSteps.lastOrNull()?.takeIf { it.state == SemanticStepState.ACTIVE }?.let { return it.label }
        return when (task.phase) {
            TaskPhase.STARTING -> "Getting your task ready"
            TaskPhase.WORKING -> "Checking the current page"
            TaskPhase.HUMAN -> "Finish your changes, then select I'm Done."
            TaskPhase.PAUSED -> "Your task is paused."
            TaskPhase.REVIEW -> "Review the current page to continue."
            TaskPhase.DONE -> "The requested result was checked."
            TaskPhase.FAILED -> "The task could not finish."
            TaskPhase.STOPPED -> "The task was stopped."
        }
    }
}
