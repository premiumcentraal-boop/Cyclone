package com.cyclone.mobile.ui.v31

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.v31.V31ReasoningMode

data class CycloneIntelligenceUiState(
    val healthy: Boolean,
    val summary: String,
    val activeTask: String? = null,
)

data class TeachLearningUiState(
    val active: Boolean,
    val pagesLearned: Int,
    val routineName: String?,
    val completion: String? = null,
)

data class AiReasoningUiState(
    val mode: V31ReasoningMode,
    val taskProgress: String,
    val humanTakeover: Boolean = false,
)

data class AutomationRuntimeUiState(
    val routineName: String,
    val version: String,
    val status: String,
    val lastVerification: String?,
    val resumable: Boolean,
)

data class BrainKnowledgeUiState(
    val verifiedKnowledge: Int,
    val apps: Int,
    val pages: Int,
    val hotMemory: Int,
    val documentMemory: Int,
    val structuralMemory: Int,
    val confidenceLabel: String,
    val stalenessLabel: String,
)

data class V31StatusUiState(
    val status: String,
    val moduleSummary: String,
    val recoverySummary: String,
    val privacySummary: String,
)

@Composable
fun CycloneIntelligenceHealthCard(state: CycloneIntelligenceUiState, modifier: Modifier = Modifier) {
    V31Card(
        title = "Cyclone Intelligence",
        status = if (state.healthy) "Ready" else "Needs attention",
        lines = buildList {
            add(state.summary)
            state.activeTask?.let { add("Active · $it") }
        },
        modifier = modifier,
    )
}

@Composable
fun TeachLearningStateCard(state: TeachLearningUiState, modifier: Modifier = Modifier) {
    V31Card(
        title = "Learning",
        status = if (state.active) "Learning now" else state.completion ?: "Ready to teach",
        lines = buildList {
            add("${state.pagesLearned} page${if (state.pagesLearned == 1) "" else "s"} learned")
            state.routineName?.let { add("Routine · $it") }
            state.completion?.let { if (state.active) add(it) }
        },
        modifier = modifier,
    )
}

@Composable
fun AiReasoningModeCard(state: AiReasoningUiState, modifier: Modifier = Modifier) {
    V31Card(
        title = "Current task",
        status = if (state.humanTakeover) "Waiting for you" else reasoningLabel(state.mode),
        lines = listOf(state.taskProgress),
        modifier = modifier,
    )
}

@Composable
fun AutomationRuntimeCard(state: AutomationRuntimeUiState, modifier: Modifier = Modifier) {
    V31Card(
        title = state.routineName,
        status = state.status,
        lines = buildList {
            add("Version ${state.version}")
            state.lastVerification?.let { add("Last check · $it") }
            add(if (state.resumable) "Can resume safely" else "Starts fresh next time")
        },
        modifier = modifier,
    )
}

@Composable
fun BrainKnowledgeCard(state: BrainKnowledgeUiState, modifier: Modifier = Modifier) {
    V31Card(
        title = "Verified knowledge",
        status = "${state.verifiedKnowledge} verified",
        lines = listOf(
            "${state.apps} apps · ${state.pages} pages",
            "Memory · ${state.hotMemory} recent · ${state.documentMemory} notes · ${state.structuralMemory} structural",
            "Confidence · ${state.confidenceLabel}",
            "Freshness · ${state.stalenessLabel}",
        ),
        modifier = modifier,
    )
}

@Composable
fun V31StatusCard(state: V31StatusUiState, modifier: Modifier = Modifier) {
    V31Card(
        title = "Cyclone status",
        status = state.status,
        lines = listOf(
            state.moduleSummary,
            "Recovery · ${state.recoverySummary}",
            "Privacy · ${state.privacySummary}",
        ),
        modifier = modifier,
    )
}

@Composable
private fun V31Card(
    title: String,
    status: String,
    lines: List<String>,
    modifier: Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusPill(status)
            }
            lines.filter(String::isNotBlank).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun reasoningLabel(mode: V31ReasoningMode): String = when (mode) {
    V31ReasoningMode.KNOWN_ROUTE -> "Known route"
    V31ReasoningMode.GRAPH -> "App knowledge"
    V31ReasoningMode.MEMORY -> "Remembered route"
    V31ReasoningMode.SEMANTIC -> "On-screen controls"
    V31ReasoningMode.AI -> "AI reasoning"
    V31ReasoningMode.VISION_FALLBACK -> "Visual check"
    V31ReasoningMode.HUMAN_TAKEOVER -> "Waiting for you"
}
