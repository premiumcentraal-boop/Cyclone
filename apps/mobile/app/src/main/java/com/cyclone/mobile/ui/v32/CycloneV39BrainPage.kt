package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.AgentRunDiagnosticV39
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.TaskResultActivityV292
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class V39RunRow(
    val session: AiTraceSession,
    val tools: Int,
    val failures: Int,
    val recoveries: Int,
)

/** Consumer-facing knowledge center backed by Cyclone's real learned state and run history. */
@Composable
internal fun CycloneV39BrainPage(context: Context, refreshTick: Int) {
    val store = AdaptiveBrainRuntime.store
    val skills = remember(refreshTick) { store.listMicroSkills(60) }
    val apps = remember(refreshTick) { store.listApps() }
    val paths = remember(refreshTick) { store.listPaths(40) }
    val notes = remember(refreshTick) { store.listNotes(30) }
    val task by WorkspaceTasks.state.collectAsState()
    val runs = remember(refreshTick) {
        AgentTraceRuntime.store.listSessions(24).map { session ->
            val metrics = AgentRunDiagnosticV39.metrics(AgentTraceRuntime.store.events(session.id))
            V39RunRow(session, metrics.toolCalls, metrics.failures, metrics.recoveries)
        }
    }
    val verified = skills.filter { it.successCount > 0 }
    val averageConfidence = if (verified.isEmpty()) 0 else {
        (verified.map { it.confidence.coerceIn(0.0, 1.0) }.average() * 100).toInt()
    }

    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Brain", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "What Cyclone knows",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        task?.takeIf { UiTask(it).active }?.let { active ->
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.SmartToy, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Current context", style = MaterialTheme.typography.labelMedium)
                            Text(
                                active.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(UiTask(active).consumerStatus, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrainMetric("Verified", verified.size.toString(), Modifier.weight(1f))
                BrainMetric("Apps", apps.size.toString(), Modifier.weight(1f))
                BrainMetric("Confidence", "$averageConfidence%", Modifier.weight(1f))
            }
        }

        item { CycloneSegmentedControl(listOf("Skills", "Apps", "Outcomes"), tab, { tab = it }) }

        when (tab) {
            0 -> {
                item { CycloneSectionTitle("Verified skills") }
                if (verified.isEmpty()) {
                    item {
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text("No verified skills yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Complete a task or teach Cyclone by doing. Reusable skills will appear here after successful use.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(verified, key = { it.signature }) { skill ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                CycloneAppIcon(skill.fromPackage, Modifier.padding(7.dp).size(30.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${skill.successCount} successful ${if (skill.successCount == 1) "use" else "uses"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            CycloneStatus("${(skill.confidence.coerceIn(0.0, 1.0) * 100).toInt()}%")
                        }
                    }
                }
                if (paths.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Rounded.Memory, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${paths.size} learned paths available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            1 -> {
                item { CycloneSectionTitle("Learned apps") }
                if (apps.isEmpty()) {
                    item {
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text("No learned apps yet", style = MaterialTheme.typography.titleSmall)
                            Text("Apps Cyclone successfully navigates will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.padding(15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                CycloneAppIcon(app.packageName, Modifier.padding(7.dp).size(30.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${app.openSuccessCount} successful ${if (app.openSuccessCount == 1) "visit" else "visits"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            else -> {
                item { CycloneSectionTitle("Recent outcomes") }
                if (runs.isEmpty()) {
                    item {
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text("No task outcomes yet", style = MaterialTheme.typography.titleSmall)
                            Text("Completed Cyclone tasks will appear here with a concise result and details when you need them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                items(runs, key = { it.session.id }) { run ->
                    V39RunCard(run) {
                        context.startActivity(
                            Intent(context, TaskResultActivityV292::class.java)
                                .putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, run.session.id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                if (notes.isNotEmpty()) {
                    item { CycloneSectionTitle("Recent learning") }
                    items(notes, key = { it.id }) { note ->
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(note.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V39RunCard(run: V39RunRow, onOpen: () -> Unit) {
    val success = run.session.status == "COMPLETED"
    val duration = ((run.session.endedAt ?: System.currentTimeMillis()) - run.session.startedAt).coerceAtLeast(0)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (success) Icons.Rounded.CheckCircle else Icons.Rounded.History,
                    null,
                    modifier = Modifier.size(19.dp),
                    tint = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(run.session.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        run.session.result ?: if (run.session.status == "RUNNING") "Working" else "Task ended",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                "${run.session.status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} · ${formatDuration(duration)} · ${formatRunClock(run.session.startedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("View details", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BrainMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "<1 sec"
    ms < 60_000 -> "${ms / 1_000} sec"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
}

private fun formatRunClock(time: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
