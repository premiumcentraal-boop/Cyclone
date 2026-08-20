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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OpenRouterCustomModelStore
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.TaskResultActivityV292
import com.cyclone.mobile.ai.TaskResultNotifierV292
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
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292
import com.cyclone.mobile.debug.PageDebugSandboxV293
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class V292Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    TEACH("Teach", Icons.Rounded.School),
    AI("AI", Icons.Rounded.AutoAwesome),
    AUTOMATIONS("Automations", Icons.Rounded.Bolt),
    BRAIN("Brain", Icons.Rounded.AccountTree),
}
private enum class V292AiMode { PHONE, BRAIN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycloneMobileV292App() {
    CycloneTheme {
        val context = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(V292Tab.HOME) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var refreshTick by remember { mutableIntStateOf(0) }

        AutomationRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)
        CycloneBrainRuntime.initialize(context)
        BrainChatRuntime.initialize(context)
        RoutineTeachingRuntime.initialize(context)
        AgentTraceRuntime.initialize(context)
        TaskResultNotifierV292.ensureChannel(context)

        LaunchedEffect(Unit) {
            while (true) { delay(700); refreshTick++ }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (settingsOpen) IconButton(onClick = { settingsOpen = false }) { Icon(Icons.Rounded.ArrowBack, "Back") }
                        else Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp).size(42.dp).clickable { settingsOpen = true },
                        ) { Box(contentAlignment = Alignment.Center) { Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black) } }
                    },
                    title = {
                        Column {
                            Text(if (settingsOpen) "Settings" else tab.label, fontWeight = FontWeight.SemiBold)
                            Text(CycloneRelease.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {},
                )
            },
            bottomBar = {
                if (!settingsOpen) NavigationBar {
                    V292Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = {
                                if (item == V292Tab.AI) Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                    Icon(item.icon, item.label, tint = MaterialTheme.colorScheme.onPrimary)
                                } else Icon(item.icon, item.label)
                            },
                            label = { Text(item.label, fontWeight = if (item == V292Tab.AI) FontWeight.SemiBold else FontWeight.Normal) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (settingsOpen) V292SettingsPage(context, refreshTick) { refreshTick++ }
                else when (tab) {
                    V292Tab.HOME -> V292HomePage(context, refreshTick, { tab = V292Tab.TEACH }, { tab = V292Tab.AI })
                    V292Tab.TEACH -> V292TeachPage(context, refreshTick)
                    V292Tab.AI -> V292AiPage(context, refreshTick)
                    V292Tab.AUTOMATIONS -> V292AutomationsPage(context, refreshTick)
                    V292Tab.BRAIN -> V292BrainPage(context, refreshTick)
                }
            }
        }
    }
}

