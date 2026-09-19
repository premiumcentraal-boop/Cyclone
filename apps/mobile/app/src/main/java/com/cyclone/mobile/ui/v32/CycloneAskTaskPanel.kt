package com.cyclone.mobile.ui.v32

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks

/**
 * Ask Cyclone's single task-status surface.
 *
 * The card mirrors the grounded runtime with three consumer states only: Working, Action needed,
 * and Done. Execution details stay in diagnostics; the card shows semantic work and truthful
 * human-control actions.
 */
@Composable
fun CycloneAskTaskPanel(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val resolvedApp = remember(task.packageName) { appLabel(context, task.packageName) }
    val presentation = TaskGlassPresentation.current(task, resolvedApp) ?: return
    val visualState = task.taskVisualState()
    var progressExpanded by rememberSaveable(task.taskId) { mutableStateOf(false) }

    // A task/result card is the primary interaction surface. Do not leave a stale editor/IME
    // competing for half the display when execution starts, fails, pauses, or finishes.
    LaunchedEffect(task.taskId, task.phase) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        if (visualState != CycloneTaskVisualState.WORKING) progressExpanded = false
    }

    val container = when (visualState) {
        CycloneTaskVisualState.WORKING -> MaterialTheme.colorScheme.surface.copy(alpha = .98f)
        CycloneTaskVisualState.ACTION_NEEDED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = .32f)
        CycloneTaskVisualState.DONE -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .30f)
    }
    val outline = when (visualState) {
        CycloneTaskVisualState.WORKING -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = .62f)
        CycloneTaskVisualState.ACTION_NEEDED -> MaterialTheme.colorScheme.error.copy(alpha = .24f)
        CycloneTaskVisualState.DONE -> MaterialTheme.colorScheme.secondary.copy(alpha = .24f)
    }

    CycloneSwipeTaskCard("task:${task.taskId}", canClear = !UiTask(task).active, onOpen = { UiTask(task).open(context) }) {
        Surface(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            shape = RoundedCornerShape(24.dp),
            color = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(.8.dp, outline),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CycloneAppIcon(presentation.packageName, Modifier.size(24.dp))
                    Text(
                        resolvedApp.takeIf { it.isNotBlank() && it != "Other" } ?: "Cyclone",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CycloneTaskStatusPill(visualState)
                }

                Text(
                    presentation.taskLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                AnimatedContent(
                    targetState = visualState,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "Cyclone task state",
                ) { state ->
                    when (state) {
                        CycloneTaskVisualState.WORKING -> WorkingBody(
                            task = task,
                            expanded = progressExpanded,
                            onToggleExpanded = { progressExpanded = !progressExpanded },
                            onFullDetails = { UiTask(task).open(context) },
                        )
                        CycloneTaskVisualState.ACTION_NEEDED -> ActionNeededBody(
                            task = task,
                            onTakeOver = {
                                WorkspaceTasks.command(context, task, "handoff")
                            },
                            onDone = {
                                WorkspaceTasks.command(context, task, "resume")
                            },
                            onProgress = { UiTask(task).open(context) },
                        )
                        CycloneTaskVisualState.DONE -> DoneBody(task) {
                            UiTask(task).open(context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkingBody(
    task: WorkspaceTaskUi,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onFullDetails: () -> Unit,
) {
    val total = task.semanticSteps.size
    val completed = task.semanticSteps.count { it.state == SemanticStepState.DONE }
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LinearProgressIndicator(
                modifier = Modifier.weight(1f).heightIn(min = 3.dp).clip(RoundedCornerShape(999.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            if (total > 0) {
                Text(
                    "$completed of $total complete",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (expanded) {
            CycloneTaskCheckpoints(task)
        } else {
            val currentStep = task.subtitle.trim()
            if (currentStep.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CycloneNineDotSpinner()
                    Text(
                        currentStep,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onToggleExpanded,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) { Text(if (expanded) "Show less" else "View progress") }
            if (expanded) {
                TextButton(
                    onClick = onFullDetails,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("Details") }
            }
        }
    }
}

@Composable
private fun ActionNeededBody(
    task: WorkspaceTaskUi,
    onTakeOver: () -> Unit,
    onDone: () -> Unit,
    onProgress: () -> Unit,
) {
    val prompt = task.interruption?.prompt ?: when {
        task.confirmation != null -> task.confirmation.explanation
        task.phase == TaskPhase.HUMAN -> "Finish this step in ${task.app}, then tell Cyclone when you're done."
        task.phase == TaskPhase.PAUSED -> "Your place is saved. Take over or continue when you're ready."
        task.phase == TaskPhase.FAILED -> task.subtitle
        else -> task.subtitle
    }.trim()
    val canTakeOver = task.canTakeOverFromUi()
    val canContinue = task.canContinueAfterHumanFromUi()

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        if (prompt.isNotBlank()) {
            Text(
                prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Only show actions that are actually available in this exact runtime state. Dead/future
        // controls do not belong on the primary task surface.
        if (canTakeOver || canContinue) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (canTakeOver) {
                    Button(
                        onClick = onTakeOver,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp).semantics {
                            contentDescription = "Take over ${task.app}"
                        },
                        shape = RoundedCornerShape(15.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) { Text("Take Over") }
                }

                if (canContinue) {
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp).semantics {
                            contentDescription = "I'm done, continue with Cyclone"
                        },
                        shape = RoundedCornerShape(15.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    ) { Text("I'm Done") }
                }
            }
        }

        TextButton(
            onClick = onProgress,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(if (task.confirmation != null) "Review details" else "View progress") }
    }
}

@Composable
private fun DoneBody(task: WorkspaceTaskUi, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SupportingLine(task)
        CycloneTaskCheckpoints(task)
        Button(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("View Result") }
    }
}

@Composable
private fun SupportingLine(task: WorkspaceTaskUi) {
    val line = task.subtitle.trim().takeIf { it.isNotBlank() }
    if (line != null) {
        Text(
            line,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
