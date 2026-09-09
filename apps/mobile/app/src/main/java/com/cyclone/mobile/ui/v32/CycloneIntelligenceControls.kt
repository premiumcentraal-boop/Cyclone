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
import androidx.compose.foundation.shape.CircleShape
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

private fun modelSubtitle(model: OpenRouterModelPreset): String = when (model.id) {
    OpenRouterModelPresets.DEFAULT.id -> "Default · fast everyday tasks"
    OpenRouterModelPresets.GPT_6_ASTRA.id -> "Best for complex phone tasks"
    OpenRouterModelPresets.GPT_5_6_SOL.id -> "Fast and efficient"
    OpenRouterModelPresets.CLAUDE_FABLE_5_1.id -> "Deep analysis"
    OpenRouterModelPresets.GEMINI_3_8_FLASH.id -> "Fast multimodal"
    OpenRouterModelPresets.MUSE_SPARK_1_3_CONTRIBUTOR.id -> "Lower cost · contributes training data"
    OpenRouterModelPresets.MUSE_SPARK_1_3.id -> "Balanced multimodal"
    OpenRouterModelPresets.GLM_5_3_FLASH.id -> "Fast alternative"
    else -> "Cyclone model"
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
        Modifier.widthIn(min = 248.dp, max = 286.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Intelligence", style = MaterialTheme.typography.titleSmall)
            Text(
                "Choose how deeply Cyclone thinks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            intelligenceLevels.forEach { level ->
                val active = currentEffort == level
                Surface(
                    modifier = Modifier.weight(1f).clickable { onChange(modelId, level) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    shadowElevation = if (active) 1.dp else 0.dp,
                ) {
                    Column(
                        Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(7.dp),
                            shape = CircleShape,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.outline,
                        ) {}
                        Text(
                            effortLabel(level),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))

        TextButton(
            onClick = { autonomyOpen = !autonomyOpen },
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Phone autonomy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(autonomyLabel(autonomy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                "Sensitive actions still ask for confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Model selection deliberately floats outside the composer row. Long model names therefore never
 * steal typing width from Ask Cyclone.
 */
@Composable
fun CycloneModelPill(
    modelId: String,
    effort: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (String, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentModel = V39AiChatContract.modelForStored(modelId)
    val currentEffort = normalizedEffort(effort)

    Box(modifier) {
        Surface(
            modifier = Modifier.widthIn(max = 206.dp).clickable(enabled = enabled) { open = true },
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .91f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${currentModel.label} ${effortLabel(currentEffort)}",
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 268.dp, max = 304.dp),
        ) {
            Text("Select model", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            OpenRouterModelPresets.all.forEach { option ->
                val selected = V39AiChatContract.storageId(option) == V39AiChatContract.storageId(currentModel)
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(option.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                modelSubtitle(option),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    onClick = {
                        onChange(V39AiChatContract.storageId(option), currentEffort)
                        open = false
                    },
                    leadingIcon = {
                        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        else Spacer(Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
fun CycloneIntelligenceControls(
    enabled: Boolean = true,
    showModelPill: Boolean = true,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(V39AiChatContract.PREFS, android.content.Context.MODE_PRIVATE) }
    var intelligenceOpen by remember { mutableStateOf(false) }
    var level by remember {
        mutableStateOf(normalizedEffort(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium"))
    }
    var model by remember {
        mutableStateOf(V39AiChatContract.modelForStored(prefs.getString(V39AiChatContract.MODEL_KEY, null)))
    }

    fun persist(modelId: String, effort: String) {
        model = V39AiChatContract.modelForStored(modelId)
        level = normalizedEffort(effort)
        prefs.edit()
            .putString(V39AiChatContract.MODEL_KEY, V39AiChatContract.storageId(model))
            .putString("openrouter_reasoning_effort", level)
            .apply()
        onChanged()
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Box {
            IconButton(
                onClick = { intelligenceOpen = !intelligenceOpen },
                enabled = enabled,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    "Intelligence and phone autonomy",
                    modifier = Modifier.size(20.dp),
                    tint = if (intelligenceOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = intelligenceOpen, onDismissRequest = { intelligenceOpen = false }) {
                CycloneModelIntelligencePanel(V39AiChatContract.storageId(model), level) { modelId, effort ->
                    persist(modelId, effort)
                }
            }
        }

        if (showModelPill) {
            CycloneModelPill(
                modelId = V39AiChatContract.storageId(model),
                effort = level,
                enabled = enabled,
                onChange = ::persist,
            )
        }
    }
}
