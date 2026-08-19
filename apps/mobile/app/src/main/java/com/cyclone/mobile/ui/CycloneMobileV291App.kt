package com.cyclone.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OpenRouterCustomModelStore
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.applearner.LearnerSessionState
import com.cyclone.mobile.applearner.discardFollowMeSession
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class V291Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    TEACH("Teach", Icons.Rounded.School),
    AI("AI", Icons.Rounded.AutoAwesome),
    AUTOMATIONS("Automations", Icons.Rounded.Bolt),
    BRAIN("Brain", Icons.Rounded.AccountTree),
}

private enum class V291AiMode { PHONE, BRAIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycloneMobileV291App() {
    CycloneTheme {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(V291Tab.HOME) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var refreshTick by remember { mutableIntStateOf(0) }

        AutomationRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)
        CycloneBrainRuntime.initialize(context)
        BrainChatRuntime.initialize(context)
        RoutineTeachingRuntime.initialize(context)

        LaunchedEffect(Unit) {
            while (true) {
                delay(700)
                refreshTick++
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (settingsOpen) {
                            IconButton(onClick = { settingsOpen = false }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                        } else {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp).size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(if (settingsOpen) "Settings" else tab.label, fontWeight = FontWeight.SemiBold)
                            Text("Cyclone 2.9.1", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        if (!settingsOpen) {
                            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Rounded.SettingsIcon, "Settings") }
                        }
                    },
                )
            },
            bottomBar = {
                if (!settingsOpen) {
                    NavigationBar {
                        V291Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { tab = item },
                                icon = {
                                    if (item == V291Tab.AI) {
                                        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                            Icon(item.icon, item.label, tint = MaterialTheme.colorScheme.onPrimary)
                                        }
                                    } else Icon(item.icon, item.label)
                                },
                                label = { Text(item.label, fontWeight = if (item == V291Tab.AI) FontWeight.SemiBold else FontWeight.Normal) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (settingsOpen) V291SettingsPage(context, refreshTick) { refreshTick++ }
                else when (tab) {
                    V291Tab.HOME -> V291HomePage(context, refreshTick, onTeach = { tab = V291Tab.TEACH }, onAi = { tab = V291Tab.AI })
                    V291Tab.TEACH -> V291TeachPage(context, refreshTick)
                    V291Tab.AI -> V291AiPage(context, refreshTick)
                    V291Tab.AUTOMATIONS -> V291AutomationsPage(context, refreshTick)
                    V291Tab.BRAIN -> V291BrainPage(context, refreshTick)
                }
            }
        }
    }
}

@Composable
private fun V291HomePage(context: Context, refreshTick: Int, onTeach: () -> Unit, onAi: () -> Unit) {
    val phoneReady = v291AccessibilityEnabled(context)
    val notificationReady = v291NotificationEnabled(context)
    var hasKey by remember(refreshTick) { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    val coreReady = DeviceState.bridgeConnected
    val requiredDone = listOf(phoneReady, notificationReady, hasKey).count { it }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Start here", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Set up the three things Cyclone needs before you start teaching or running AI. This prevents getting halfway through a task and discovering a missing permission or key.")
                    Text("$requiredDone of 3 essentials ready", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            V291SetupCard(
                number = "1",
                icon = Icons.Rounded.Security,
                title = "Enable phone control",
                body = "Accessibility lets Cyclone understand the visible UI and perform approved phone actions.",
                done = phoneReady,
                actionLabel = if (phoneReady) "Open settings" else "Enable",
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        item {
            V291SetupCard(
                number = "2",
                icon = Icons.Rounded.Notifications,
                title = "Enable notification access",
                body = "This lets automations react to real notification events instead of constantly polling screenshots.",
                done = notificationReady,
                actionLabel = if (notificationReady) "Open settings" else "Enable",
            ) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (hasKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V291NumberBadge("3", hasKey)
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Rounded.Key, null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Add your OpenRouter API key", fontWeight = FontWeight.SemiBold)
                            Text(if (hasKey) "Key secured with Android Keystore." else "Paste the key once so Cyclone AI and teaching analysis are ready before you need them.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!hasKey) {
                        OutlinedTextField(
                            value = keyDraft,
                            onValueChange = { keyDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            label = { Text("OpenRouter API key") },
                            placeholder = { Text("sk-or-…") },
                        )
                        Button(
                            onClick = {
                                runCatching { OpenRouterSecretStore.save(context, keyDraft.trim()) }
                                    .onSuccess { keyDraft = ""; hasKey = true; Toast.makeText(context, "OpenRouter key secured", Toast.LENGTH_SHORT).show() }
                                    .onFailure { Toast.makeText(context, it.message ?: "Could not save key", Toast.LENGTH_LONG).show() }
                            },
                            enabled = keyDraft.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(6.dp)); Text("Secure API key") }
                    }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, null)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Optional: Cyclone Core", fontWeight = FontWeight.SemiBold)
                        Text(if (coreReady) "Core is connected." else "Pair Core in Settings for desktop/Hermes/remote features. Phone teaching works locally without it.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onTeach, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.School, null); Spacer(Modifier.width(5.dp)); Text("Teach") }
                Button(onClick = onAi, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(5.dp)); Text("Use AI") }
            }
        }
    }
}

