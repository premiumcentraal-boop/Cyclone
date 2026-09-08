package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.*

/** Preferences only: neither intelligence nor model selection grants action authority. */
@Composable
fun CycloneIntelligenceControls(enabled: Boolean = true, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cyclone_ai", android.content.Context.MODE_PRIVATE) }
    var open by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium") }
    var autonomy by remember { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Rounded.Tune, "Intelligence and phone autonomy") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.widthIn(max = 310.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Intelligence", style = MaterialTheme.typography.titleSmall)
                val levels = listOf("low", "medium", "high")
                Slider(value = (if (level == "max") 2 else levels.indexOf(level).coerceAtLeast(0)).toFloat(), valueRange = 0f..2f, steps = 1,
                    onValueChange = { level = levels[kotlin.math.round(it).toInt().coerceIn(0, 2)] },
                    onValueChangeFinished = { prefs.edit().putString("openrouter_reasoning_effort", level).apply(); onChanged() })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("Low", "Medium", "High").forEach { Text(it, style = MaterialTheme.typography.labelSmall) } }
                HorizontalDivider()
                Text("Phone autonomy", style = MaterialTheme.typography.titleSmall)
                listOf(CycloneAiAccessProfile.GUIDED to "Ask often", CycloneAiAccessProfile.BALANCED to "Balanced", CycloneAiAccessProfile.FULL to "Independent").forEach { (profile, label) ->
                    DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (profile == autonomy) Text("✓") }, onClick = {
                        autonomy = profile; CycloneAiAccessProfileStore.write(context, profile); onChanged()
                    })
                }
                Text("Sensitive actions always need your confirmation.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
