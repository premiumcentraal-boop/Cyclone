package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.OverlayUserAction

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
    val shape = RoundedCornerShape(if (compact) 28.dp else 24.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = if (compact) .86f else .76f))
            .then(if (compact && onExpand != null) Modifier.clickable(onClick = onExpand) else Modifier),
    ) {
        if (compact) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Working on it…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        status,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(
                    onClick = { OverlayChromeRuntime.dispatch(OverlayUserAction.STOP_TASK) },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Rounded.Stop, "Stop task", Modifier.size(20.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Working on it", style = MaterialTheme.typography.titleSmall)
                        Text(
                            status,
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledIconButton(
                        onClick = { OverlayChromeRuntime.dispatch(OverlayUserAction.STOP_TASK) },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(Icons.Rounded.Stop, "Stop task", Modifier.size(20.dp))
                    }
                }
                Text(
                    "View progress",
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { OverlayChromeRuntime.dispatch(OverlayUserAction.VIEW_PROGRESS) }
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}