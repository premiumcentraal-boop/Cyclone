package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A task is a physical object: drag right to open it, drag left to pause/stop/dismiss it.
 * The foreground card tracks the finger 1:1; threshold actions are announced by one haptic tick.
 */
@Composable
fun CycloneAskTaskPanel(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val resolvedApp = remember(task.packageName) { appLabel(context, task.packageName) }
    val presentation = TaskGlassPresentation.current(task, resolvedApp) ?: return
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val terminal = task.phase == TaskPhase.DONE || task.phase == TaskPhase.FAILED
    var hidden by remember(task.taskId) { mutableStateOf(false) }
    var widthPx by remember(task.taskId) { mutableIntStateOf(1) }
    var offsetPx by remember(task.taskId) { mutableFloatStateOf(0f) }
    var thresholdBuzzed by remember(task.taskId) { mutableStateOf(false) }
    if (hidden) return

    val minimumThreshold = with(density) { 78.dp.toPx() }
    fun threshold() = max(minimumThreshold, widthPx * .29f)
    fun leftLabel(): String = when {
        terminal -> "Dismiss"
        task.working -> "Pause"
        else -> "Stop"
    }
    fun leftAction() {
        when {
            task.phase == TaskPhase.DONE -> WorkspaceTasks.command(context, task, "cancel")
            task.phase == TaskPhase.FAILED -> Unit
            task.working -> WorkspaceTasks.command(context, task, "pause")
            else -> WorkspaceTasks.command(context, task, "cancel")
        }
    }
    fun openAction() = UiTask(task).open(context)

    val dragState = rememberDraggableState { delta ->
        val limit = widthPx * .86f
        offsetPx = (offsetPx + delta).coerceIn(-limit, limit)
        val crossed = abs(offsetPx) >= threshold()
        if (crossed && !thresholdBuzzed) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            thresholdBuzzed = true
        } else if (!crossed && thresholdBuzzed) {
            thresholdBuzzed = false
        }
    }

    val shape = RoundedCornerShape(if (terminal) 22.dp else 28.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) },
    ) {
        TaskSwipeBackground(offsetPx = offsetPx, leftLabel = leftLabel(), terminal = terminal)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    onDragStarted = { thresholdBuzzed = false },
                    onDragStopped = { velocity ->
                        val commitThreshold = threshold()
                        val commitLeft = offsetPx <= -commitThreshold || velocity < -2200f
                        val commitRight = offsetPx >= commitThreshold || velocity > 2200f
                        scope.launch {
                            when {
                                commitLeft && terminal -> {
                                    animate(
                                        initialValue = offsetPx,
                                        targetValue = -widthPx.toFloat(),
                                        animationSpec = spring(dampingRatio = .92f, stiffness = 720f),
                                    ) { value, _ -> offsetPx = value }
                                    leftAction()
                                    hidden = true
                                }
                                commitLeft -> {
                                    leftAction()
                                    animate(
                                        initialValue = offsetPx,
                                        targetValue = 0f,
                                        animationSpec = spring(dampingRatio = .88f, stiffness = 640f),
                                    ) { value, _ -> offsetPx = value }
                                }
                                commitRight -> {
                                    openAction()
                                    animate(
                                        initialValue = offsetPx,
                                        targetValue = 0f,
                                        animationSpec = spring(dampingRatio = .9f, stiffness = 640f),
                                    ) { value, _ -> offsetPx = value }
                                }
                                else -> animate(
                                    initialValue = offsetPx,
                                    targetValue = 0f,
                                    animationSpec = spring(dampingRatio = .86f, stiffness = 620f),
                                ) { value, _ -> offsetPx = value }
                            }
                            thresholdBuzzed = false
                        }
                    },
                ),
            shape = shape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = if (terminal) .90f else .92f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = if (terminal) 2.dp else 5.dp,
        ) {
            if (terminal) {
                TerminalTaskContent(task, presentation, onOpen = ::openAction)
            } else {
                ActiveTaskContent(task, presentation, onOpen = ::openAction)
            }
        }
    }
}

@Composable
private fun TaskSwipeBackground(offsetPx: Float, leftLabel: String, terminal: Boolean) {
    val draggingRight = offsetPx > 0f
    val color = when {
        draggingRight -> MaterialTheme.colorScheme.primaryContainer
        terminal -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        draggingRight -> MaterialTheme.colorScheme.primary
        terminal -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color, contentColor = contentColor, modifier = Modifier.matchParentSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (draggingRight) Arrangement.Start else Arrangement.End,
        ) {
            if (draggingRight) {
                Icon(Icons.Rounded.ArrowForward, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open", style = MaterialTheme.typography.labelLarge)
            } else {
                Text(leftLabel, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (terminal) Icons.Rounded.Close else Icons.Rounded.Pause,
                    null,
                    Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TerminalTaskContent(
    task: WorkspaceTaskUi,
    presentation: TaskGlassCardModel,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            CycloneAppIcon(presentation.packageName, Modifier.padding(6.dp).size(32.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                presentation.status,
                style = MaterialTheme.typography.labelMedium,
                color = if (task.phase == TaskPhase.DONE) CycloneColors.Success else MaterialTheme.colorScheme.error,
            )
            Text(
                presentation.taskLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (task.phase == TaskPhase.FAILED) {
                Text(
                    task.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (task.phase == TaskPhase.DONE) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, "Completed", Modifier.size(18.dp), tint = CycloneColors.Success)
                }
            }
        } else {
            Button(
                onClick = onOpen,
                modifier = Modifier.heightIn(min = 38.dp),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp),
            ) { Text("Review", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun ActiveTaskContent(
    task: WorkspaceTaskUi,
    presentation: TaskGlassCardModel,
    onOpen: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                CycloneAppIcon(presentation.packageName, Modifier.padding(6.dp).size(34.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    presentation.status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    presentation.taskLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                task.subtitle.takeIf { it.isNotBlank() && it != presentation.taskLabel }?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (task.working) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .7f),
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpen,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                    contentDescription = presentation.actionContentDescription
                },
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(presentation.actionLabel, style = MaterialTheme.typography.labelLarge)
            }
            if (task.working) {
                FilledTonalIconButton(
                    onClick = { WorkspaceTasks.command(LocalContext.current, task, "pause") },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.Pause, "Pause task", Modifier.size(20.dp))
                }
            }
        }
    }
}
