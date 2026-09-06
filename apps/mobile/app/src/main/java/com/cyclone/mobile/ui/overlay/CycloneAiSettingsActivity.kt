package com.cyclone.mobile.ui.overlay

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.model.ModelQualificationOutcome
import com.cyclone.mobile.ai.model.ModelQualificationRunner
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import kotlinx.coroutines.launch

/** Full AI configuration intentionally lives in the Cyclone app, never in the floating composer. */
class CycloneAiSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CycloneV32Theme {
                AiSettingsContent(context = this, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AiSettingsContent(context: Context, onBack: () -> Unit) {
    val prefs = remember { context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var selectedModelId by rememberSaveable {
        mutableStateOf(
            prefs.getString("openrouter_model", OpenRouterModelPresets.DEFAULT.id)
                .orEmpty()
                .ifBlank { OpenRouterModelPresets.DEFAULT.id },
        )
    }
    var reasoning by rememberSaveable {
        mutableStateOf(
            prefs.getString("openrouter_reasoning_effort", "medium")
                .orEmpty()
                .takeIf { it in REASONING_LEVELS } ?: "medium",
        )
    }
    var checking by remember { mutableStateOf(false) }
    var accessResult by remember { mutableStateOf<String?>(null) }
    var roleRefresh by remember { mutableStateOf(0) }
    val selectedModel = OpenRouterModelPresets.byId(selectedModelId)
    val roleManager = remember(roleRefresh) { context.getSystemService(RoleManager::class.java) }
    val roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    val assistantHeld = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val requestAssistant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleRefresh += 1
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Back")
                }
                Spacer(Modifier.weight(1f))
                Text("AI settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }

        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Model", fontWeight = FontWeight.Bold)
                        Text(
                            "Model choice and compatibility checks live here so the floating bar stays only about asking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { Text("Choose model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(OpenRouterModelPresets.all, key = { it.id }) { model ->
            FilterChip(
                selected = selectedModelId == model.id,
                onClick = {
                    selectedModelId = model.id
                    prefs.edit().putString("openrouter_model", model.id).apply()
                    accessResult = null
                },
                label = { Text(model.label) },
            )
        }

        item {
            SettingsCard {
                Text("Reasoning", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    REASONING_LEVELS.forEach { level ->
                        FilterChip(
                            selected = reasoning == level,
                            onClick = {
                                reasoning = level
                                prefs.edit().putString("openrouter_reasoning_effort", level).apply()
                            },
                            label = { Text(level.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Button(
                    enabled = !checking && OpenRouterSecretStore.hasKey(context),
                    onClick = {
                        checking = true
                        accessResult = null
                        scope.launch {
                            accessResult = try {
                                when (val result = ModelQualificationRunner(context).qualify(selectedModel)) {
                                    is ModelQualificationOutcome.Passed ->
                                        "${selectedModel.label}: ${if (result.cached) "recently verified" else "verified"} for this OpenRouter account."
                                    is ModelQualificationOutcome.Failed -> buildString {
                                        append(selectedModel.label)
                                        append(": ")
                                        append(result.failure.userMessage)
                                        if (result.failure.httpStatus > 0) {
                                            append(" (HTTP ").append(result.failure.httpStatus).append(')')
                                        }
                                        result.failure.providerMessage
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { append(" · ").append(it) }
                                    }
                                }
                            } catch (_: Exception) {
                                "Could not check model access. Try again."
                            }
                            checking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.size(7.dp))
                    Text(if (checking) "Checking model…" else "Check model access")
                }
                if (!OpenRouterSecretStore.hasKey(context)) {
                    Text(
                        "Add your OpenRouter API key in Cyclone Settings before checking model access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                accessResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Power button → Cyclone", fontWeight = FontWeight.Bold)
                        Text(
                            if (assistantHeld) "Cyclone is your selected Android assistant."
                            else "Choose Cyclone as Android's assistant, then set Press & hold power button to Digital assistant in system gesture settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (assistantHeld) Icon(Icons.Rounded.CheckCircle, "Enabled", tint = MaterialTheme.colorScheme.primary)
                }
                if (roleAvailable && !assistantHeld) {
                    Button(
                        onClick = {
                            requestAssistant.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Make Cyclone my assistant") }
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Android settings") }
                Text(
                    "Cyclone never intercepts the raw Power key. Android invokes the selected assistant through the system-owned assistant gesture.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

private val REASONING_LEVELS = listOf("low", "medium", "high", "max")
