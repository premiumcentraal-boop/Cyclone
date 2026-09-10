package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.OverlayUserAction

/** Foreground/live-phone projection of the same consumer state language used by background tasks. */
@Composable
fun CycloneForegroundWorkCard(
    snapshot: OverlayChromeSnapshot,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onExpand: (() -> Unit)? = null,
) {
    val status = snapshot.statusMessage?.trim()?.takeIf { it.isNotBlank() }
        ?: snapshot.bullets.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: if (snapshot.state == OverlayChromeState.LIVE) "Watching the phone and continuing…" else "Using your phone…"
    val shape = RoundedCornerShape(if (compact) 22.dp else 24.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .then(if (compact && onExpand != null) Modifier.clickable(onClick = onExpand) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (compact) .92f else .96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = if (compact) 1.dp else 2.dp,
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth().padding(start = 13.dp, top = 9.dp, end = 7.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Cyclone", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        CycloneTaskStatusPill(CycloneTaskVisualState.WORKING)
                    }
                    Text(
                        status,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = { OverlayChromeRuntime.dispatch(OverlayUserAction.STOP_TASK) },
                    modifier = Modifier.size(42.dp),
                ) {
                    Icon(Icons.Rounded.Stop, "Stop task", Modifier.size(19.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Cyclone",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CycloneTaskStatusPill(CycloneTaskVisualState.WORKING)
                }
                Text(
                    status,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { OverlayChromeRuntime.dispatch(OverlayUserAction.VIEW_PROGRESS) },
                        modifier = Modifier.weight(1f),
                    ) { Text("View progress") }
                    FilledIconButton(
                        onClick = { OverlayChromeRuntime.dispatch(OverlayUserAction.STOP_TASK) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.Stop, "Stop task", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