@Composable
private fun V292HomePage(context: Context, refreshTick: Int, onTeach: () -> Unit, onAi: () -> Unit) {
    val phoneReady = v292AccessibilityEnabled(context)
    val listenerReady = v292NotificationListenerEnabled(context)
    val resultNotifications = v292ResultNotificationsEnabled(context)
    var hasKey by remember(refreshTick) { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    val essentials = listOf(phoneReady, listenerReady, resultNotifications, hasKey).count { it }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            V292Hero(Icons.Rounded.AutoAwesome, "Set Cyclone up once", "Get phone control, event access, result notifications and your OpenRouter model ready before running or teaching anything.")
            Text("$essentials of 4 essentials ready", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        item { V292SetupCard("1", Icons.Rounded.Security, "Phone control", "Accessibility gives Cyclone the semantic Android UI map and approved actions.", phoneReady, if (phoneReady) "Open settings" else "Enable") {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
        item { V292SetupCard("2", Icons.Rounded.Notifications, "Notification event access", "Lets automations react to real events without screenshot polling.", listenerReady, if (listenerReady) "Open settings" else "Enable") {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } }
        item { V292SetupCard("3", Icons.Rounded.History, "AI result notifications", "After a phone task, Cyclone sends a tappable result so you can inspect the final decision, error and learning timeline.", resultNotifications, if (resultNotifications) "Enabled" else "Allow") {
            if (Build.VERSION.SDK_INT >= 33) {
                val activity = context as? Activity
                if (activity != null) ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 292)
            }
        } }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (hasKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V292NumberBadge("4", hasKey); Spacer(Modifier.width(10.dp)); Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("OpenRouter API key", fontWeight = FontWeight.SemiBold)
                            Text(if (hasKey) "Secured with Android Keystore." else "Required for unknown-page reasoning, vision fallback and one-pass learning consolidation.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (!hasKey) {
                        OutlinedTextField(keyDraft, { keyDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("OpenRouter API key") }, visualTransformation = PasswordVisualTransformation())
                        Button(onClick = {
                            runCatching { OpenRouterSecretStore.save(context, keyDraft.trim()) }
                                .onSuccess { keyDraft = ""; hasKey = true; Toast.makeText(context, "OpenRouter key secured", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, it.message ?: "Could not save key", Toast.LENGTH_LONG).show() }
                        }, enabled = keyDraft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(5.dp)); Text("Secure key") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(9.dp))
                    Column { Text("Cyclone Core is optional", fontWeight = FontWeight.SemiBold); Text(if (DeviceState.bridgeConnected) "Core connected." else "Phone learning and AI can run locally on the device. Pair Core in Settings for desktop/Hermes/remote features.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onTeach, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.School, null); Spacer(Modifier.width(5.dp)); Text("Teach") }
            Button(onClick = onAi, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(5.dp)); Text("Use AI") }
        } }
    }
}

@Composable
private fun V292TeachPage(context: Context, refreshTick: Int) {
    val follow = FollowMeLearnerRuntime.progress()
    val appProgress = AppLearnerRuntime.progress()
    val learnedApps = AppLearnerRuntime.learnedApps()
    val gestureCount = follow.teachingSessionId?.let { TeachingGestureEvidenceV292.list(context, it).size } ?: 0

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { V292Hero(Icons.Rounded.School, "Teach Cyclone", "Page Awareness Sandbox lets you freeze a real page and inspect raw Android evidence, semantic controls, the exact Page Agent payload, Brain/App Graph recall and execution-free model probes.") }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(32.dp)); Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) { Text("Follow Me", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("Swipe left/right, tap icons and navigate normally. Cyclone links the before/after pages into reusable knowledge.", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (follow.active) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            V292Metric("Pages", follow.screensSeen, Modifier.weight(1f)); V292Metric("Actions", follow.actionsSeen, Modifier.weight(1f)); V292Metric("Swipes", gestureCount, Modifier.weight(1f))
                        }
                        Text(follow.currentApp.ifBlank { "Following your phone" } + follow.currentScreen.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { if (follow.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(if (follow.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(5.dp)); Text(if (follow.paused) "Resume Follow Me" else "Pause Follow Me")
                        }
                        Button(onClick = { FollowMeLearnerRuntime.stop() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(5.dp)); Text("Stop, consolidate & review") }
                        OutlinedButton(onClick = {
                            discardFollowMeSession(context)
                            Toast.makeText(context, "Follow Me cancelled — no review or model consolidation", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(5.dp)); Text("Cancel & discard") }
                    } else {
                        Button(onClick = {
                            if (appProgress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) AppLearnerRuntime.stop()
                            FollowMeLearnerRuntime.start(context)
                            Toast.makeText(context, "Follow Me started — swipe, tap and navigate naturally", Toast.LENGTH_SHORT).show()
                            (context as? Activity)?.moveTaskToBack(true)
                        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(5.dp)); Text("Start Follow Me") }
                    }
                    Text("Typed text, passwords, OTPs and sensitive field contents are not stored. Stop runs at most one compact teaching-consolidation model call.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Gesture, null); Spacer(Modifier.width(8.dp)); Column { Text("Manual routine teacher", fontWeight = FontWeight.Bold); Text("Place exact Tap, Hold, Swipe, Check, Wait, Back and Home steps with before/after evidence.", style = MaterialTheme.typography.bodySmall) } }
                    Button(onClick = {
                        val service = CycloneAccessibilityService.instance
                        if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                        else { service.showGuidedRecorderOverlay(); (context as? Activity)?.moveTaskToBack(true) }
                    }, enabled = !follow.active, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Gesture, null); Spacer(Modifier.width(5.dp)); Text("Open manual teacher") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Page Awareness Sandbox", fontWeight = FontWeight.Bold)
                            Text("Freeze what Android sees, what PageContext keeps, what the Page Agent actually receives, and A/B-test the harness without executing actions.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Button(onClick = {
                        val service = CycloneAccessibilityService.instance
                        if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                        else {
                            PageDebugSandboxV293.start(service)
                            Toast.makeText(context, "PAGE DEBUG overlay started — capture the target app pages", Toast.LENGTH_SHORT).show()
                            (context as? Activity)?.moveTaskToBack(true)
                        }
                    }, enabled = !follow.active, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(5.dp)); Text("Start page sandbox")
                    }
                    OutlinedButton(onClick = { PageDebugSandboxV293.launchReport(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Info, null); Spacer(Modifier.width(5.dp)); Text("Open sandbox inspector")
                    }
                    Text("Model A/B probes are opt-in, use the selected OpenRouter model, make five calls on one frozen page, and never execute their proposed actions.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { OutlinedButton(onClick = { RoutineTeachingRuntime.launchReport(context, null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(5.dp)); Text("Teaching history, AI notes & corrections") } }
        item { Text("Apps Cyclone has mapped", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (learnedApps.isEmpty()) item { V292Empty("No mapped apps yet", "Use Follow Me and navigate through the app once.") }
        else items(learnedApps.take(30), key = { it.packageName }) { app ->
            Card(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(app.label, fontWeight = FontWeight.SemiBold); Text("${(app.confidence * 100).toInt()}% knowledge · ${app.knowledgeState.name.lowercase()}", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }
}

@Composable
private fun V292AiPage(context: Context, refreshTick: Int) {
    val prefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterAdaptiveAgent(context.applicationContext) }
    var mode by rememberSaveable { mutableStateOf(V292AiMode.PHONE) }
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

    fun config() = QuickAgentConfig(model = OpenRouterModelPresets.byId(modelSlug), safeMode = safeMode, providerSort = "latency")

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(30.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(34.dp)) }
                    Text("Cyclone AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("One continuous phone mission. Cyclone checks learned Brain/App Graph routes first, uses AI only for unknown semantic states, and vision only when the UI map is not enough.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f))
                }
            }
        }
        item { GatewayAiCard(context, refreshTick) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                V292ModeCard(mode == V292AiMode.PHONE, Icons.Rounded.Visibility, "Control my phone", "Run an autonomous phone task with the transparent live decision HUD.") { mode = V292AiMode.PHONE; result = ""; status = "" }
                V292ModeCard(mode == V292AiMode.BRAIN, Icons.Rounded.AccountTree, "Chat with Cyclone Brain", "Ask what Cyclone has learned or save knowledge without touching the phone.") { mode = V292AiMode.BRAIN; result = ""; status = "" }
            }
        }
        if (mode == V292AiMode.PHONE) {
            item {
                V292Section("Phone task") {
                    V292Notice(Icons.Rounded.AutoAwesome, "Live transparent decision HUD", "Starts automatically over the app. You see UI understanding, decisions, green verification, red recovery/errors, final status, and result compilation — not private hidden chain-of-thought.")
                    OutlinedTextField(phoneRequest, { phoneRequest = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, enabled = !busy, label = { Text("What should Cyclone do on my phone?") }, placeholder = { Text("Open the app and navigate to my saved items") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true; result = ""; status = "Starting one agentic task session…"
                            scope.launch {
                                val run = agent.execute(phoneRequest, config()) { status = it }
                                result = run.message
                                status = if (run.ok) "Task completed. Results are being consolidated." else "Task stopped/failed. Recovery evidence is being consolidated."
                                busy = false
                            }
                        }, enabled = phoneRequest.isNotBlank() && !busy && hasKey, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (busy) "Working…" else "Do it") }
                        FilledTonalButton(onClick = {
                            busy = true; result = ""; status = "Building a reviewable automation…"
                            scope.launch {
                                val run = agent.buildWorkflow(phoneRequest, config()) { status = it }
                                result = run.message; status = if (run.ok) "Automation created for review." else "Automation was not created."; busy = false
                            }
                        }, enabled = phoneRequest.isNotBlank() && !busy && hasKey, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.width(4.dp)); Text("Build automation") }
                    }
                    if (!hasKey) V292Notice(Icons.Rounded.Key, "OpenRouter key required", "Add it on Home before asking Cyclone to solve unknown phone states.")
                    if (status.isNotBlank()) Text(status, fontWeight = FontWeight.Medium)
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val latest = AgentTraceRuntime.store.listSessions(1).firstOrNull()
                    if (latest != null && latest.status != "RUNNING") OutlinedButton(onClick = {
                        context.startActivity(Intent(context, TaskResultActivityV292::class.java).putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, latest.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(5.dp)); Text("Open latest decision timeline") }
                }
            }
        } else {
            item {
                V292Section("Brain conversation") {
                    chatHistory.takeLast(8).forEach { message -> Card(colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(12.dp)) { Text(if (message.role == "user") "You" else "Cyclone Brain", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium); Text(message.text) } } }
                    OutlinedTextField(brainRequest, { brainRequest = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text("Ask or teach the Brain") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { val prompt = brainRequest.trim(); if (prompt.isNotBlank()) { brainRequest = ""; busy = true; scope.launch { result = BrainChatRuntime.chat(context, prompt, modelSlug); chatTick++; busy = false } } }, enabled = brainRequest.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(4.dp)); Text("Chat") }
                        FilledTonalButton(onClick = { runCatching { BrainChatRuntime.saveKnowledge(context, brainRequest.trim()) }.onSuccess { brainRequest = ""; chatTick++ } }, enabled = brainRequest.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(4.dp)); Text("Save knowledge") }
                    }
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            V292Section("OpenRouter models") {
                Text("Built-in and your saved custom model slugs are persistent. The selected model is also used for teaching/correction consolidation.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenRouterModelPresets.all.forEach { model -> FilterChip(selected = modelSlug == model.id, onClick = { modelSlug = model.id; prefs.edit().putString("openrouter_model", model.id).apply() }, label = { Text(model.label) }) }
                }
                customModels.forEach { model -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = modelSlug == model.id, onClick = { modelSlug = model.id; prefs.edit().putString("openrouter_model", model.id).apply() }, label = { Text(model.id, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { OpenRouterCustomModelStore.remove(context, model.id); customModels = OpenRouterCustomModelStore.list(context); if (modelSlug == model.id) { modelSlug = OpenRouterModelPresets.DEFAULT.id; prefs.edit().putString("openrouter_model", modelSlug).apply() } }) { Icon(Icons.Rounded.Close, "Remove model") }
                } }
                OutlinedTextField(customDraft, { customDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Add OpenRouter model slug") }, placeholder = { Text("provider/model-name") })
                Button(onClick = { OpenRouterCustomModelStore.add(context, customDraft).fold(onSuccess = { model -> customModels = OpenRouterCustomModelStore.list(context); modelSlug = model.id; prefs.edit().putString("openrouter_model", model.id).apply(); customDraft = "" }, onFailure = { Toast.makeText(context, it.message ?: "Invalid model", Toast.LENGTH_LONG).show() }) }, enabled = customDraft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(5.dp)); Text("Save model") }
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Safe Mode", fontWeight = FontWeight.SemiBold); Text("Stops purchase, payment, send and destructive boundaries.", style = MaterialTheme.typography.bodySmall) }; Switch(safeMode, { safeMode = it; prefs.edit().putBoolean("safe_mode", it).apply() }) }
            }
        }
    }
}

@Composable
private fun V292AutomationsPage(context: Context, refreshTick: Int) {
    val automations = AutomationRuntime.store.listAutomations()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> automations.firstOrNull { it.id == id } }
    if (selected != null) { V292AutomationDetail(context, selected) { selectedId = null }; return }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V292Hero(Icons.Rounded.Bolt, "Automations", "Taught and AI-built routines live here. Open one to inspect every step before enabling it.") }
        if (automations.isEmpty()) item { V292Empty("No automations yet", "Use Follow Me, the manual teacher or Build automation in AI.") }
        else items(automations, key = { it.id }) { automation ->
            Card(onClick = { selectedId = automation.id }, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(automation.name, fontWeight = FontWeight.SemiBold); Text("${automation.steps.size} steps · ${automation.trigger.type.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall) }; Icon(Icons.Rounded.Info, "Details") }
                if (automation.description.isNotBlank()) Text(automation.description, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(if (automation.enabled) "Enabled" else "Disabled for review", color = if (automation.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            } }
        }
    }
}

@Composable
private fun V292AutomationDetail(context: Context, automation: AutomationDefinition, onBack: () -> Unit) {
    var enabled by remember(automation.id, automation.enabled) { mutableStateOf(automation.enabled) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { OutlinedButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("All automations") }; Spacer(Modifier.height(9.dp)); V292Hero(Icons.Rounded.Bolt, automation.name, automation.description.ifBlank { "Review the exact route before running it." }) }
        item { V292Section("How it starts") { Text("Trigger: ${automation.trigger.type.name.lowercase().replace('_', ' ')}", fontWeight = FontWeight.SemiBold); automation.trigger.parameters.filterKeys(::v292SafeParameterKey).forEach { (k, v) -> Text("${k.replace('_', ' ')}: $v", style = MaterialTheme.typography.bodySmall) } } }
        item { Text("Steps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(automation.steps.withIndex().toList(), key = { it.value.id }) { indexed -> V292AutomationStep(indexed.index + 1, indexed.value) }
        if (automation.verification.isNotEmpty()) item { V292Section("Final verification") { automation.verification.forEachIndexed { i, c -> Text("${i + 1}. ${c.left} ${c.operator.name.lowercase().replace('_', ' ')} ${c.right.orEmpty()}", style = MaterialTheme.typography.bodySmall) } } }
        item { V292Section("Run controls") {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Automation enabled", fontWeight = FontWeight.SemiBold); Text("Keep newly learned routines disabled until you have reviewed them.", style = MaterialTheme.typography.bodySmall) }; Switch(enabled, { enabled = it; AutomationRuntime.store.saveAutomation(automation.copy(enabled = it)) }) }
            Button(onClick = { AutomationRuntime.router.runManual(automation.id); Toast.makeText(context, "Automation started", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Run now") }
        } }
    }
}

@Composable
private fun V292BrainPage(context: Context, refreshTick: Int) {
    val adaptive = AdaptiveBrainRuntime.store
    val skills = adaptive.listMicroSkills(60)
    val apps = adaptive.listApps()
    val paths = adaptive.listPaths(30)
    val notes = adaptive.listNotes(40)
    val reports = CycloneBrainRuntime.store.listReports(20)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V292Hero(Icons.Rounded.AccountTree, "Cyclone Brain", "This is the one knowledge base. Teaching, verified task actions, failures, corrections and one-pass consolidation all add to this existing evidence instead of creating parallel memories.") }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { V292Metric("Skills", skills.size, Modifier.weight(1f)); V292Metric("Apps", apps.size, Modifier.weight(1f)); V292Metric("Paths", paths.size, Modifier.weight(1f)) } }
        item { V292Section("Latest learning updates") {
            val learningNotes = notes.filter { it.source in setOf("TEACHING_CONSOLIDATION", "TEACHING_OPTIMIZATION", "AI_TEACHING_CORRECTION", "USER_TEACHING_CORRECTION", "MISSION_CONSOLIDATION", "MISSION_RECOVERY") }.take(12)
            if (learningNotes.isEmpty()) Text("No consolidated learning updates yet. Teach or run a phone task first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else learningNotes.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
        } }
        item { Text("Strong reusable skills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (skills.isEmpty()) item { V292Empty("No verified skills yet", "Follow Me or a successful AI run will create evidence here.") }
        else items(skills.take(20), key = { it.signature }) { skill -> Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp)) { Text(skill.name, fontWeight = FontWeight.SemiBold); Text("${(skill.confidence * 100).toInt()}% · ${skill.successCount} success / ${skill.failureCount} failure · ${skill.source}", style = MaterialTheme.typography.bodySmall); Text(skill.tool, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        if (reports.isNotEmpty()) item { V292Section("Recent task reports") { reports.take(8).forEach { Text("• ${it.goal}: ${it.summary.take(180)}", style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable
private fun V292SettingsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    val defaultName = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ")
    var url by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var name by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultName).orEmpty()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { V292Hero(Icons.Rounded.SettingsIcon, "${CycloneRelease.label} settings", "Phone permissions, result notifications and optional Cyclone Core connection.") }
        item { V292Section("Permissions") { V292Status("Phone control", v292AccessibilityEnabled(context)); V292Status("Notification events", v292NotificationListenerEnabled(context)); V292Status("AI result notifications", v292ResultNotificationsEnabled(context)); V292Status("OpenRouter key", OpenRouterSecretStore.hasKey(context)) } }
        item { V292Section("Cyclone Core · optional") {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("WebSocket URL") })
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Pairing token") }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Device name") })
            Button(onClick = { prefs.edit().putString("coreWsUrl", url.trim()).putString("coreToken", token.trim()).putString("deviceName", name.trim().ifBlank { defaultName }).apply(); BridgeClient.stop(); BridgeClient.start(context); refresh() }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(5.dp)); Text(if (DeviceState.bridgeConnected) "Reconnect" else "Save & connect") }
        } }
        item { V292Section("Learning behavior") { Text("Phone tasks use deterministic Brain/App Graph evidence first. OpenRouter is called only for unknown semantic states or a one-time visual fallback, plus one compact post-task consolidation pass. Follow Me runs locally while you demonstrate, then at most one compact post-teaching consolidation pass.", style = MaterialTheme.typography.bodySmall) } }
    }
}

@Composable
private fun V292ModeCard(selected: Boolean, icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(body, style = MaterialTheme.typography.bodySmall) }; if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun V292AutomationStep(index: Int, step: StepDefinition) {
    Card(shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.Top) { Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Text(index.toString(), fontWeight = FontWeight.Bold) }; Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(step.name.ifBlank { step.type.name.lowercase().replace('_', ' ') }, fontWeight = FontWeight.SemiBold); Text(step.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.labelSmall) } }
        Text(v292DescribeStep(step), style = MaterialTheme.typography.bodySmall)
        if (step.confirmationRequired) Text("Requires confirmation.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        if (step.recovery.maxRetries > 0 || step.recovery.onFailure.name != "ABORT") Text("Recovery: ${step.recovery.maxRetries} retr${if (step.recovery.maxRetries == 1) "y" else "ies"}; then ${step.recovery.onFailure.name.lowercase().replace('_', ' ')}.", style = MaterialTheme.typography.labelSmall)
    } }
}

