package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.*

/** Shared compact controls. Preferences do not grant execution authority. */
@Composable
fun CycloneModelIntelligencePanel(modelId: String, effort: String, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    var models by remember { mutableStateOf(false) }
    var autonomyOpen by remember { mutableStateOf(false) }
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    Column(Modifier.widthIn(max = 300.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { models = !models }) {
            Text(OpenRouterModelPresets.byId(modelId).label + " ▾", style = MaterialTheme.typography.titleSmall)
        }
        if (models) Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            OpenRouterModelPresets.all.forEach { model ->
                TextButton(onClick = { onChange(model.id, effort); models = false }) {
                    Text((if (model.id == modelId) "✓ " else "") + model.label)
                }
            }
        } else {
            val levels = listOf("low", "medium", "high")
            Slider(value = (if (effort == "max") 2 else levels.indexOf(effort).coerceAtLeast(0)).toFloat(),
                valueRange = 0f..2f, steps = 1,
                onValueChange = { onChange(modelId, levels[kotlin.math.round(it).toInt().coerceIn(0, 2)]) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("Low", "Medium", "High").forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            TextButton(onClick = { autonomyOpen = !autonomyOpen }) { Text("Phone autonomy ▾", style = MaterialTheme.typography.labelSmall) }
            if (autonomyOpen) {
                listOf(CycloneAiAccessProfile.GUIDED to "Ask often", CycloneAiAccessProfile.BALANCED to "Balanced",
                    CycloneAiAccessProfile.FULL to "Independent").forEach { (profile, label) ->
                    TextButton(onClick = { autonomy = profile; CycloneAiAccessProfileStore.write(context, profile); autonomyOpen = false }) {
                        Text((if (autonomy == profile) "✓ " else "") + label)
                    }
                }
                Text("Sensitive actions always ask.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun CycloneIntelligenceControls(enabled: Boolean = true, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cyclone_ai", android.content.Context.MODE_PRIVATE) }
    var open by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium") }
    var model by remember { mutableStateOf(OpenRouterModelPresets.byId(prefs.getString("openrouter_model", "").orEmpty()).id) }
    Box {
        IconButton(onClick = { open = !open }, enabled = enabled) {
            Icon(Icons.Rounded.Tune, "Intelligence and phone autonomy", tint = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CycloneModelIntelligencePanel(model, level) { id, effort ->
                model = id; level = effort
                prefs.edit().putString("openrouter_model", id).putString("openrouter_reasoning_effort", effort).apply()
                onChanged()
            }
        }
    }
}
