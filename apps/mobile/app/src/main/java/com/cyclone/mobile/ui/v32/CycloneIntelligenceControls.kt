package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
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
import com.cyclone.mobile.ai.OpenRouterCatalogStore
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

private enum class OverlaySettingsStep { MODEL, INTELLIGENCE, AUTONOMY }

/**
 * Intelligence surface shared by the in-app composer and the system overlay.
 *
 * The overlay path intentionally behaves as one small transforming pill: model -> intelligence ->
 * phone autonomy -> model. This keeps settings out of the composer body and prevents nested cards.
 */
@Composable
fun CycloneModelIntelligencePanel(
    modelId: String,
    effort: String,
    showModelSelector: Boolean = true,
    onChange: (String, String) -> Unit,
) {
    if (showModelSelector) {
        OverlaySettingsWizard(modelId = modelId, effort = effort, onChange = onChange)
    } else {
        StandardIntelligencePanel(modelId = modelId, effort = effort, onChange = onChange)
    }
}

@Composable
private fun OverlaySettingsWizard(
    modelId: String,
    effort: String,
    onChange: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OverlaySettingsStep.MODEL) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    val currentModel = V39AiChatContract.modelForStored(modelId).let { model ->
        model.takeIf { candidate -> pickerModels.any { it.id == candidate.id } }
            ?: OpenRouterModelPresets.byId(OpenRouterCatalogStore.activeId(context))
    }
    val currentEffort = normalizedEffort(effort)

    Column(
        modifier = Modifier
            .widthIn(min = 278.dp, max = 344.dp)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        when (step) {
            OverlaySettingsStep.MODEL -> {
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { modelMenuOpen = true }
                            .padding(horizontal = 7.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            currentModel.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Choose model",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DropdownMenu(
                        expanded = modelMenuOpen,
                        onDismissRequest = { modelMenuOpen = false },
                        modifier = Modifier.widthIn(min = 276.dp, max = 316.dp),
                    ) {
                        Text(
                            "Select model",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (pickerModels.isEmpty()) Text("Choose models in Settings → Model & API", Modifier.padding(16.dp))
                        pickerModels.forEach { option ->
                            val selected = V39AiChatContract.storageId(option) == V39AiChatContract.storageId(currentModel)
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
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
                                    modelMenuOpen = false
                                    step = OverlaySettingsStep.INTELLIGENCE
                                },
                                leadingIcon = {
                                    if (selected) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            null,
                                            Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            OverlaySettingsStep.INTELLIGENCE -> {
                Text(
                    "Intelligence",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    intelligenceLevels.forEach { level ->
                        CompactChoice(
                            label = effortLabel(level),
                            selected = currentEffort == level,
                            modifier = Modifier.weight(1f),
                        ) {
                            onChange(modelId, level)
                            step = OverlaySettingsStep.AUTONOMY
                        }
                    }
                }
            }

            OverlaySettingsStep.AUTONOMY -> {
                Text(
                    "Phone autonomy",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        CycloneAiAccessProfile.GUIDED to "Ask often",
                        CycloneAiAccessProfile.BALANCED to "Balanced",
                        CycloneAiAccessProfile.FULL to "Independent",
                    ).forEach { (profile, label) ->
                        CompactChoice(
                            label = label,
                            selected = autonomy == profile,
                            modifier = Modifier.weight(1f),
                        ) {
                            autonomy = profile
                            CycloneAiAccessProfileStore.write(context, profile)
                            step = OverlaySettingsStep.MODEL
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactChoice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StandardIntelligencePanel(
    modelId: String,
    effort: String,
    onChange: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var autonomyOpen by remember { mutableStateOf(false) }
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val currentEffort = normalizedEffort(effort)

    Column(
        Modifier.widthIn(min = 252.dp, max = 292.dp).padding(horizontal = 12.dp, vertical = 10.dp),
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
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        Modifier.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(6.dp),
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))

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

/** Model selection floats above the composer and never steals typing width. */
@Composable
fun CycloneModelPill(
    modelId: String,
    effort: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    val currentModel = V39AiChatContract.modelForStored(modelId).let { model ->
        model.takeIf { candidate -> pickerModels.any { it.id == candidate.id } }
            ?: OpenRouterModelPresets.byId(OpenRouterCatalogStore.activeId(context))
    }
    val currentEffort = normalizedEffort(effort)

    Box(modifier) {
        Surface(
            modifier = Modifier.widthIn(max = 210.dp).clickable(enabled = enabled) { open = true },
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
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
            if (pickerModels.isEmpty()) Text("Choose models in Settings → Model & API", Modifier.padding(16.dp))
            pickerModels.forEach { option ->
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
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    var model by remember(catalogRevision) {
        mutableStateOf(V39AiChatContract.modelForStored(OpenRouterCatalogStore.activeId(context)))
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
                CycloneModelIntelligencePanel(
                    modelId = V39AiChatContract.storageId(model),
                    effort = level,
                    showModelSelector = false,
                    onChange = ::persist,
                )
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
