package com.cyclone.mobile.runtime.background

/**
 * Consumer-facing projection of a task.
 *
 * This is deliberately read-only: it never executes tools, changes controller ownership, or
 * manufactures verification. Every field is derived from the authoritative WorkspaceTaskUi /
 * TaskHarnessState evidence already owned by the runtime.
 */
enum class TaskConsumerState { WORKING, ACTION_NEEDED, DONE, FAILED }

data class TaskPresentationMilestone(
    val label: String,
    val state: SemanticStepState,
)

/**
 * Consumer compaction only. The authoritative semanticSteps list stays untouched for diagnostics.
 * Adjacent operations with the same safe label are folded into one visual milestone so repeated
 * verified taps/scrolls do not turn the chat card into an execution log.
 */
object TaskMilestoneProjector {
    fun project(steps: List<SemanticTaskStep>): List<TaskPresentationMilestone> {
        val out = mutableListOf<TaskPresentationMilestone>()
        steps.forEach { step ->
            val label = step.label.trim().take(90)
            if (label.isBlank()) return@forEach
            val previous = out.lastOrNull()
            if (previous != null && previous.label.equals(label, ignoreCase = true)) {
                out[out.lastIndex] = TaskPresentationMilestone(label, merge(previous.state, step.state))
            } else {
                out += TaskPresentationMilestone(label, step.state)
            }
        }
        return out.takeLast(8)
    }

    private fun merge(previous: SemanticStepState, next: SemanticStepState): SemanticStepState = when {
        next == SemanticStepState.ACTION_NEEDED -> next
        next == SemanticStepState.FAILED -> next
        next == SemanticStepState.ACTIVE -> next
        next == SemanticStepState.DONE -> SemanticStepState.DONE
        previous == SemanticStepState.DONE -> previous
        else -> next
    }
}

enum class TaskFollowUpAction {
    VIEW_DETAILS,
    RUN_AGAIN,
    TRY_AGAIN,
    OPEN_APP,
    TAKE_OVER,
    AUTOFILL,
    CONTINUE,
}

data class TaskPresentationSnapshot(
    val taskId: String,
    val app: String,
    val packageName: String,
    val title: String,
    val state: TaskConsumerState,
    val currentMilestone: String?,
    val milestones: List<TaskPresentationMilestone>,
    val completedMilestones: List<String>,
    val completedCount: Int,
    val totalCount: Int?,
    /** Null means the runtime does not yet know a stable denominator. */
    val progressFraction: Float?,
    val supportingCopy: String?,
    val outcomeCopy: String?,
    val followUps: List<TaskFollowUpAction>,
)

