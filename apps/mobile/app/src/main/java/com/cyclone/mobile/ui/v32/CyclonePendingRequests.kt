package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.PendingWorkspaceRequest
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceDestinationHint
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction

/**
 * FIFO presentation only: queued work has Steer + Stop and starts automatically when safely eligible.
 *
 * The queue is intentionally secondary UI. A current task/result owns the task surface, so queued
 * requests stay hidden until that surface is gone instead of stacking another card beneath it.
 */
@Composable
fun CyclonePendingRequests(onOpen: () -> Unit = {}) {
    val requests by WorkspaceTasks.requests.state.collectAsState()
    val currentTask by WorkspaceTasks.state.collectAsState()
    if (requests.isEmpty() || currentTask?.phase?.let { it != TaskPhase.STOPPED } == true) return
    val context = LocalContext.current
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var steering by remember { mutableStateOf<PendingWorkspaceRequest?>(null) }
    val visible = if (keyboardOpen) requests.take(KEYBOARD_VISIBLE_QUEUE_CARDS) else requests

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                requests.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visible.forEach { request ->
            QueuedTaskCard(
                request = request,
                compact = keyboardOpen,
                onSteer = { steering = if (steering?.id == request.id) null else request },
                onStop = {
                    if (steering?.id == request.id) steering = null
                    WorkspaceTasks.requests.remove(request.id)
                },
            )
            if (steering?.id == request.id) {
                SteerDestinationSheet(
                    request = request,
                    destinations = WorkspaceTasks.queueDestinations(context),
                    onSelected = { destination ->
                        WorkspaceTasks.requests.steer(request.id, destination)
                        if (destination.androidUserId == com.cyclone.mobile.runtime.workspaces.Layer2Workspaces.currentAndroidUserId() &&
                            WorkspaceTasks.resolveQueueTarget(context, request) == null) {
                            steering = null
                            onOpen()
                            OverlayExternalInteraction.active.value = true
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(context, com.cyclone.mobile.runtime.background.WorkspaceActivity::class.java)
                                        .putExtra("goal", request.goal)
                                        .putExtra("pendingRequestId", request.id)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                OverlayExternalInteraction.active.value = false
                            }
                        } else {
                            WorkspaceTasks.tryPromoteNext(context)
                            steering = null
                        }
                    },
                )
            }
        }
        if (keyboardOpen && requests.size > visible.size) {
            Text(
                "+${requests.size - visible.size} more queued",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QueuedTaskCard(
    request: PendingWorkspaceRequest,
    compact: Boolean,
    onSteer: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val target = remember(request) { WorkspaceTasks.resolveQueueTarget(context, request) }
    val status = remember(request) { WorkspaceTasks.queuePresentationStatus(context, request) }
    val model = TaskGlassPresentation.queued(
        request = request,
        appLabel = target?.appLabel ?: request.targetAppLabel,
        packageName = target?.packageName ?: request.targetPackageName,
        status = status,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .90f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .64f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CycloneAppIcon(model.packageName, Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.taskLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        model.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onSteer,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = model.steerContentDescription },
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Steer") }
                TextButton(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = model.stopContentDescription },
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Stop") }
            }
        }
    }
}

/** Inline by design: an AccessibilityService overlay cannot safely depend on an Activity dialog token. */
@Composable
private fun SteerDestinationSheet(
    request: PendingWorkspaceRequest,
    destinations: List<WorkspaceDestinationHint>,
    onSelected: (WorkspaceDestinationHint) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        destinations.forEach { destination ->
            androidx.compose.material3.FilterChip(
                selected = request.preferredDestination?.androidUserId == destination.androidUserId,
                onClick = { onSelected(destination) },
                label = { Text(destination.label) },
                modifier = Modifier.semantics { contentDescription = "Steer to ${destination.label}" },
            )
        }
    }
}

private const val KEYBOARD_VISIBLE_QUEUE_CARDS = 2
