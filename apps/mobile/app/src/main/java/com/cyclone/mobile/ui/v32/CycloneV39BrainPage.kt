package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.remember
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class V39RunRow(
    val session: AiTraceSession,
    val tools: Int,
    val failures: Int,
    val recoveries: Int,
)

/** Cyclone 3.9 Brain surface: recent debuggable runs first, learned knowledge second. */
@Composable
internal fun CycloneV39BrainPage(context: Context, refreshTick: Int) {
    val store = AdaptiveBrainRuntime.store
    val skills = remember(refreshTick) { store.listMicroSkills(60) }
    val apps = remember(refreshTick) { store.listApps() }
    val paths = remember(refreshTick) { store.listPaths(40) }
    val notes = remember(refreshTick) { store.listNotes(30) }
    val runs = remember(refreshTick) {
        AgentTraceRuntime.store.listSessions(24).map { session ->
            val metrics = AgentRunDiagnosticV39.metrics(AgentTraceRuntime.store.events(session.id))
            V39RunRow(session, metrics.toolCalls, metrics.failures, metrics.recoveries)
        }
    }

    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("What Cyclone knows", style = MaterialTheme.typography.headlineSmall) }
        item { CycloneSegmentedControl(listOf("Skills", "Apps", "Insights"), tab, { tab = it }) }
        when (tab) {
            0 -> {
                item { CycloneSectionTitle("Verified skills") }
                val verified = skills.filter { it.successCount > 0 }
                if (verified.isEmpty()) item { Text("Complete a task or teach Cyclone to build reusable skills.") }
                items(verified, key = { it.signature }) { skill ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CycloneAppIcon(skill.fromPackage)
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.titleSmall)
                            Text("${skill.successCount} successful uses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CycloneStatus("${(skill.confidence.coerceIn(0.0, 1.0) * 100).toInt()}%")
                    }
                }
                if (paths.isNotEmpty()) item { Text("${paths.size} learned paths", style = MaterialTheme.typography.bodySmall) }
            }
            1 -> {
                if (apps.isEmpty()) item { Text("Apps Cyclone learns will appear here.") }
                items(apps, key = { it.packageName }) { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CycloneAppIcon(app.packageName)
                        Column {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text("${app.openSuccessCount} successful visits", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            else -> {
                item { CycloneSectionTitle("Recent outcomes") }
                if (runs.isEmpty()) item { Text("Your completed tasks will appear here.") }
                items(runs, key = { it.session.id }) { run ->
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(context, TaskResultActivityV292::class.java).putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, run.session.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(run.session.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(run.session.result ?: if (run.session.status == "RUNNING") "Working" else "Task ended", style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }
                if (notes.isNotEmpty()) item { CycloneSectionTitle("Recent learning") }
                items(notes, key = { it.id }) { Text(it.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
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
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    if (success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    null,
                    tint = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
                Column(Modifier.weight(1f)) {
                    Text(run.session.goal, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${run.session.status.replace('_', ' ')} · ${formatDuration(duration)} · ${formatRunClock(run.session.startedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "${run.session.decisions} turns · ${run.tools} tools · ${run.failures} failures · ${run.recoveries} recovery",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Tap to inspect and download .txt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun V39Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .55f))) {
        Column(Modifier.padding(10.dp)) {
            Text(value.toString(), fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "<1 sec"
    ms < 60_000 -> "${ms / 1_000} sec"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
}

private fun formatRunClock(time: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