internal fun v292DescribeStep(step: StepDefinition): String {
    val tool = step.parameters["tool"]?.removePrefix("phone.")?.replace('_', ' ')
    val target = step.selector?.text?.takeIf(String::isNotBlank) ?: step.selector?.contentDescription?.takeIf(String::isNotBlank) ?: step.selector?.resourceId?.substringAfterLast('/')?.takeIf(String::isNotBlank) ?: step.selector?.role?.takeIf(String::isNotBlank)
    val safe = step.parameters.filterKeys(::v292SafeParameterKey).filterKeys { it != "tool" }.entries.take(5).joinToString(" · ") { (k, v) -> "${k.replace('_', ' ')}=$v" }
    return buildString { append(tool?.replaceFirstChar(Char::uppercase) ?: step.type.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)); target?.let { append(" → $it") }; if (safe.isNotBlank()) append(" · $safe") }
}
private fun v292SafeParameterKey(key: String): Boolean = listOf("password", "secret", "token", "api_key", "apikey", "credential", "value", "text").none { it in key.lowercase() }

@Composable
private fun V292Hero(icon: ImageVector, title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(26.dp)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Icon(icon, null, modifier = Modifier.size(34.dp)); Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(body) } }
}
@Composable
private fun V292Section(title: String, content: @Composable Column.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() } }
}
@Composable
private fun V292Metric(label: String, value: Int, modifier: Modifier = Modifier) { Card(modifier, shape = RoundedCornerShape(17.dp)) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) } } }
@Composable
private fun V292Notice(icon: ImageVector, title: String, body: String) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) { Icon(icon, null); Spacer(Modifier.width(8.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } } } }
@Composable
private fun V292Empty(title: String, body: String) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } } }
@Composable
private fun V292Status(label: String, ok: Boolean) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error); Spacer(Modifier.width(7.dp)); Text(label, modifier = Modifier.weight(1f)); Text(if (ok) "Ready" else "Needs setup", style = MaterialTheme.typography.labelMedium) } }
@Composable
private fun V292SetupCard(number: String, icon: ImageVector, title: String, body: String, done: Boolean, actionLabel: String, action: () -> Unit) { Card(shape = RoundedCornerShape(21.dp), colors = CardDefaults.cardColors(containerColor = if (done) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { V292NumberBadge(number, done); Spacer(Modifier.width(9.dp)); Icon(icon, null); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } }; OutlinedButton(onClick = action, enabled = !done || actionLabel != "Enabled", modifier = Modifier.fillMaxWidth()) { Text(actionLabel) } } } }
@Composable
private fun V292NumberBadge(number: String, done: Boolean) { Box(Modifier.size(30.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(if (done) "✓" else number, color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) } }

private fun v292AccessibilityEnabled(context: Context): Boolean {
    val component = ComponentName(context, CycloneAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}
private fun v292NotificationListenerEnabled(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
private fun v292ResultNotificationsEnabled(context: Context): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
