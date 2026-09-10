package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
 * It intentionally shows semantic task state only: Working, Done, or Action Needed. Raw gestures,
 * coordinates, model traces and transport details remain in execution logs rather than leaking into
 * the user-facing progress surface.
 */
@Composable
fun CycloneAskTaskPanel(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val resolvedApp = remember(task.packageName) { appLabel(context, task.packageName) }
    val presentation = TaskGlassPresentation.current(task, resolvedApp) ?: return
    val actionNeeded = task.interruption != null
    val canResume = task.interruption?.canResumeAfterHuman == true

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TaskStatusMark(task.phase, actionNeeded)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        presentation.status,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        presentation.taskLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    CycloneAppIcon(presentation.packageName, Modifier.padding(5.dp).size(30.dp))
                }
            }

            val semanticLine = task.subtitle.trim().takeIf { it.isNotBlank() && it != presentation.taskLabel }
            if (semanticLine != null) {
                Text(
                    semanticLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when {
                task.phase == TaskPhase.DONE -> DoneActions(task)
                actionNeeded -> ActionNeededActions(
                    task = task,
                    canResume = canResume,
                    onTakeOver = { WorkspaceTasks.command(context, task, "handoff") },
                    onDone = { WorkspaceTasks.command(context, task, "resume") },
                    onProgress = { UiTask(task).open(context) },
                )
                else -> WorkingActions(task, onProgress = { UiTask(task).open(context) })
            }
        }
    }
}

@Composable
private fun TaskStatusMark(phase: TaskPhase, actionNeeded: Boolean) {
    when {
        phase == TaskPhase.DONE -> {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(
                        Icons.Rounded.Check,
                        "Done",
                        Modifier.size(20.dp),
                        tint = CycloneColors.Success,
                    )
                }
            }
        }
        actionNeeded || phase == TaskPhase.FAILED || phase == TaskPhase.STOPPED -> {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        else -> {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.size(24.dp).semantics { contentDescription = "Working" },
                    strokeWidth = 2.5.dp,
                )
            }
        }
    }
}

@Composable
private fun WorkingActions(task: WorkspaceTaskUi, onProgress: () -> Unit) {
    if (task.steps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            task.steps.distinct().takeLast(3).forEach { step ->
                Text(
                    "✓  $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(
        onClick = onProgress,
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text("View Progress") }
}

@Composable
private fun DoneActions(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    if (task.steps.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            task.steps.distinct().takeLast(3).forEach { step ->
                Text(
                    "✓  $step",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Button(
        onClick = { UiTask(task).open(context) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text("View Result") }
}

@Composable
private fun ActionNeededActions(
    task: WorkspaceTaskUi,
    canResume: Boolean,
    onTakeOver: () -> Unit,
    onDone: () -> Unit,
    onProgress: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Button(
                onClick = onTakeOver,
                enabled = task.interruption?.canTakeOver == true,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(15.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) { Text("Take Over") }
            OutlinedButton(
                onClick = onDone,
                enabled = canResume,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                shape = RoundedCornerShape(15.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) { Text("I'm Done") }
        }
        OutlinedButton(
            onClick = {},
            enabled = task.interruption?.canAutofill == true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            shape = RoundedCornerShape(15.dp),
        ) { Text("Autofill · Soon") }
        androidx.compose.material3.TextButton(
            onClick = onProgress,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("View Progress") }
    }
}
