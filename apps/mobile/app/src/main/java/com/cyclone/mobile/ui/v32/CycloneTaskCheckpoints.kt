package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import kotlin.math.cos
import kotlin.math.sin

internal data class CheckpointRow(val label: String, val state: SemanticStepState)

internal fun checkpointRows(task: WorkspaceTaskUi): List<CheckpointRow> {
    val rows = task.semanticSteps.takeLast(3).map { CheckpointRow(it.label, it.state) }
    val activeLabel = task.subtitle.trim()
    val alreadyRepresented = rows.any { row ->
        row.label.trim().equals(activeLabel, ignoreCase = true)
    }
    return if (
        task.working &&
        task.confirmation == null &&
        activeLabel.isNotBlank() &&
        rows.none { it.state == SemanticStepState.ACTIVE } &&
        !alreadyRepresented
    ) rows + CheckpointRow(activeLabel, SemanticStepState.ACTIVE)
    else rows
}

@Composable
fun CycloneNineDotSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Working dots")
    val rotation by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Dot rotation")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(16.dp).semantics { contentDescription = "Working" }) {
        val orbit = size.minDimension * .34f
        repeat(9) { dot ->
            val angle = Math.toRadians((rotation + dot * 40f).toDouble())
            drawCircle(color.copy(alpha = .2f + .8f * (dot + 1) / 9f), size.minDimension * .065f,
                Offset(center.x + cos(angle).toFloat() * orbit, center.y + sin(angle).toFloat() * orbit))
        }
    }
}

@Composable
fun CycloneTaskCheckpoints(task: WorkspaceTaskUi) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        checkpointRows(task).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.state == SemanticStepState.ACTIVE && task.working && task.confirmation == null) {
                    CycloneNineDotSpinner()
                } else Text(when(row.state) {
                    SemanticStepState.DONE -> "✓"
                    SemanticStepState.FAILED -> "–"
                    SemanticStepState.ACTION_NEEDED -> "✦"
                    else -> "○"
                }, modifier = Modifier.width(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.label, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
