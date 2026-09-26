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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.model.ModelQualificationOutcome
import com.cyclone.mobile.ai.model.ModelQualificationRunner
import com.cyclone.mobile.ui.v32.CycloneOpenRouterCatalog
import com.cyclone.mobile.ui.v32.CycloneReasoningSelector
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldMode
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldPrefs
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldStyle
import kotlinx.coroutines.launch

/** Full AI configuration intentionally lives in the Cyclone app, never in the floating composer. */
class CycloneAiSettingsActivity : ComponentActivity() {
    override fun onDestroy() {
        if (!isChangingConfigurations) com.cyclone.mobile.ui.overlay.OverlayExternalInteraction.active.value = false
        super.onDestroy()
    }
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
    val scope = rememberCoroutineScope()
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    var selectedModelId by rememberSaveable(catalogRevision) { mutableStateOf(OpenRouterCatalogStore.activeId(context)) }
    var backupModelId by rememberSaveable(catalogRevision) { mutableStateOf(OpenRouterCatalogStore.backupId(context)) }
    var checking by remember { mutableStateOf(false) }
    var accessResult by remember { mutableStateOf<String?>(null) }
    var roleRefresh by remember { mutableStateOf(0) }
    val selectedModel = remember(catalogRevision, selectedModelId) { OpenRouterCatalogStore.preset(context, selectedModelId) }
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

