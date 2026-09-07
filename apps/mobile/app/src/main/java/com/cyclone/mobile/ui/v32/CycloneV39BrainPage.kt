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
import androidx.compose.runtime.Composable
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

    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CyclonePageIntro(
                "Learn once, debug fast",
                "Cyclone Brain",
                "Recent AI runs are saved as compact diagnostics you can inspect, download and share when something goes wrong.",
            )
        }

        item { CycloneSectionTitle("Recent runs") }
        if (runs.isEmpty()) {
            item {
                CycloneSimpleCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.History, null)
                        Column {
                            Text("No AI runs yet", fontWeight = FontWeight.Bold)
                            Text("Ask Cyclone to do something and its diagnostic run will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            items(runs, key = { it.session.id }) { run ->
                V39RunCard(run) {
                    context.startActivity(
                        Intent(context, TaskResultActivityV292::class.java)
                            .putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, run.session.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }

        item {
            CycloneHeroCard(
                title = "${skills.count { it.confidence >= .7 }} strong skills",
                body = "Across ${apps.size} apps and ${paths.size} reusable paths.",
                icon = Icons.Rounded.AccountTree,
                tone = CyclonePastel.SKY,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V39Metric("Skills", skills.size, Modifier.weight(1f))
                    V39Metric("Apps", apps.size, Modifier.weight(1f))
                    V39Metric("Paths", paths.size, Modifier.weight(1f))
                }
            }
        }

        item { CycloneSectionTitle("Reusable skills") }
        if (skills.isEmpty()) {
            item {
                CycloneSimpleCard {
                    Text("Nothing verified yet", fontWeight = FontWeight.Bold)
                    Text("Complete phone tasks or teach Cyclone to build reusable evidence.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(skills.take(16), key = { it.signature }) { skill ->
                CycloneSimpleCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(skill.name, fontWeight = FontWeight.Bold)
                            Text(
                                "${skill.successCount} success · ${skill.failureCount} failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CycloneStatusPill("${(skill.confidence * 100).toInt()}%", skill.confidence >= .55)
                    }
                }
            }
        }

        if (notes.isNotEmpty()) {
            item {
                CycloneSimpleCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Rounded.Memory, null)
                        Text("Latest learning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    notes.take(6).forEach {
                        Text("• ${it.text}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
