package com.cyclone.mobile.ui.v32

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.BuildConfig
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.ai.AgentRunInsights
import com.cyclone.mobile.ai.AgentRunLogExporter
import com.cyclone.mobile.ai.AgentRunRecord
import com.cyclone.mobile.ai.AgentRunRuntime
import com.cyclone.mobile.ai.AgentRunStatus
import com.cyclone.mobile.ai.AgentRunTimeline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun V32RunsPage(
    context: Context,
    refreshTick: Int,
    onKnowledge: () -> Unit,
) {
    var selectedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    val runs = remember(refreshTick) { AgentRunRuntime.recent(context, 60) }
    val selected = selectedRunId?.let { id -> AgentRunRuntime.get(context, id) }

    if (selected != null) {
        V32RunDetail(selected, onBack = { selectedRunId = null })
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { CyclonePageIntro("Observable by default", "Runs", "See what Cyclone tried, what Android accepted, what verification proved and how recovery happened.") }
        item { CycloneSegmentedControl(listOf("Knowledge", "Runs"), 1, { if (it == 0) onKnowledge() }) }
        item {
            val completed = runs.count { it.status == AgentRunStatus.COMPLETE }
            val failed = runs.count { it.status == AgentRunStatus.FAILED }
            CycloneHeroCard(
                title = "${runs.size} recent runs",
                body = "$completed complete · $failed failed · diagnostics stay local until you export them.",
                icon = Icons.Rounded.History,
                tone = CyclonePastel.LILAC,
            )
        }
        if (runs.isEmpty()) {
            item { CycloneSimpleCard { Text("No runs yet", fontWeight = FontWeight.Bold); Text("Your next Cyclone task will appear here with its verification and recovery timeline.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            items(runs, key = { it.id }) { run -> V32RunCard(run) { selectedRunId = run.id } }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun V32RunCard(run: AgentRunRecord, onOpen: () -> Unit) {
    val metrics = AgentRunInsights.metrics(run)
    Card(onClick = onOpen) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(run.goal.ifBlank { "Cyclone task" }, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${run.status.name} · ${metrics.toolCalls} tool ${if (metrics.toolCalls == 1) "turn" else "turns"} · ${formatRunClock(run.startedAtMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CycloneStatusPill(run.status.name, run.status == AgentRunStatus.COMPLETE)
            }
            if (run.summary.isNotBlank()) {
                Text(run.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun V32RunDetail(run: AgentRunRecord, onBack: () -> Unit) {
    val context = LocalContext.current
    val metrics = remember(run) { AgentRunInsights.metrics(run) }
    val tools = remember(run) { AgentRunInsights.toolsUsed(run) }
    val errors = remember(run) { AgentRunInsights.errors(run) }
    val recovery = remember(run) { AgentRunInsights.recoveries(run) }
    val knowledge = remember(run) { AgentRunInsights.knowledgeRefs(run) }
    val screenshots = remember(run) { AgentRunInsights.screenshotRefs(run) }
    val finalPage = remember(run) { AgentRunInsights.finalVerifiedPage(run) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val result = runCatching {
            val metadata = AgentRunLogExporter.metadataJson(
                record = run,
                cycloneVersion = CycloneRelease.version,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                buildIdentifier = "${BuildConfig.BUILD_TYPE}:${BuildConfig.VERSION_NAME}:${BuildConfig.VERSION_CODE}",
            )
            context.contentResolver.openOutputStream(uri)?.use { out ->
                AgentRunLogExporter.writeZip(run, metadata, out)
            } ?: error("Could not open selected destination")
        }
        Toast.makeText(context, if (result.isSuccess) "Run log saved" else "Run log could not be saved", Toast.LENGTH_LONG).show()
    }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                Spacer(Modifier.size(6.dp))
                Text("Runs")
            }
        }
        item {
            CycloneHeroCard(
                title = run.goal.ifBlank { "Cyclone task" },
                body = run.summary.ifBlank { "Run ${run.finalClassification.lowercase().replace('_', ' ')}." },
                icon = Icons.Rounded.Speed,
                tone = if (run.status == AgentRunStatus.COMPLETE) CyclonePastel.MINT else CyclonePastel.PEACH,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CycloneStatusPill(run.status.name, run.status == AgentRunStatus.COMPLETE)
                    Text(formatDuration(metrics.durationMs), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        item {
            CycloneSimpleCard {
                Text("Run details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                V32RunField("Model", run.model.ifBlank { "Unknown" })
                V32RunField("Started", formatRunDate(run.startedAtMs))
                V32RunField("Duration", formatDuration(metrics.durationMs))
                V32RunField("Model / tool turns", "${metrics.modelTurns} / ${metrics.toolCalls}")
                V32RunField("Tools used", tools.joinToString().ifBlank { "None recorded" })
                V32RunField("Verified page", finalPage.first ?: "Not recorded")
                V32RunField("Verified app", finalPage.second ?: "Not recorded")
                V32RunField("Learning", AgentRunInsights.learningSummary(run))
            }
        }
        item {
            CycloneSimpleCard {
                Text("Metrics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                V32RunField("Read / mutation calls", "${metrics.readToolCalls} / ${metrics.mutationToolCalls}")
                V32RunField("Verified actions", metrics.verifiedActions.toString())
                V32RunField("Failed tools", metrics.failedTools.toString())
                V32RunField("Recovery cycles", metrics.recoveryCycles.toString())
                V32RunField("Screenshots", metrics.screenshots.toString())
                V32RunField("GATE events", metrics.gateEvents.toString())
            }
        }
        if (errors.isNotEmpty()) item { V32RunListCard("Errors", errors) }
        if (recovery.isNotEmpty()) item { V32RunListCard("Recovery", recovery) }
        if (knowledge.isNotEmpty()) item { V32RunListCard("Brain knowledge used", knowledge) }
        item {
            CycloneSimpleCard {
                Text("Screenshot usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (screenshots.isEmpty()) "No screenshot evidence recorded." else "${screenshots.size} reference(s): ${screenshots.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Downloaded logs contain screenshot IDs/hashes/metadata only — not image payloads.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { CycloneSectionTitle("Timeline") }
        items(run.events, key = { it.id }) { event ->
            CycloneSimpleCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(AgentRunTimeline.title(event), fontWeight = FontWeight.SemiBold)
                    Text(formatRunSecond(event.timestampMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (event.message.isNotBlank()) Text(event.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val basis = event.payload.optString("verificationBasis")
                if (basis.isNotBlank()) Text("Verification: ${basis.replace('_', ' ').lowercase()}", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (run.status != AgentRunStatus.RUNNING && run.status != AgentRunStatus.GATE) {
            item {
                Button(
                    onClick = { launcher.launch("Cyclone-run-${safeDownloadId(run.id)}.zip") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.size(7.dp))
                    Text("Download log")
                }
            }
        }
        item {
            Text(
                "Diagnostic exports never include provider hidden chain-of-thought, API keys, passwords, PINs, OTPs, verification codes, clipboard secrets or screenshot image bytes.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun V32RunField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(.42f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(.58f))
    }
}

@Composable
private fun V32RunListCard(title: String, values: List<String>) {
    CycloneSimpleCard {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        values.take(20).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun safeDownloadId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
private fun formatRunClock(time: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
private fun formatRunSecond(time: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
private fun formatRunDate(time: Long): String = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(time))
private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms} ms"
    ms < 60_000 -> "${ms / 1_000}s"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
}
