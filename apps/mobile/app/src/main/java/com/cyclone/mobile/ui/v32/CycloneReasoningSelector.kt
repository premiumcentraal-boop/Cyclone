package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterCatalogStore

enum class ReasoningSelectorMode { MODEL_CONTROLLED, PILLS, MENU }

fun reasoningSelectorMode(options: List<String>): ReasoningSelectorMode = when {
    options.isEmpty() -> ReasoningSelectorMode.MODEL_CONTROLLED
    options.size <= 3 -> ReasoningSelectorMode.PILLS
    else -> ReasoningSelectorMode.MENU
}

internal fun reasoningEffortLabel(value: String): String = value.replace('_', ' ').split(' ')
    .joinToString(" ") { token -> token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }

/** Exact OpenRouter reasoning surface. It never maps, rounds or invents effort tokens. */
@Composable
internal fun CycloneReasoningSelector(
    modelId: String,
    compact: Boolean = false,
    onChanged: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val revision by OpenRouterCatalogStore.revision.collectAsState()
    val canonical = remember(modelId) { OpenRouterCatalogStore.canonicalId(modelId) }
    val options = remember(revision, canonical) { OpenRouterCatalogStore.reasoningOptions(context, canonical) }
    val selected = remember(revision, canonical) { OpenRouterCatalogStore.reasoningSelection(context, canonical) }
    val defaultEffort = remember(revision, canonical) { OpenRouterCatalogStore.defaultReasoningEffort(context, canonical) }
    var menuOpen by remember(canonical) { mutableStateOf(false) }

    fun choose(value: String?) {
        OpenRouterCatalogStore.setReasoningEffort(context, canonical, value)
        onChanged(value.orEmpty())
    }

    when (reasoningSelectorMode(options)) {
        ReasoningSelectorMode.MODEL_CONTROLLED -> {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Model controlled", style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "OpenRouter does not advertise selectable reasoning efforts for this model, so Cyclone sends no intelligence override.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        ReasoningSelectorMode.PILLS -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.forEach { effort ->
                        FilterChip(
                            selected = selected == effort,
                            onClick = { choose(effort) },
                            label = { Text(reasoningEffortLabel(effort)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selected == null) "Model default${defaultEffort?.let { ": ${reasoningEffortLabel(it)}" }.orEmpty()}"
                        else "Exact OpenRouter effort: $selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected != null) TextButton(onClick = { choose(null) }) { Text("Use default") }
                }
            }
        }
        ReasoningSelectorMode.MENU -> {
            Box(Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { menuOpen = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = if (compact) 9.dp else 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Intelligence", style = MaterialTheme.typography.labelMedium)
                            Text(
                                selected?.let(::reasoningEffortLabel)
                                    ?: "Model default${defaultEffort?.let { " · ${reasoningEffortLabel(it)}" }.orEmpty()}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, "Choose intelligence")
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Model default${defaultEffort?.let { " · ${reasoningEffortLabel(it)}" }.orEmpty()}") },
                        onClick = { choose(null); menuOpen = false },
                    )
                    options.forEach { effort ->
                        DropdownMenuItem(
                            text = { Text(reasoningEffortLabel(effort)) },
                            onClick = { choose(effort); menuOpen = false },
                            trailingIcon = { if (selected == effort) Text("✓") },
                        )
                    }
                }
            }
        }
    }
}
