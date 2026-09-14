package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/**
 * Exact OpenRouter reasoning surface. It never maps, rounds or invents effort tokens.
 *
 * Two/three-way intelligence choices are one physical liquid control: a single Kyant tray with a
 * moving refractive lens. More complex provider option sets use one liquid menu trigger instead of
 * falling back to a stock outlined/filled field.
 */
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
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Model controlled",
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "This model does not expose selectable intelligence levels, so Cyclone leaves reasoning under the model's control.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ReasoningSelectorMode.PILLS -> {
            val selectedIndex = selected?.let(options::indexOf)?.takeIf { it >= 0 }
            Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
                CycloneLiquidChoiceBar(
                    options = options.map(::reasoningEffortLabel),
                    selectedIndex = selectedIndex,
                    onSelect = { index -> choose(options[index]) },
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (selected == null) {
                            "Model default${defaultEffort?.let { ": ${reasoningEffortLabel(it)}" }.orEmpty()}"
                        } else {
                            "Exact provider level · ${reasoningEffortLabel(selected)}"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (selected != null) {
                        CycloneLiquidTextAction(
                            label = "Default",
                            onClick = { choose(null) },
                        )
                    }
                }
            }
        }

        ReasoningSelectorMode.MENU -> {
            Box(Modifier.fillMaxWidth()) {
                CycloneLiquidMenuTrigger(
                    title = "Intelligence",
                    value = selected?.let(::reasoningEffortLabel)
                        ?: "Model default${defaultEffort?.let { " · ${reasoningEffortLabel(it)}" }.orEmpty()}",
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    compact = compact,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text("Model default${defaultEffort?.let { " · ${reasoningEffortLabel(it)}" }.orEmpty()}")
                        },
                        onClick = { choose(null); menuOpen = false },
                        trailingIcon = {
                            if (selected == null) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        },
                    )
                    options.forEach { effort ->
                        DropdownMenuItem(
                            text = { Text(reasoningEffortLabel(effort)) },
                            onClick = { choose(effort); menuOpen = false },
                            trailingIcon = {
                                if (selected == effort) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}
