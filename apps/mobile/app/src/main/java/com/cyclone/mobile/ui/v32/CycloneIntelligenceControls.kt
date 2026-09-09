package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets

private val intelligenceLevels = listOf("low", "medium", "high")

private fun effortLabel(value: String): String = when (value.lowercase()) {
    "low" -> "Low"
    "high", "max" -> "High"
    else -> "Medium"
}

private fun normalizedEffort(value: String): String = when (value.lowercase()) {
    "low" -> "low"
    "high", "max" -> "high"
    else -> "medium"
}

private fun autonomyLabel(profile: CycloneAiAccessProfile): String = when (profile) {
    CycloneAiAccessProfile.GUIDED -> "Ask often"
    CycloneAiAccessProfile.BALANCED -> "Balanced"
    CycloneAiAccessProfile.FULL -> "Independent"
}

/** Compact first-stage menu: intelligence first, phone autonomy second. */
@Composable
fun CycloneModelIntelligencePanel(
    modelId: String,
    effort: String,
    onChange: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var autonomyOpen by remember { mutableStateOf(false) }
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val currentEffort = normalizedEffort(effort)

    Column(
        Modifier.widthIn(min = 244.dp, max = 284.dp).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Intelligence", style = MaterialTheme.typography.titleSmall)
            Text(
                "How much reasoning Cyclone should use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intelligenceLevels.forEach { level ->
                val active = currentEffort == level
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onChange(modelId, level) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        effortLabel(level),
                        modifier = Modifier.padding(vertical = 9.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))

        TextButton(
            onClick = { autonomyOpen = !autonomyOpen },
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Phone autonomy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    autonomyLabel(autonomy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp))
        }

        if (autonomyOpen) {
            listOf(
                CycloneAiAccessProfile.GUIDED to "Ask often",
                CycloneAiAccessProfile.BALANCED to "Balanced",
                CycloneAiAccessProfile.FULL to "Independent",
            ).forEach { (profile, label) ->
                val active = autonomy == profile
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            autonomy = profile
                            CycloneAiAccessProfileStore.write(context, profile)
                            autonomyOpen = false
                        }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (active) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "Sensitive actions still require confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CycloneIntelligenceControls(enabled: Boolean = true, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(V39AiChatContract.PREFS, android.content.Context.MODE_PRIVATE) }
    var intelligenceOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    var level by remember {
        mutableStateOf(normalizedEffort(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium"))
    }
    var model by remember {
        mutableStateOf(V39AiChatContract.modelForStored(prefs.getString(V39AiChatContract.MODEL_KEY, null)))
    }

    fun persistModel(next: OpenRouterModelPreset) {
        model = next
        prefs.edit().putString(V39AiChatContract.MODEL_KEY, V39AiChatContract.storageId(next)).apply()
        onChanged()
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            IconButton(
                onClick = { intelligenceOpen = !intelligenceOpen },
                enabled = enabled,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    "Intelligence and phone autonomy",
                    modifier = Modifier.size(20.dp),
                    tint = if (intelligenceOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = intelligenceOpen, onDismissRequest = { intelligenceOpen = false }) {
                CycloneModelIntelligencePanel(model.id, level) { _, effort ->
                    level = normalizedEffort(effort)
                    prefs.edit().putString("openrouter_reasoning_effort", level).apply()
                    onChanged()
                }
            }
        }

        Box {
            TextButton(
                onClick = { modelOpen = true },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 36.dp).widthIn(max = 154.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    model.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(
                expanded = modelOpen,
                onDismissRequest = { modelOpen = false },
                modifier = Modifier.widthIn(min = 230.dp, max = 300.dp),
            ) {
                OpenRouterModelPresets.all.forEach { option ->
                    val selected = V39AiChatContract.storageId(option) == V39AiChatContract.storageId(model)
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            persistModel(option)
                            modelOpen = false
                        },
                        leadingIcon = {
                            if (selected) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Spacer(Modifier.size(18.dp))
                            }
                        },
                    )
                }
            }
        }
    }
}