        item { SettingsCard { CycloneOpenRouterCatalog(context) } }
        item { Text("Choose model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(pickerModels, key = { it.id }) { model ->
            FilterChip(
                selected = selectedModelId == model.id,
                onClick = {
                    try {
                        OpenRouterCatalogStore.setActive(context, model.id)
                        selectedModelId = OpenRouterCatalogStore.activeId(context)
                        accessResult = null
                    } catch (failure: Exception) {
                        accessResult = failure.message
                    }
                },
                label = { Text(model.label) },
            )
        }

        item {
            SettingsCard {
                Text("When the main model is busy", fontWeight = FontWeight.Bold)
                Text(
                    "If the main model is rate-limited or down during a task, Cyclone can continue with a backup you choose. It never switches on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = backupModelId.isBlank(),
                    onClick = {
                        runCatching { OpenRouterCatalogStore.setBackup(context, "") }
                        backupModelId = OpenRouterCatalogStore.backupId(context)
                    },
                    label = { Text("Stop and tell me") },
                )
                pickerModels.filter { it.id != selectedModelId }.forEach { model ->
                    FilterChip(
                        selected = backupModelId == model.id,
                        onClick = {
                            try {
                                OpenRouterCatalogStore.setBackup(context, model.id)
                                backupModelId = OpenRouterCatalogStore.backupId(context)
                            } catch (failure: Exception) {
                                accessResult = failure.message
                            }
                        },
                        label = { Text("Backup: ${model.label}") },
                    )
                }
            }
        }

        item {
            SettingsCard {
                var mindOn by remember { mutableStateOf(com.cyclone.mobile.mind.mission.MindMissions.enabled(context)) }
                var minutes by remember { mutableStateOf(com.cyclone.mobile.mind.mission.MindMissions.workingMinutes(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cyclone Mind", fontWeight = FontWeight.Bold)
                        Text(
                            "One model works each request as a mission: it keeps the whole conversation, uses the phone's tools itself and asks you only when it needs you. Off uses the classic step agent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = mindOn, onCheckedChange = {
                        mindOn = it
                        com.cyclone.mobile.mind.mission.MindMissions.setEnabled(context, it)
                    })
                }
                if (mindOn) {
                    Spacer(Modifier.size(8.dp))
                    Text("Working time per mission: $minutes min", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 30, 60).forEach { value ->
                            FilterChip(
                                selected = minutes == value,
                                onClick = {
                                    minutes = value
                                    com.cyclone.mobile.mind.mission.MindMissions.setWorkingMinutes(context, value)
                                },
                                label = { Text("$value min") },
                            )
                        }
                    }
                    Text(
                        "Time you spend answering Cyclone does not count. A paused mission can be resumed from Ask.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsCard {
                // Planes (plan 25): where missions work. The pill on a running task switches either way.
                var planeMode by remember { mutableStateOf(com.cyclone.mobile.runtime.plane.MissionPlanes.mode(context)) }
                val blocker = remember { com.cyclone.mobile.runtime.plane.MissionPlanes.blocker(context) }
                Text("Where Cyclone works", fontWeight = FontWeight.Bold)
                Text(
                    when (planeMode) {
                        com.cyclone.mobile.runtime.plane.PlaneMode.AUTOMATIC -> "Automatic: when you are using your phone, Cyclone works on a background screen behind it; otherwise on your screen, where you can watch. Protected screens and apps that need you always come to your screen."
                        com.cyclone.mobile.runtime.plane.PlaneMode.SCREEN -> "Always on your screen, where you can watch every step."
                        com.cyclone.mobile.runtime.plane.PlaneMode.BACKGROUND -> "In the background whenever the app allows it. Steps that need you or your screen still come to it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cyclone.mobile.runtime.plane.PlaneMode.entries.forEach { mode ->
                        FilterChip(
                            selected = planeMode == mode,
                            onClick = {
                                planeMode = mode
                                com.cyclone.mobile.runtime.plane.MissionPlanes.setMode(context, mode)
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }
                blocker?.let {
                    Text("Background work is not available yet: $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Plan 26 (A42-4): what happens when background is wanted but not possible right now.
                if (planeMode != com.cyclone.mobile.runtime.plane.PlaneMode.SCREEN) {
                    var fallback by remember { mutableStateOf(com.cyclone.mobile.runtime.plane.MissionPlanes.fallback(context)) }
                    Text("When background isn't possible", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.cyclone.mobile.runtime.plane.PlaneFallback.entries.forEach { choice ->
                            FilterChip(selected = fallback == choice, onClick = {
                                fallback = choice
                                com.cyclone.mobile.runtime.plane.MissionPlanes.setFallback(context, choice)
                            }, label = { Text(choice.label) })
                        }
                    }
                }
            }
        }

        item {
            SettingsCard {
                // Plan 26 (A42-1): one switch, one answer, one next step.
                var capability by remember { mutableStateOf(com.cyclone.mobile.runtime.plane.MissionPlanes.capability(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background work", fontWeight = FontWeight.Bold)
                        Text(capability.headline, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = capability.level != com.cyclone.mobile.runtime.plane.CapabilityLevel.OFF &&
                            capability.level != com.cyclone.mobile.runtime.plane.CapabilityLevel.UNSUPPORTED,
                        enabled = capability.level != com.cyclone.mobile.runtime.plane.CapabilityLevel.UNSUPPORTED,
                        onCheckedChange = { on ->
                            com.cyclone.mobile.runtime.plane.MissionPlanes.setBackgroundOn(context, on)
                            capability = com.cyclone.mobile.runtime.plane.MissionPlanes.capability(context)
                        },
                    )
                }
                capability.action?.takeIf { it != com.cyclone.mobile.runtime.plane.CapabilityAction.TURN_ON }?.let { action ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(com.cyclone.mobile.runtime.plane.BackgroundWatch.fixIntent(context, action)) }
                    }) { Text(action.label) }
                }
                Text("Also in Quick Settings: add the Cyclone background tile. Glass on your PC can keep it on after restarts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Per app: seeded (banking, camera, games on your screen) and learned; the owner's choice wins.
                val compat = remember { com.cyclone.mobile.runtime.plane.MissionPlanes.compat(context) }
                var choices by remember { mutableStateOf(compat.choices()) }
                if (choices.isNotEmpty()) {
                    Text("Per app", style = MaterialTheme.typography.bodyMedium)
                    choices.entries.sortedBy { it.key }.take(30).forEach { (pkg, choice) ->
                        val label = remember(pkg) { runCatching { context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val next = com.cyclone.mobile.runtime.plane.PlaneOverride.entries.let { it[(it.indexOf(choice) + 1) % it.size] }
                                compat.setOverride(pkg, next)
                                choices = compat.choices()
                            }) { Text(choice.label) }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard {
                val memory = remember { com.cyclone.mobile.mind.mission.MindMissions.memory(context) }
                var facts by remember { mutableStateOf(memory.all()) }
                Text("What Cyclone Mind remembers", fontWeight = FontWeight.Bold)
                Text(
                    "Facts the Mind kept for future missions. It never keeps passwords, codes, keys or card numbers. Remove anything you don't want it to know.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (facts.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    facts.take(50).forEach { fact ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fact.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { memory.forget(fact.id); facts = memory.all() }) { Text("Forget") }
                        }
                    }
                    TextButton(onClick = { memory.forgetAll(); facts = memory.all() }) { Text("Forget everything") }
                }
            }
        }

        item {
            SettingsCard {
                Text("Intelligence", fontWeight = FontWeight.Bold)
                if (selectedModelId.isBlank()) {
                    Text("Choose an available model first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    CycloneReasoningSelector(selectedModelId)
                }
                Text(
                    "Cyclone uses only the exact reasoning efforts advertised by this model in OpenRouter. Changing intelligence never changes the model or provider route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !checking && selectedModelId.isNotBlank() && OpenRouterSecretStore.hasKey(context),
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
                                        if (result.failure.httpStatus > 0) append(" (HTTP ").append(result.failure.httpStatus).append(')')
                                        result.failure.providerMessage?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
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

        item { SettingsCard { WorkingIndicatorSettings(context) } }

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
                        onClick = { requestAssistant.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Make Cyclone my assistant") }
                }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
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

/** Shared by the AI settings screen and the main Settings → Appearance → Working indicator page. */
@Composable
internal fun ColumnScope.WorkingIndicatorSettings(context: Context) {
    var mode by remember { mutableStateOf(TraceFieldPrefs.mode(context)) }
    var style by remember { mutableStateOf(TraceFieldPrefs.style(context)) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    Text("Working indicator", fontWeight = FontWeight.Bold)
    Text(
        "Trace Field shows tiny digits under a soft lens that follows what Cyclone is looking at and tapping. It never covers your screen and is hidden from Cyclone's own screenshots.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            TraceFieldMode.FIELD to "Trace Field",
            TraceFieldMode.EDGE to "Edge only",
            TraceFieldMode.OFF to "Off",
        ).forEach { (option, label) ->
            FilterChip(
                selected = mode == option,
                onClick = {
                    TraceFieldPrefs.setMode(context, option)
                    mode = option
                    previewMessage = null
                },
                label = { Text(label) },
            )
        }
    }
    if (mode == TraceFieldMode.FIELD) {
        Text("Style", fontWeight = FontWeight.Bold)
        TraceFieldStyle.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    FilterChip(
                        selected = style == option,
                        onClick = {
                            TraceFieldPrefs.setStyle(context, option)
                            style = option
                            previewMessage = null
                        },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        Text(style.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedButton(
        enabled = mode != TraceFieldMode.OFF,
        onClick = {
            previewMessage = if (TraceFieldRuntime.preview()) {
                "Playing preview over this screen."
            } else {
                "Turn on Cyclone's accessibility service to preview."
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Preview") }
    previewMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(
        "Battery Saver switches Trace Field to Edge only. Remove animations shows a still field.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
