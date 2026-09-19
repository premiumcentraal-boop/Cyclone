package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object CycloneChatDrawerGesturePolicy {
    const val COLLAPSE_THRESHOLD_DP = 72f
    const val EXPAND_THRESHOLD_DP = 34f

    fun shouldCollapse(downwardDragDp: Float): Boolean =
        downwardDragDp >= COLLAPSE_THRESHOLD_DP

    fun shouldExpand(verticalDragDp: Float): Boolean =
        verticalDragDp <= -EXPAND_THRESHOLD_DP
}

/**
 * Shared visual shell for the in-app chat and accessibility overlay.
 *
 * The visible grabber is the only drag target: scrolling chat/task content never accidentally
 * collapses the drawer. A deliberate downward drag settles the whole surface into the Ask Cyclone
 * pill while the underlying run keeps its ownership/state.
 */
@Composable
internal fun CycloneChatDrawerSurface(
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { androidx.compose.runtime.mutableStateOf<Job?>(null) }
    val thresholdPx = with(LocalDensity.current) { CycloneChatDrawerGesturePolicy.COLLAPSE_THRESHOLD_DP.dp.toPx() }

    fun settle(collapse: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = dragOffset,
                targetValue = if (collapse) thresholdPx * 1.35f else 0f,
                animationSpec = spring(dampingRatio = .86f, stiffness = 520f),
            ) { value, _ -> dragOffset = value }
            if (collapse) onCollapse()
            dragOffset = 0f
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffset },
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics { contentDescription = "Drag down or tap to minimize Cyclone chat" }
                    .clickable(role = Role.Button, onClick = { settle(true) })
                    .pointerInput(thresholdPx) {
                        detectVerticalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                            },
                            onDragCancel = { settle(false) },
                            onDragEnd = { settle(dragOffset >= thresholdPx) },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 42.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f), CircleShape),
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** Resting state for both chat surfaces. Tap or drag upward to recover the exact current run. */
@Composable
internal fun CycloneCollapsedAskPill(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    status: String? = null,
) {
    var upwardDrag by remember { mutableFloatStateOf(0f) }
    val thresholdPx = with(LocalDensity.current) { CycloneChatDrawerGesturePolicy.EXPAND_THRESHOLD_DP.dp.toPx() }
    Surface(
        onClick = onExpand,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Ask Cyclone. Open current chat." }
            .pointerInput(thresholdPx) {
                detectVerticalDragGestures(
                    onDragStart = { upwardDrag = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        upwardDrag += amount
                    },
                    onDragCancel = { upwardDrag = 0f },
                    onDragEnd = {
                        if (upwardDrag <= -thresholdPx) onExpand()
                        upwardDrag = 0f
                    },
                )
            },
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .985f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .60f)),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSurface)
                    if (active) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(3.dp)
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("Ask Cyclone", style = MaterialTheme.typography.bodyLarge)
                if (!status.isNullOrBlank()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.KeyboardArrowUp,
                        null,
                        Modifier.size(25.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CycloneChatToolsPanel(
    onCamera: () -> Unit,
    onFiles: () -> Unit,
    onShareScreen: () -> Unit,
    onModel: () -> Unit,
    onExplainScreen: () -> Unit,
    onCreateRoutine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CycloneToolTile(Icons.Rounded.CameraAlt, "Camera", onCamera, Modifier.weight(1f))
            CycloneToolTile(Icons.Rounded.AttachFile, "Files", onFiles, Modifier.weight(1f))
            CycloneToolTile(Icons.Rounded.ScreenShare, "Screen", onShareScreen, Modifier.weight(1f))
        }
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
            tonalElevation = 0.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                CycloneToolRow(Icons.Rounded.AutoAwesome, "Explain this screen", onExplainScreen)
                CycloneToolRow(Icons.Rounded.Tune, "Model & intelligence", onModel)
                CycloneToolRow(Icons.Rounded.Bolt, "Create a routine", onCreateRoutine)
            }
        }
    }
}

@Composable
private fun CycloneToolTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 92.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f),
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun CycloneToolRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}