object TaskPresentationProjector {
    fun project(task: WorkspaceTaskUi): TaskPresentationSnapshot {
        val state = when (task.phase) {
            TaskPhase.STARTING, TaskPhase.WORKING -> TaskConsumerState.WORKING
            TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN -> TaskConsumerState.ACTION_NEEDED
            TaskPhase.DONE -> TaskConsumerState.DONE
            TaskPhase.FAILED, TaskPhase.STOPPED -> TaskConsumerState.FAILED
        }

        val semantic = task.semanticSteps
        val milestones = TaskMilestoneProjector.project(semantic).filter { milestone ->
            // Harness FAILED can mean an operation was superseded without fresh verification.
            // Keep that diagnostic evidence in semanticSteps, but do not paint a red consumer
            // milestone unless the task itself is terminally failed.
            milestone.state != SemanticStepState.FAILED || state == TaskConsumerState.FAILED
        }
        val verifiedOperations = milestones.filter { it.state == SemanticStepState.DONE }
        val activeOperation = milestones.lastOrNull {
            it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED
        }

        val planned = task.plannedMilestones.filter(String::isNotBlank).take(8)
        val rawPlanIndex = when (state) {
            TaskConsumerState.DONE -> planned.size
            else -> task.plannedMilestoneIndex.coerceIn(0, planned.size)
        }
        // If a task stops after the trajectory cursor reached the end, keep the final milestone as
        // the interruption/failure point instead of visually claiming every planned step completed.
        val planIndex = when {
            state == TaskConsumerState.DONE -> planned.size
            planned.isNotEmpty() && rawPlanIndex >= planned.size -> planned.lastIndex
            else -> rawPlanIndex
        }
        val presentationMilestones = if (planned.isNotEmpty()) {
            planned.mapIndexed { index, label ->
                val milestoneState = when {
                    state == TaskConsumerState.DONE -> SemanticStepState.DONE
                    index < planIndex -> SemanticStepState.DONE
                    index > planIndex -> SemanticStepState.PENDING
                    state == TaskConsumerState.ACTION_NEEDED -> SemanticStepState.ACTION_NEEDED
                    state == TaskConsumerState.FAILED -> SemanticStepState.FAILED
                    else -> SemanticStepState.ACTIVE
                }
                TaskPresentationMilestone(label, milestoneState)
            }
        } else {
            milestones
        }
        val total = planned.size.takeIf { it > 0 }
        val completedCount = presentationMilestones.count { it.state == SemanticStepState.DONE }
        val fraction = total?.let { denominator ->
            (completedCount.toFloat() / denominator.toFloat()).coerceIn(0f, 1f)
        } ?: if (state == TaskConsumerState.DONE) 1f else null

        val currentMilestone = activeOperation?.label?.takeIf(String::isNotBlank)
            ?: presentationMilestones.firstOrNull {
                it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED
            }?.label
            ?: task.subtitle.takeIf(String::isNotBlank)

        val supportingCopy = when (state) {
            TaskConsumerState.WORKING -> when {
                total != null -> "$completedCount of $total complete"
                verifiedOperations.isNotEmpty() ->
                    "${verifiedOperations.size} verified step${if (verifiedOperations.size == 1) "" else "s"} complete"
                else -> currentMilestone
            }
            TaskConsumerState.ACTION_NEEDED -> task.interruption?.prompt?.takeIf(String::isNotBlank)
                ?: task.confirmation?.explanation?.takeIf(String::isNotBlank)
                ?: task.subtitle.takeIf(String::isNotBlank)
            TaskConsumerState.DONE -> task.outcome?.takeIf(String::isNotBlank)
                ?: "The requested result was checked."
            TaskConsumerState.FAILED -> task.outcome?.takeIf(String::isNotBlank)
                ?: task.subtitle.takeIf(String::isNotBlank)
        }

        return TaskPresentationSnapshot(
            taskId = task.taskId,
            app = task.app,
            packageName = task.packageName,
            title = consumerTaskTitle(task),
            state = state,
            currentMilestone = currentMilestone,
            milestones = presentationMilestones,
            completedMilestones = presentationMilestones
                .filter { it.state == SemanticStepState.DONE }
                .map { it.label }
                .takeLast(4),
            completedCount = completedCount,
            totalCount = total,
            progressFraction = fraction,
            supportingCopy = supportingCopy,
            outcomeCopy = task.outcome?.takeIf(String::isNotBlank),
            followUps = TaskFollowUpPolicy.actions(task, state),
        )
    }

    private fun consumerTaskTitle(task: WorkspaceTaskUi): String {
        val clean = task.goal.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return task.app.ifBlank { "Phone task" }
        val app = task.app.takeIf { it.isNotBlank() && !it.equals("Other", true) }
        val lower = clean.lowercase()
        return when {
            "battery" in lower && ("setting" in lower || app.equals("Settings", true)) ->
                "Checking battery settings"
            Regex("\\b(logged? ?in|log ?in|login|signed? ?in|sign ?in)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(clean) -> "Checking ${app ?: "app"} login status"
            app != null && Regex("\\b(open|launch|start)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Opening $app"
            app != null && Regex("\\b(check|verify|see|review)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Checking $app"
            app != null && Regex("\\b(find|search|look for)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ->
                "Searching $app"
            app != null -> "Task in $app"
            else -> "Phone task"
        }
    }
}

object TaskFollowUpPolicy {
    fun actions(task: WorkspaceTaskUi, state: TaskConsumerState): List<TaskFollowUpAction> = buildList {
        val interactiveSession = !task.sessionId.isNullOrBlank() && task.displayId != null
        when (state) {
            TaskConsumerState.WORKING -> add(TaskFollowUpAction.VIEW_DETAILS)
            TaskConsumerState.ACTION_NEEDED -> {
                val interruption = task.interruption
                val resumableHumanBoundary =
                    interactiveSession &&
                        task.resumable &&
                        task.confirmation == null &&
                        task.phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW)

                if (resumableHumanBoundary && interruption?.canAutofill == true) {
                    add(TaskFollowUpAction.AUTOFILL)
                }
                if (
                    interactiveSession &&
                    interruption?.canTakeOver == true &&
                    task.phase !in setOf(TaskPhase.HUMAN, TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
                ) {
                    add(TaskFollowUpAction.TAKE_OVER)
                }
                if (resumableHumanBoundary && interruption?.canResumeAfterHuman == true) {
                    add(TaskFollowUpAction.CONTINUE)
                }
                add(TaskFollowUpAction.VIEW_DETAILS)
            }
            TaskConsumerState.DONE -> {
                add(TaskFollowUpAction.VIEW_DETAILS)
                if (task.packageName.isNotBlank()) add(TaskFollowUpAction.OPEN_APP)
                add(TaskFollowUpAction.RUN_AGAIN)
            }
            TaskConsumerState.FAILED -> {
                add(TaskFollowUpAction.TRY_AGAIN)
                add(TaskFollowUpAction.VIEW_DETAILS)
            }
        }
    }
}
