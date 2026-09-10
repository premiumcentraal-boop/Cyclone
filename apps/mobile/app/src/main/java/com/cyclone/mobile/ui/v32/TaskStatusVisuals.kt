package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

enum class CycloneTaskVisualState { WORKING, ACTION_NEEDED, DONE }

fun WorkspaceTaskUi.taskVisualState(): CycloneTaskVisualState {
    if (confirmation != null) return CycloneTaskVisualState.ACTION_NEEDED
    return when (phase) {
        TaskPhase.STARTING, TaskPhase.WORKING -> CycloneTaskVisualState.WORKING
        TaskPhase.DONE -> CycloneTaskVisualState.DONE
        TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN, TaskPhase.FAILED -> CycloneTaskVisualState.ACTION_NEEDED
        TaskPhase.STOPPED -> CycloneTaskVisualState.ACTION_NEEDED
    }
}

fun WorkspaceTaskUi.canTakeOverFromUi(): Boolean =
    sessionId != null && phase !in setOf(TaskPhase.HUMAN, TaskPhase.DONE, TaskPhase.STOPPED)

fun WorkspaceTaskUi.canContinueAfterHumanFromUi(): Boolean =
    resumable && confirmation == null && phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW)

@Composable
fun CycloneTaskStatusPill(
    state: CycloneTaskVisualState,
    modifier: Modifier = Modifier,
) {
    val container = when (state) {
        CycloneTaskVisualState.WORKING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .60f)
        CycloneTaskVisualState.ACTION_NEEDED -> MaterialTheme.colorScheme.tertiaryContainer
        CycloneTaskVisualState.DONE -> MaterialTheme.colorScheme.secondary.copy(alpha = .12f)
    }
    val content = when (state) {
        CycloneTaskVisualState.WORKING,
        CycloneTaskVisualState.ACTION_NEEDED -> MaterialTheme.colorScheme.onTertiaryContainer
        CycloneTaskVisualState.DONE -> MaterialTheme.colorScheme.secondary
    }
    val label = when (state) {
        CycloneTaskVisualState.WORKING -> "Working"
        CycloneTaskVisualState.ACTION_NEEDED -> "Action needed"
        CycloneTaskVisualState.DONE -> "Done"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when (state) {
                CycloneTaskVisualState.WORKING -> CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.8.dp,
                    color = content,
                )
                CycloneTaskVisualState.ACTION_NEEDED -> Text(
                    "✦",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                CycloneTaskVisualState.DONE -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = content,
                )
            }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}
