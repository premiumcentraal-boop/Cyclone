package com.cyclone.mobile.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneV32Theme

/**
 * One Compose overlay for all six chrome states. Buttons only invoke [onAction];
 * they never perform Accessibility clicks on the host app.
 */
@Composable
fun OverlayChrome(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CycloneV32Theme {
        when (snapshot.state) {
            OverlayChromeState.IDLE -> if (snapshot.idleChipVisible) {
                Box(
                    modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) { IdleChip(onAction) }
            }
            OverlayChromeState.ANALYSIS -> ChromeFrame(modifier) { AnalysisCard(snapshot, onAction) }
            OverlayChromeState.WORKING -> ChromeFrame(modifier) { WorkingCard(onAction) }
            OverlayChromeState.LIVE -> ChromeFrame(modifier) { LiveBar(onAction) }
            OverlayChromeState.GATE -> ChromeFrame(modifier) { GateCard(onAction) }
            OverlayChromeState.DONE -> ChromeFrame(modifier) { DoneCard() }
        }
    }
}

@Composable
private fun ChromeFrame(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter,
        content = { content() },
    )
}

@Composable
private fun IdleChip(onAction: (OverlayUserAction) -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shadowElevation = 8.dp,
        modifier = Modifier
            .semantics { contentDescription = OverlayCopy.COMPOSER }
            .clickable { onAction(OverlayUserAction.ASK_CYCLONE) },
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(OverlayCopy.COMPOSER, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AnalysisCard(snapshot: OverlayChromeSnapshot, onAction: (OverlayUserAction) -> Unit) {
    ChromeCard {
        Text(OverlayCopy.ANALYSIS_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        snapshot.bullets.forEach { bullet ->
            Text("• $bullet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                onAction(
                    if (snapshot.analysisCta == OverlayAnalysisCta.COMMERCE) OverlayUserAction.COMMERCE
                    else OverlayUserAction.CONFIRM,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(OverlayCopy.primaryCta(snapshot.analysisCta))
        }
        LegalLine()
    }
}

@Composable
private fun WorkingCard(onAction: (OverlayUserAction) -> Unit) {
    ChromeCard {
        Text(OverlayCopy.WORKING_TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(OverlayCopy.WORKING_BODY, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusRow()
        Button(onClick = { onAction(OverlayUserAction.VIEW_PROGRESS) }, modifier = Modifier.fillMaxWidth()) {
            Text(OverlayCopy.PRIMARY)
        }
        InterruptRow(onAction)
        LegalLine()
    }
}

@Composable
private fun LiveBar(onAction: (OverlayUserAction) -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusRow()
            InterruptRow(onAction)
        }
    }
}

@Composable
private fun GateCard(onAction: (OverlayUserAction) -> Unit) {
    ChromeCard {
        Text(OverlayCopy.GATE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Button(onClick = { onAction(OverlayUserAction.GATE_CONFIRM) }, modifier = Modifier.fillMaxWidth()) {
            Text(OverlayCopy.CONFIRM)
        }
        LegalLine()
    }
}

@Composable
private fun DoneCard() {
    ChromeCard {
        Text(OverlayCopy.DONE, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChromeCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun StatusRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
        )
        Text(OverlayCopy.STATUS, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InterruptRow(onAction: (OverlayUserAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(
            onClick = { onAction(OverlayUserAction.STOP_TASK) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = OverlayCopy.LIVE_LEFT, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(OverlayCopy.LIVE_LEFT)
        }
        OutlinedButton(
            onClick = { onAction(OverlayUserAction.TAKE_CONTROL) },
            modifier = Modifier.weight(1f),
        ) {
            Text(OverlayCopy.LIVE_RIGHT)
        }
    }
}

@Composable
private fun LegalLine() {
    Text(
        OverlayCopy.LEGAL,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
