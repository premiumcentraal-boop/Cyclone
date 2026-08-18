package com.cyclone.mobile.ui

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterQuickAgent
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import kotlinx.coroutines.launch

/** V2.3 product shell: V2.2 onboarding + an always-available fast AI phone agent. */
@Composable
fun CycloneMobileV23App() {
    CycloneTheme {
        var open by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            CycloneMobileV22App()
            ExtendedFloatingActionButton(
                onClick = { open = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 88.dp),
                icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                text = { Text("AI") },
            )
            if (open) QuickAgentSheet(onDismiss = { open = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickAgentSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterQuickAgent(context.applicationContext) }

    var request by rememberSaveable { mutableStateOf("") }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var selectedModel by rememberSaveable {
        mutableStateOf(prefs.getString("openrouter_model", OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id).orEmpty())
    }
    var safeMode by rememberSaveable { mutableStateOf(prefs.getBoolean("safe_mode", true)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var result by remember { mutableStateOf("") }

    fun config() = QuickAgentConfig(
        model = OpenRouterModelPresets.byId(selectedModel),
        safeMode = safeMode,
        providerSort = "latency",
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Cyclone Quick Agent", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("V2.3 · fresh phone context every decision", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasKey) Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("OpenRouter", fontWeight = FontWeight.SemiBold)
                    }
                    if (!hasKey) {
                        Text("Your key is encrypted with Android Keystore and never written to logs or workflow files.")
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("OpenRouter API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                        Button(
                            onClick = {
                                runCatching { OpenRouterSecretStore.save(context, keyDraft) }
                                    .onSuccess {
                                        keyDraft = ""
                                        hasKey = true
                                        status = "OpenRouter connected"
                                    }
                                    .onFailure { result = it.message ?: "Could not save key" }
                            },
                            enabled = keyDraft.isNotBlank(),
                        ) { Text("Save key") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("API key secured on this phone", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                OpenRouterSecretStore.clear(context)
                                hasKey = false
                            }) { Text("Replace") }
                        }
                    }
                    Text(
                        "When Quick Agent runs, the current app name and selected visible UI text/labels are sent to your chosen OpenRouter model. Screenshots are sent only when the structured UI is not enough.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    )
                }
            }

            Text("Fast model", fontWeight = FontWeight.SemiBold)
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenRouterModelPresets.all.forEach { model ->
                    FilterChip(
                        selected = selectedModel == model.id,
                        onClick = {
                            selectedModel = model.id
                            prefs.edit().putString("openrouter_model", model.id).apply()
                        },
                        label = { Text(model.label) },
                    )
                }
            }
            Text(
                if (selectedModel == OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id)
                    "Default: fast text/tool decisions. Gemma 4 is used automatically if a screenshot needs vision."
                else "This model can also understand screenshot fallback directly; Cyclone still prefers the Accessibility tree first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = request,
                onValueChange = { request = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What should Cyclone do?") },
                placeholder = { Text("Open Settings, find Battery, and show me battery usage") },
                minLines = 3,
                maxLines = 6,
                enabled = !busy,
            )

            Card(shape = RoundedCornerShape(18.dp)) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Safe Mode", fontWeight = FontWeight.Medium)
                        Text("Blocks obvious payment, sending, purchase and destructive actions.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = safeMode,
                        onCheckedChange = {
                            safeMode = it
                            prefs.edit().putBoolean("safe_mode", it).apply()
                        },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = hasKey && request.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        result = ""
                        scope.launch {
                            val run = agent.execute(request, config()) { status = it }
                            result = run.message
                            status = if (run.ok) "Done in ${run.decisions} decisions" else "Stopped"
                            DeviceState.addLog("Quick Agent ${if (run.ok) "completed" else "stopped"}: ${run.message.take(120)}")
                            busy = false
                        }
                    },
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (busy) "Working…" else "Do it now")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = hasKey && request.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        result = ""
                        scope.launch {
                            val run = agent.buildWorkflow(request, config()) { status = it }
                            result = run.message
                            status = if (run.ok) "Workflow ready for review" else "Workflow rejected"
                            busy = false
                        }
                    },
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Build workflow")
                }
            }

            HorizontalDivider()
            Text(status, fontWeight = FontWeight.SemiBold)
            if (result.isNotBlank()) {
                Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "Cyclone observes the live UI before the first call, sends the model a compact semantic map, executes one phone tool at a time, then refreshes the environment before the next decision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
