package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets

private val autonomyProfiles = listOf(
    CycloneAiAccessProfile.GUIDED,
    CycloneAiAccessProfile.BALANCED,
    CycloneAiAccessProfile.FULL,
)

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

@Composable
fun CycloneModelIntelligencePanel(
    modelId: String,
    effort: String,
    showModelSelector: Boolean = true,
    onChange: (String, String) -> Unit,
) {
    if (showModelSelector) OverlaySettingsWizard(modelId, effort, onChange)
    else StandardIntelligencePanel(modelId, effort, onChange)
}

@Composable
private fun ModelPickerRows(
    models: List<OpenRouterModelPreset>,
    currentModel: OpenRouterModelPreset,
    onSelect: (OpenRouterModelPreset) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (models.isEmpty()) {
            Text(
                "Choose models in Settings → Model & API",
                Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        models.forEachIndexed { index, option ->
            val selected = V39AiChatContract.storageId(option) == V39AiChatContract.storageId(currentModel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                } else {
                    Spacer(Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        modelSubtitle(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index != models.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 38.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f),
                )
            }
        }
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
    var modelListOpen by remember { mutableStateOf(false) }
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    val currentModel = V39AiChatContract.modelForStored(modelId).let { model ->
        model.takeIf { candidate -> pickerModels.any { it.id == candidate.id } }
            ?: OpenRouterCatalogStore.preset(context, OpenRouterCatalogStore.activeId(context))
    }
    val reasoningOptions = remember(catalogRevision, currentModel.id) {
        OpenRouterCatalogStore.reasoningOptions(context, currentModel.id)
    }

    Column(
        modifier = Modifier.widthIn(min = 278.dp, max = 344.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            OverlaySettingsStep.entries.forEach { item ->
                val active = item == step
                Text(
                    when (item) {
                        OverlaySettingsStep.MODEL -> "Model"
                        OverlaySettingsStep.INTELLIGENCE -> "Intelligence"
                        OverlaySettingsStep.AUTONOMY -> "Autonomy"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (step) {
            OverlaySettingsStep.MODEL -> {
                CycloneLiquidMenuTrigger(
                    title = "Model",
                    value = currentModel.label,
                    onClick = { modelListOpen = !modelListOpen },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                if (modelListOpen) {
                    ModelPickerRows(pickerModels, currentModel) { option ->
                        OpenRouterCatalogStore.setActive(context, option.id)
                        val exact = OpenRouterCatalogStore.reasoningSelection(context, option.id).orEmpty()
                        onChange(V39AiChatContract.storageId(option), exact)
                        modelListOpen = false
                        step = OverlaySettingsStep.INTELLIGENCE
                    }
                }
            }

            OverlaySettingsStep.INTELLIGENCE -> {
                if (currentModel.id.isBlank()) {
                    Text("Choose an available model first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    CycloneReasoningSelector(currentModel.id, compact = true) { selected ->
                        onChange(V39AiChatContract.storageId(currentModel), selected)
                        step = OverlaySettingsStep.AUTONOMY
                    }
                    if (reasoningOptions.isEmpty()) {
                        CycloneLiquidTextAction(
                            label = "Continue",
                            onClick = { step = OverlaySettingsStep.AUTONOMY },
                            modifier = Modifier.align(Alignment.End),
                            prominent = true,
                        )
                    }
                }
            }

            OverlaySettingsStep.AUTONOMY -> {
                val selectedIndex = autonomyProfiles.indexOf(autonomy).coerceAtLeast(0)
                CycloneLiquidChoiceBar(
                    options = autonomyProfiles.map(::autonomyLabel),
                    selectedIndex = selectedIndex,
                    onSelect = { index ->
                        val profile = autonomyProfiles[index]
                        autonomy = profile
                        CycloneAiAccessProfileStore.write(context, profile)
                        step = OverlaySettingsStep.MODEL
                    },
                    modifier = Modifier.fillMaxWidth(),
                    compact = true,
                )
                Text(
                    autonomy.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StandardIntelligencePanel(modelId: String, effort: String, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val canonical = OpenRouterCatalogStore.canonicalId(modelId)

    Column(
        Modifier.widthIn(min = 252.dp, max = 320.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Intelligence", style = MaterialTheme.typography.titleSmall)
            Text(
                "Exact options for the selected OpenRouter model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canonical.isBlank()) {
            Text("Choose an available model first.", style = MaterialTheme.typography.bodySmall)
        } else {
            CycloneReasoningSelector(canonical) { selected -> onChange(modelId, selected) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Phone autonomy", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            CycloneLiquidChoiceBar(
                options = autonomyProfiles.map(::autonomyLabel),
                selectedIndex = autonomyProfiles.indexOf(autonomy).coerceAtLeast(0),
                onSelect = { index ->
                    autonomy = autonomyProfiles[index]
                    CycloneAiAccessProfileStore.write(context, autonomy)
                },
                modifier = Modifier.fillMaxWidth(),
                compact = true,
            )
            Text(
                autonomy.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Sensitive actions still ask for confirmation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Stable in-layout model picker: never moves backdrop-dependent controls into a Popup window. */
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
            ?: OpenRouterCatalogStore.preset(context, OpenRouterCatalogStore.activeId(context))
    }
    val selectedEffort = remember(catalogRevision, currentModel.id) { OpenRouterCatalogStore.reasoningSelection(context, currentModel.id) }
    val defaultEffort = remember(catalogRevision, currentModel.id) { OpenRouterCatalogStore.defaultReasoningEffort(context, currentModel.id) }
    val intelligenceLabel = selectedEffort?.let(::reasoningEffortLabel)
        ?: defaultEffort?.let { "${reasoningEffortLabel(it)} default" }
        ?: "Model controlled"

    Column(modifier, horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CycloneLiquidMenuTrigger(
            title = "Model",
            value = "${currentModel.label} · $intelligenceLabel",
            onClick = { if (enabled) open = !open },
            modifier = Modifier.widthIn(max = 240.dp),
            compact = true,
            enabled = enabled,
        )
        if (open) {
            CycloneLiquidPanel(
                modifier = Modifier.widthIn(min = 268.dp, max = 320.dp),
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(6.dp),
            ) {
                ModelPickerRows(pickerModels, currentModel) { option ->
                    OpenRouterCatalogStore.setActive(context, option.id)
                    onChange(
                        V39AiChatContract.storageId(option),
                        OpenRouterCatalogStore.reasoningSelection(context, option.id).orEmpty(),
                    )
                    open = false
                }
            }
        }
    }
}

/**
 * Legacy compact host retained for callers that need Tune + optional model pill. Expansion stays in
 * the same Compose hierarchy, so it cannot reproduce the popup/backdrop crash from 4.4.4.
 */
@Composable
fun CycloneIntelligenceControls(
    enabled: Boolean = true,
    showModelPill: Boolean = true,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    var intelligenceOpen by remember { mutableStateOf(false) }
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    var model by remember(catalogRevision) {
        mutableStateOf(V39AiChatContract.modelForStored(OpenRouterCatalogStore.activeId(context)))
    }

    fun persist(modelId: String, effort: String) {
        val resolved = V39AiChatContract.modelForStored(modelId)
        if (resolved.id.isNotBlank()) {
            OpenRouterCatalogStore.setActive(context, resolved.id)
            model = OpenRouterCatalogStore.preset(context, resolved.id)
        }
        onChanged()
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CycloneTrayIconAction(
                onClick = { intelligenceOpen = !intelligenceOpen },
                enabled = enabled,
                modifier = Modifier.size(44.dp),
            ) {
                Icon(
                    Icons.Rounded.Tune,
                    "Intelligence and phone autonomy",
                    Modifier.size(20.dp),
                    tint = if (intelligenceOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showModelPill) {
                CycloneModelPill(
                    modelId = V39AiChatContract.storageId(model),
                    effort = OpenRouterCatalogStore.reasoningSelection(context, model.id).orEmpty(),
                    enabled = enabled,
                    onChange = ::persist,
                )
            }
        }
        if (intelligenceOpen) {
            CycloneLiquidPanel(
                modifier = Modifier.widthIn(min = 268.dp, max = 320.dp),
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(6.dp),
            ) {
                CycloneModelIntelligencePanel(
                    modelId = V39AiChatContract.storageId(model),
                    effort = OpenRouterCatalogStore.reasoningSelection(context, model.id).orEmpty(),
                    showModelSelector = false,
                    onChange = ::persist,
                )
            }
        }
    }
}
