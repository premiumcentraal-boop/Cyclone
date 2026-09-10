package com.cyclone.mobile.runtime.background

/** Consumer progress is a projection of harness evidence, never a second executor. */
enum class SemanticStepState { PENDING, ACTIVE, DONE, ACTION_NEEDED, FAILED }
data class SemanticTaskStep(val id: Long, val label: String, val state: SemanticStepState,
    val evidence: String? = null)
data class TaskInterruption(val reason: String, val prompt: String,
    val canTakeOver: Boolean = false, val canResumeAfterHuman: Boolean = false,
    val canAutofill: Boolean = false, val confirmationToken: String? = null)
data class TaskOperationEvidence(val sessionId: String, val displayId: Int,
    val controlRevision: Long, val executionAccepted: Boolean, val verified: Boolean,
    val freshObservation: Boolean, val classification: String?,
    val workspaceId: String? = null, val workspaceGeneration: Long? = null)

object TaskHarnessState {
    /** Values from action parameters, provider summaries, page text and credentials never enter this copy. */
    fun operationLabel(tool: String, app: String): String {
        val name = app.takeIf { it.length in 1..45 && it.matches(Regex("[\\p{L}][\\p{L} .'-]*")) } ?: "your app"
        return when (tool) {
            "phone.open_app" -> "Opening $name"
            "phone.back" -> "Going back in $name"
            "phone.home" -> "Opening the home screen"
            "phone.scroll", "phone.swipe" -> "Browsing $name"
            "phone.type", "phone.replace_text" -> "Filling in the requested field"
            "compiled_skill" -> "Following your app routine"
            else -> "Navigating $name"
        }
    }

    fun begin(task: WorkspaceTaskUi, tool: String): WorkspaceTaskUi {
        if (!task.working) return task
        val label = operationLabel(tool, task.app)
        val steps = task.semanticSteps.map { if (it.state == SemanticStepState.ACTIVE)
            it.copy(state = SemanticStepState.FAILED, evidence = "UNVERIFIED") else it }
        return task.copy(message = label, semanticSteps = (steps + SemanticTaskStep(
            (steps.lastOrNull()?.id ?: 0) + 1, label, SemanticStepState.ACTIVE)).takeLast(40))
    }

    fun finish(task: WorkspaceTaskUi, evidence: TaskOperationEvidence): WorkspaceTaskUi {
        if (!task.working || task.controlRevision != evidence.controlRevision ||
            (task.sessionId ?: "default-foreground") != evidence.sessionId ||
            (task.displayId ?: 0) != evidence.displayId || task.workspaceId != evidence.workspaceId ||
            task.workspaceGeneration != evidence.workspaceGeneration) return task
        val last = task.semanticSteps.lastOrNull()?.takeIf { it.state == SemanticStepState.ACTIVE } ?: return task
        val done = evidence.executionAccepted && evidence.verified && evidence.freshObservation
        // Diagnostic classes are bounded; never copy failure messages or arbitrary evidence values.
        val basis = evidence.classification?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) }
        val steps = task.semanticSteps.dropLast(1) + last.copy(
            state = if (done) SemanticStepState.DONE else SemanticStepState.FAILED, evidence = basis)
        return task.copy(semanticSteps = steps, steps = steps.filter { it.state == SemanticStepState.DONE }.map { it.label },
            message = if (done) "Checking the current page" else "Checking what changed before continuing")
    }

    fun interruption(task: WorkspaceTaskUi): TaskInterruption? {
        val bound = !task.sessionId.isNullOrBlank() && task.displayId != null
        return when (task.phase) {
            TaskPhase.HUMAN -> TaskInterruption("HUMAN_CONTROL", "Finish your changes, then select I'm Done.",
                canResumeAfterHuman = bound && task.resumable)
            TaskPhase.PAUSED -> TaskInterruption("PAUSED", "Your task is paused.",
                canTakeOver = bound, canResumeAfterHuman = bound && task.resumable && task.confirmation == null)
            TaskPhase.REVIEW -> TaskInterruption(if (task.confirmation == null) "HUMAN_REVIEW" else "CONFIRMATION",
                task.confirmation?.explanation ?: "Review the current page to continue.", canTakeOver = bound,
                canResumeAfterHuman = false, confirmationToken = task.confirmation?.token)
            else -> null
        }
    }

    /** Called centrally after backend transitions, including stale callback rejection. */
    fun normalize(previous: WorkspaceTaskUi, next: WorkspaceTaskUi): WorkspaceTaskUi {
        val interrupted = previous.working && !next.working && next.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW)
        val revision = if (interrupted) previous.controlRevision + 1 else next.controlRevision
        val steps = if (interrupted) next.semanticSteps.map {
            if (it.state == SemanticStepState.ACTIVE) it.copy(state = SemanticStepState.ACTION_NEEDED) else it
        } else next.semanticSteps
        return next.copy(controlRevision = revision, semanticSteps = steps, interruption = interruption(next))
    }

    fun category(task: WorkspaceTaskUi): String = when (task.phase) {
        TaskPhase.STARTING, TaskPhase.WORKING -> "Working"
        TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW -> "Action Needed"
        TaskPhase.DONE -> "Done"
        TaskPhase.FAILED -> "Failed"
        TaskPhase.STOPPED -> "Stopped"
    }
}
