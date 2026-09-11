package com.cyclone.mobile.ui.v32

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val resolvedApp = remember(task.packageName) { appLabel(context, task.packageName) }
    val presentation = TaskGlassPresentation.current(task, resolvedApp) ?: return
    val visualState = task.taskVisualState()

    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
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
                    CycloneTaskVisualState.WORKING -> WorkingBody(task) {
                        UiTask(task).open(context)
                    }
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

@Composable
private fun WorkingBody(task: WorkspaceTaskUi, onProgress: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SupportingLine(task)
        val recent = task.steps.distinct().takeLast(2)
        if (recent.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recent.forEach { step ->
                    Text(
                        step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TextButton(
            onClick = onProgress,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
        ) { Text("View progress") }
    }
}

@Composable
private fun ActionNeededBody(
    task: WorkspaceTaskUi,
    onTakeOver: () -> Unit,
    onDone: () -> Unit,
    onProgress: () -> Unit,
) {
    val prompt = when {
        task.confirmation != null -> task.confirmation.explanation
        task.phase == TaskPhase.HUMAN -> "Finish this step in ${task.app}, then tell Cyclone when you're done."
        task.phase == TaskPhase.PAUSED -> "Your place is saved. Take over or continue when you're ready."
        task.phase == TaskPhase.FAILED -> task.subtitle
        else -> task.subtitle
    }.trim()

    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        if (prompt.isNotBlank()) {
            Text(
                prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = onTakeOver,
                enabled = task.canTakeOverFromUi(),
                modifier = Modifier.weight(1f).heightIn(min = 46.dp).semantics {
                    contentDescription = "Take over ${task.app}"
                },
                shape = RoundedCornerShape(15.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) { Text(if (task.phase == TaskPhase.HUMAN) "In control" else "Take Over") }

            OutlinedButton(
                onClick = onDone,
                enabled = task.canContinueAfterHumanFromUi(),
                modifier = Modifier.weight(1f).heightIn(min = 46.dp).semantics {
                    contentDescription = "I'm done, continue with Cyclone"
                },
                shape = RoundedCornerShape(15.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) { Text("I'm Done") }
        }

        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            shape = RoundedCornerShape(15.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Autofill")
                Text("Soon", style = MaterialTheme.typography.labelSmall)
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
        val completed = task.steps.distinct().takeLast(3)
        if (completed.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                completed.forEach { step ->
                    Text(
                        "✓  $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
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