@Composable
private fun V291TeachPage(context: Context, refreshTick: Int) {
    val follow = FollowMeLearnerRuntime.progress()
    val appProgress = AppLearnerRuntime.progress()
    val learnedApps = AppLearnerRuntime.learnedApps()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            V291Hero(
                icon = Icons.Rounded.School,
                title = "Teach Cyclone",
                body = "Show Cyclone what you do naturally, or build a routine manually step by step. Both teaching modes live here in 2.9.1.",
            )
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Follow Me", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Use the phone normally while Cyclone maps pages, controls and actions.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (follow.active) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            V291Metric("Apps", follow.appsSeen, Modifier.weight(1f))
                            V291Metric("Pages", follow.screensSeen, Modifier.weight(1f))
                            V291Metric("Actions", follow.actionsSeen, Modifier.weight(1f))
                        }
                        Text(follow.currentApp.ifBlank { "Following your phone" } + follow.currentScreen.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(
                            onClick = { if (follow.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(if (follow.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                            Spacer(Modifier.width(5.dp)); Text(if (follow.paused) "Resume Follow Me" else "Pause Follow Me")
                        }
                        Button(onClick = { FollowMeLearnerRuntime.stop() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(5.dp)); Text("Stop & review what Cyclone learned")
                        }
                        OutlinedButton(
                            onClick = {
                                discardFollowMeSession(context)
                                Toast.makeText(context, "Follow Me cancelled — no teaching report or AI review", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(5.dp)); Text("Cancel & discard") }
                        Text("Cancel & discard exits without compiling a workflow, opening a review page or running the post-session model analysis.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Button(
                            onClick = {
                                if (appProgress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) AppLearnerRuntime.stop()
                                FollowMeLearnerRuntime.start(context)
                                Toast.makeText(context, "Follow Me started — use the LEARN bubble while navigating", Toast.LENGTH_SHORT).show()
                                (context as? Activity)?.moveTaskToBack(true)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Start Follow Me & use my phone") }
                    }
                    Text("Typed text, passwords, OTPs and sensitive field contents are not stored by Follow Me.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Gesture, null, modifier = Modifier.size(31.dp))
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Manual routine teacher", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Build the routine deliberately: Tap, Hold, Swipe, Check, Wait, Back and Home. Cyclone stores before/after UI evidence and compiles the routine for review.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(
                        onClick = {
                            val service = CycloneAccessibilityService.instance
                            if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                            else {
                                service.showGuidedRecorderOverlay()
                                Toast.makeText(context, "Manual teacher opened", Toast.LENGTH_SHORT).show()
                                (context as? Activity)?.moveTaskToBack(true)
                            }
                        },
                        enabled = !follow.active,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Icon(Icons.Rounded.Gesture, null); Spacer(Modifier.width(6.dp)); Text("Open full manual teacher") }
                    if (follow.active) Text("Finish or cancel Follow Me before starting a separate manual recording.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedButton(onClick = { RoutineTeachingRuntime.launchReport(context, null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.History, null); Spacer(Modifier.width(6.dp)); Text("Teaching history & past reports")
            }
        }
        item { Text("Apps Cyclone has mapped", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (learnedApps.isEmpty()) item { V291Empty("No learned apps yet", "Start Follow Me and navigate through an app to build its semantic map.") }
        else items(learnedApps.take(30), key = { it.packageName }) { app ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.SemiBold)
                        Text("${(app.confidence * 100).toInt()}% knowledge · ${app.knowledgeState.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun V291AiPage(context: Context, refreshTick: Int) {
    val prefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterAdaptiveAgent(context.applicationContext) }
    var mode by rememberSaveable { mutableStateOf(V291AiMode.PHONE) }
    var phoneRequest by rememberSaveable { mutableStateOf("") }
    var brainRequest by rememberSaveable { mutableStateOf("") }
    var modelSlug by rememberSaveable { mutableStateOf(prefs.getString("openrouter_model", OpenRouterModelPresets.DEFAULT.id).orEmpty().ifBlank { OpenRouterModelPresets.DEFAULT.id }) }
    var customDraft by rememberSaveable { mutableStateOf("") }
    var customModels by remember(refreshTick) { mutableStateOf(OpenRouterCustomModelStore.list(context)) }
    var safeMode by rememberSaveable { mutableStateOf(prefs.getBoolean("safe_mode", true)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var chatTick by remember { mutableIntStateOf(0) }
    val hasKey = OpenRouterSecretStore.hasKey(context)
    val chatHistory = BrainChatRuntime.history(context, 16 + chatTick * 0)

    fun config() = QuickAgentConfig(
        model = OpenRouterModelPresets.byId(modelSlug),
        safeMode = safeMode,
        providerSort = "latency",
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(30.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp))
                    }
                    Text("Cyclone AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("Choose what you want the AI to do. Phone control acts on Android; Brain chat only reads and writes Cyclone knowledge.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                V291AiModeCard(
                    selected = mode == V291AiMode.PHONE,
                    icon = Icons.Rounded.Visibility,
                    title = "Control my phone",
                    body = "Ask Cyclone to open apps, navigate pages, use known skills or build a deterministic workflow.",
                ) { mode = V291AiMode.PHONE; result = ""; status = "" }
                V291AiModeCard(
                    selected = mode == V291AiMode.BRAIN,
                    icon = Icons.Rounded.AccountTree,
                    title = "Chat with Cyclone Brain",
                    body = "Ask what Cyclone has learned or save knowledge. This mode does not control the phone.",
                ) { mode = V291AiMode.BRAIN; result = ""; status = "" }
            }
        }

        if (mode == V291AiMode.PHONE) {
            item {
                V291Section("Phone task") {
                    OutlinedTextField(
                        value = phoneRequest,
                        onValueChange = { phoneRequest = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        enabled = !busy,
                        label = { Text("What should Cyclone do on my phone?") },
                        placeholder = { Text("Open Spotify and go to my downloads") },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                busy = true; result = ""; status = "Checking learned phone knowledge…"
                                scope.launch {
                                    val run = agent.execute(phoneRequest, config()) { status = it }
                                    result = run.message
                                    status = if (run.ok) "Completed with ${run.decisions} AI decision${if (run.decisions == 1) "" else "s"}." else "Stopped."
                                    busy = false
                                }
                            },
                            enabled = phoneRequest.isNotBlank() && !busy && hasKey,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (busy) "Working…" else "Do it") }
                        FilledTonalButton(
                            onClick = {
                                busy = true; result = ""; status = "Building a reviewable automation…"
                                scope.launch {
                                    val run = agent.buildWorkflow(phoneRequest, config()) { status = it }
                                    result = run.message; status = if (run.ok) "Automation created for review." else "Automation was not created."; busy = false
                                }
                            },
                            enabled = phoneRequest.isNotBlank() && !busy && hasKey,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.width(4.dp)); Text("Build automation") }
                    }
                    if (!hasKey) V291Notice(Icons.Rounded.Key, "OpenRouter key required", "Add it from the Home setup cards before starting an AI phone task.")
                    if (status.isNotBlank()) Text(status, fontWeight = FontWeight.Medium)
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                V291Section("Brain conversation") {
                    Text("Ask about learned apps, routes and skills, or type “Remember that …” to save a note. Brain chat does not press anything on your phone.", style = MaterialTheme.typography.bodySmall)
                    chatHistory.takeLast(8).forEach { message ->
                        Card(colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (message.role == "user") "You" else "Cyclone Brain", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                Text(message.text)
                            }
                        }
                    }
                    OutlinedTextField(value = brainRequest, onValueChange = { brainRequest = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text("Ask or teach the Brain") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val prompt = brainRequest.trim(); if (prompt.isBlank()) return@Button
                                brainRequest = ""; busy = true
                                scope.launch {
                                    result = BrainChatRuntime.chat(context, prompt, modelSlug)
                                    chatTick++; busy = false
                                }
                            },
                            enabled = brainRequest.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(4.dp)); Text("Chat") }
                        FilledTonalButton(
                            onClick = {
                                runCatching { BrainChatRuntime.saveKnowledge(context, brainRequest.trim()) }
                                    .onSuccess { brainRequest = ""; chatTick++; Toast.makeText(context, "Saved to Brain", Toast.LENGTH_SHORT).show() }
                            },
                            enabled = brainRequest.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(4.dp)); Text("Save knowledge") }
                    }
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            V291Section("OpenRouter model library") {
                Text("Pick a built-in model or save your own OpenRouter model slug. Saved custom models stay available after restarting Cyclone and also appear in Follow Me's teaching-model picker.", style = MaterialTheme.typography.bodySmall)
                Text("Built in", fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenRouterModelPresets.all.forEach { model ->
                        FilterChip(
                            selected = modelSlug == model.id,
                            onClick = { modelSlug = model.id; prefs.edit().putString("openrouter_model", model.id).apply() },
                            label = { Text(model.label) },
                        )
                    }
                }
                if (customModels.isNotEmpty()) {
                    Text("Saved by you", fontWeight = FontWeight.SemiBold)
                    customModels.forEach { model ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = modelSlug == model.id,
                                onClick = { modelSlug = model.id; prefs.edit().putString("openrouter_model", model.id).apply() },
                                label = { Text(model.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = {
                                OpenRouterCustomModelStore.remove(context, model.id)
                                customModels = OpenRouterCustomModelStore.list(context)
                                if (modelSlug == model.id) {
                                    modelSlug = OpenRouterModelPresets.DEFAULT.id
                                    prefs.edit().putString("openrouter_model", modelSlug).apply()
                                }
                            }) { Icon(Icons.Rounded.Close, "Remove model") }
                        }
                    }
                }
                OutlinedTextField(
                    value = customDraft,
                    onValueChange = { customDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Add OpenRouter model slug") },
                    placeholder = { Text("provider/model-name") },
                )
                Button(
                    onClick = {
                        OpenRouterCustomModelStore.add(context, customDraft).fold(
                            onSuccess = { model ->
                                customModels = OpenRouterCustomModelStore.list(context)
                                modelSlug = model.id
                                prefs.edit().putString("openrouter_model", model.id).apply()
                                customDraft = ""
                                Toast.makeText(context, "Model saved", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { Toast.makeText(context, it.message ?: "Invalid model slug", Toast.LENGTH_LONG).show() },
                        )
                    },
                    enabled = customDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(5.dp)); Text("Save model") }
                Text("Current: $modelSlug", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Safe Mode", fontWeight = FontWeight.SemiBold)
                        Text("Blocks obvious purchase, payment, send and destructive actions.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = safeMode, onCheckedChange = { safeMode = it; prefs.edit().putBoolean("safe_mode", it).apply() })
                }
            }
        }
    }
}

@Composable
private fun V291AutomationsPage(context: Context, refreshTick: Int) {
    val automations = AutomationRuntime.store.listAutomations()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> automations.firstOrNull { it.id == id } }

    if (selected != null) {
        V291AutomationDetail(context, selected, onBack = { selectedId = null })
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V291Hero(Icons.Rounded.Bolt, "Automations", "Open any automation to see exactly what it will do before you run or enable it.") }
        if (automations.isEmpty()) item { V291Empty("No automations yet", "Teach one from the Teach tab or ask Cyclone AI to build a workflow.") }
        else items(automations, key = { it.id }) { automation ->
            Card(onClick = { selectedId = automation.id }, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(automation.name, fontWeight = FontWeight.SemiBold)
                            Text("${automation.steps.size} steps · ${automation.trigger.type.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Rounded.Info, "View details", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (automation.description.isNotBlank()) Text(automation.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (automation.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelMedium, color = if (automation.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun V291AutomationDetail(context: Context, automation: AutomationDefinition, onBack: () -> Unit) {
    var enabled by remember(automation.id, automation.enabled) { mutableStateOf(automation.enabled) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("All automations") }
            Spacer(Modifier.height(10.dp))
            V291Hero(Icons.Rounded.Bolt, automation.name, automation.description.ifBlank { "Review every step before running this automation." })
        }
        item {
            V291Section("How it starts") {
                Text("Trigger: ${automation.trigger.type.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.SemiBold)
                val safeTrigger = automation.trigger.parameters.filterKeys(::v291SafeParameterKey)
                safeTrigger.forEach { (key, value) -> Text("${key.replace('_', ' ')}: $value", style = MaterialTheme.typography.bodySmall) }
                if (automation.conditions.isNotEmpty()) Text("${automation.conditions.size} precondition${if (automation.conditions.size == 1) "" else "s"} checked before execution.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Steps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(automation.steps.withIndex().toList(), key = { it.value.id }) { indexed ->
            V291AutomationStep(indexed.index + 1, indexed.value)
        }
        if (automation.verification.isNotEmpty()) item {
            V291Section("Final verification") {
                automation.verification.forEachIndexed { index, condition ->
                    Text("${index + 1}. ${condition.left} ${condition.operator.name.lowercase().replace('_', ' ')} ${condition.right.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            V291Section("Run controls") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automation enabled", fontWeight = FontWeight.SemiBold)
                        Text("Enabled automations may run when their trigger fires.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        AutomationRuntime.store.saveAutomation(automation.copy(enabled = it))
                    })
                }
                Button(onClick = {
                    AutomationRuntime.router.runManual(automation.id)
                    Toast.makeText(context, "Automation started", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Run now") }
            }
        }
    }
}

@Composable
private fun V291AutomationStep(index: Int, step: StepDefinition) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text(index.toString(), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.name.ifBlank { step.type.name.lowercase().replace('_', ' ') }, fontWeight = FontWeight.SemiBold)
                    Text(step.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(v291DescribeAutomationStep(step), style = MaterialTheme.typography.bodySmall)
            if (step.confirmationRequired) Text("Requires your confirmation before execution.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (step.recovery.maxRetries > 0 || step.recovery.onFailure.name != "ABORT") {
                Text("Recovery: ${step.recovery.maxRetries} retr${if (step.recovery.maxRetries == 1) "y" else "ies"}; then ${step.recovery.onFailure.name.lowercase().replace('_', ' ')}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun v291DescribeAutomationStep(step: StepDefinition): String {
    val tool = step.parameters["tool"]?.removePrefix("phone.")?.replace('_', ' ')
    val selector = step.selector
    val target = selector?.text?.takeIf(String::isNotBlank)
        ?: selector?.contentDescription?.takeIf(String::isNotBlank)
        ?: selector?.resourceId?.substringAfterLast('/')?.takeIf(String::isNotBlank)
        ?: selector?.role?.takeIf(String::isNotBlank)
    val safe = step.parameters.filterKeys(::v291SafeParameterKey)
        .filterKeys { it != "tool" }
        .entries.take(3)
        .joinToString(" · ") { (key, value) -> "${key.replace('_', ' ')}=$value" }
    return buildString {
        append(tool?.replaceFirstChar(Char::uppercase) ?: step.type.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase))
        target?.let { append(" → $it") }
        if (safe.isNotBlank()) append(" · $safe")
    }
}

private fun v291SafeParameterKey(key: String): Boolean {
    val normalized = key.lowercase()
    return listOf("password", "secret", "token", "api_key", "apikey", "credential", "value", "text").none { it in normalized }
}

@Composable
private fun V291BrainPage(context: Context, refreshTick: Int) {
    val adaptive = AdaptiveBrainRuntime.store
    val oldBrain = CycloneBrainRuntime.store
    val skills = adaptive.listMicroSkills(50)
    val apps = adaptive.listApps()
    val paths = adaptive.listPaths(20)
    val notes = adaptive.listNotes(20)
    val reports = oldBrain.listReports(20)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { V291Hero(Icons.Rounded.AccountTree, "Cyclone Brain", "Inspect the local evidence Cyclone has actually learned from successful phone actions, app maps, paths and your notes.") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V291Metric("Skills", skills.size, Modifier.weight(1f))
                V291Metric("Apps", apps.size, Modifier.weight(1f))
                V291Metric("Paths", paths.size, Modifier.weight(1f))
            }
        }
        item { Text("Strongest reusable skills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (skills.isEmpty()) item { V291Empty("No verified skills yet", "Run a phone task or teach Cyclone first.") }
        else items(skills.take(20), key = { it.signature }) { skill ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(skill.name, fontWeight = FontWeight.SemiBold)
                    Text("${(skill.confidence * 100).toInt()}% · ${skill.successCount} success / ${skill.failureCount} failure", style = MaterialTheme.typography.bodySmall)
                    Text(skill.tool, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (notes.isNotEmpty()) item {
            V291Section("Your saved knowledge") { notes.take(10).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) } }
        }
        if (reports.isNotEmpty()) item {
            V291Section("Recent AI reports") { reports.take(8).forEach { Text("• ${it.goal}: ${it.summary.take(180)}", style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun V291SettingsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    val defaultName = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ")
    val defaultId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.let { "android-$it" }.orEmpty()
    var coreUrl by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultName).orEmpty()) }
    val calendarReady = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { V291Hero(Icons.Rounded.SettingsIcon, "Cyclone settings", "Permissions and Core connection settings in one place. OpenRouter models and key setup are available directly from Home and AI.") }
        item {
            V291Section("Phone permissions") {
                V291PermissionLine("Accessibility", v291AccessibilityEnabled(context), "Required for UI understanding and phone control") {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                HorizontalDivider()
                V291PermissionLine("Notification access", v291NotificationEnabled(context), "Needed for notification-triggered automations") {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                HorizontalDivider()
                V291PermissionLine("Calendar", calendarReady, "Optional for calendar-aware automations") {
                    (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 291)
                }
            }
        }
        item {
            V291Section("Cyclone Core") {
                OutlinedTextField(value = coreUrl, onValueChange = { coreUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("WebSocket URL") })
                OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pairing token") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = deviceName, onValueChange = { deviceName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device name") })
                Button(
                    onClick = {
                        prefs.edit()
                            .putString("coreWsUrl", coreUrl.trim())
                            .putString("coreToken", token.trim())
                            .putString("deviceId", prefs.getString("deviceId", defaultId).orEmpty().ifBlank { defaultId })
                            .putString("deviceName", deviceName.trim().ifBlank { defaultName })
                            .apply()
                        BridgeClient.stop(); BridgeClient.start(context); refresh()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(5.dp)); Text(if (DeviceState.bridgeConnected) "Reconnect" else "Save & connect") }
                V291Status("Core connected", DeviceState.bridgeConnected)
            }
        }
        item { V291Notice(Icons.Rounded.Info, "Cyclone 2.9.1", "Teach-first navigation, reviewable automation details, clearer AI modes, first-run setup cards and a persistent custom OpenRouter model library.") }
    }
}

@Composable
private fun V291AiModeCard(selected: Boolean, icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun V291SetupCard(number: String, icon: ImageVector, title: String, body: String, done: Boolean, actionLabel: String, action: () -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                V291NumberBadge(number, done)
                Spacer(Modifier.width(10.dp))
                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(body, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Text(if (done) "✓ $actionLabel" else actionLabel) }
        }
    }
}

@Composable
private fun V291NumberBadge(number: String, done: Boolean) {
    Box(Modifier.size(30.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(if (done) "✓" else number, color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun V291Hero(icon: ImageVector, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, modifier = Modifier.size(36.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(body)
        }
    }
}

@Composable
private fun V291Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun V291Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(11.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun V291Notice(icon: ImageVector, title: String, body: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null); Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun V291Empty(title: String, body: String) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun V291Status(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(7.dp)); Text(label)
    }
}

@Composable
private fun V291PermissionLine(title: String, enabled: Boolean, body: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = action) { Text(if (enabled) "Open" else "Set up") }
    }
}

private fun v291AccessibilityEnabled(context: Context): Boolean {
    val component = ComponentName(context, CycloneAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}

private fun v291NotificationEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
