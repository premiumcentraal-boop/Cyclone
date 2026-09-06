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
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.AiTraceBus
import com.cyclone.mobile.ai.AiTraceOverlayV27Runtime
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.applearner.LearnerSessionState
import com.cyclone.mobile.applearner.LearningMode
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.BrainMicroSkill
import com.cyclone.mobile.brain.CycloneBrainRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class V27Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    LEARN("Learn", Icons.Rounded.School),
    AI("AI", Icons.Rounded.AutoAwesome),
    AUTOMATIONS("Automations", Icons.Rounded.Bolt),
    BRAIN("Brain", Icons.Rounded.AccountTree),
}

private enum class V27Utility(val title: String) {
    SETTINGS("Cyclone settings"),
    CONNECTIONS("Connections"),
    PERMISSIONS("Permissions"),
    HISTORY("AI history"),
    ABOUT("About Cyclone V2.7"),
}

private enum class V27AiMode { PHONE, BRAIN }
private enum class V27LearnView { HOME, NEW_APP, APP_PROGRESS, APP_DETAIL }
private enum class V27HistoryFilter { ALL, SUCCESS, FAILED }
private data class V27InstalledApp(val packageName: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycloneMobileV27App() {
    CycloneTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var tab by rememberSaveable { mutableStateOf(V27Tab.AI) }
        var utility by rememberSaveable { mutableStateOf<V27Utility?>(null) }
        var historySessionId by rememberSaveable { mutableStateOf<String?>(null) }
        var refreshTick by remember { mutableIntStateOf(0) }

        AutomationRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)
        AgentTraceRuntime.initialize(context)
        CycloneBrainRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)
        BrainChatRuntime.initialize(context)

        LaunchedEffect(Unit) {
            while (true) {
                delay(650)
                refreshTick++
            }
        }

        fun openUtility(page: V27Utility) {
            utility = page
            historySessionId = null
            scope.launch { drawer.close() }
        }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            V27CycloneC(56)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Cyclone V2.7", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Adaptive phone intelligence", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        V27DrawerItem(Icons.Rounded.Settings, "Settings") { openUtility(V27Utility.SETTINGS) }
                        V27DrawerItem(Icons.Rounded.Link, "Connections") { openUtility(V27Utility.CONNECTIONS) }
                        V27DrawerItem(Icons.Rounded.Security, "Permissions") { openUtility(V27Utility.PERMISSIONS) }
                        V27DrawerItem(Icons.Rounded.AccountTree, "Obsidian Brain") {
                            utility = null; tab = V27Tab.BRAIN; scope.launch { drawer.close() }
                        }
                        V27DrawerItem(Icons.Rounded.History, "AI history") { openUtility(V27Utility.HISTORY) }
                        V27DrawerItem(Icons.Rounded.Info, "About") { openUtility(V27Utility.ABOUT) }
                    }
                }
            },
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            Surface(
                                onClick = { scope.launch { drawer.open() } },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp).size(42.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        },
                        title = {
                            Column {
                                Text(utility?.title ?: tab.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (DeviceState.bridgeConnected) "Cyclone V2.7 · Core connected" else "Cyclone V2.7 Beta",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            if (utility != null) {
                                OutlinedButton(onClick = { utility = null; historySessionId = null }) {
                                    Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("Back")
                                }
                            } else {
                                val ready = v27AccessibilityEnabled(context) && DeviceState.controller == DeviceState.Controller.AGENT
                                Box(Modifier.size(11.dp).clip(CircleShape).background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
                                Spacer(Modifier.width(16.dp))
                            }
                        },
                    )
                },
                bottomBar = {
                    if (utility == null) {
                        NavigationBar {
                            V27Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = {
                                        if (item == V27Tab.AI) {
                                            Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                                Icon(Icons.Rounded.AutoAwesome, "AI", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(29.dp))
                                            }
                                        } else Icon(item.icon, item.label)
                                    },
                                    label = { Text(item.label, fontWeight = if (item == V27Tab.AI) FontWeight.Bold else FontWeight.Normal) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (val page = utility) {
                        V27Utility.SETTINGS -> V27SettingsHub(
                            onConnections = { utility = V27Utility.CONNECTIONS },
                            onPermissions = { utility = V27Utility.PERMISSIONS },
                            onBrain = { utility = null; tab = V27Tab.BRAIN },
                            onHistory = { utility = V27Utility.HISTORY },
                        )
                        V27Utility.CONNECTIONS -> V27ConnectionsPage(context) { refreshTick++ }
                        V27Utility.PERMISSIONS -> V27PermissionsPage(context) { refreshTick++ }
                        V27Utility.HISTORY -> V27HistoryPage(context, refreshTick, historySessionId) { historySessionId = it }
                        V27Utility.ABOUT -> V27AboutPage()
                        null -> when (tab) {
                            V27Tab.HOME -> V27HomePage(context, refreshTick, onAi = { tab = V27Tab.AI }, onLearn = { tab = V27Tab.LEARN })
                            V27Tab.LEARN -> V27LearnPage(context, refreshTick)
                            V27Tab.AI -> V27AiPage(context, refreshTick, onHistory = { utility = V27Utility.HISTORY })
                            V27Tab.AUTOMATIONS -> V27AutomationsPage(context, refreshTick) { refreshTick++ }
                            V27Tab.BRAIN -> V27BrainPage(context, refreshTick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V27HomePage(context: Context, refreshTick: Int, onAi: () -> Unit, onLearn: () -> Unit) {
    val oldBrain = CycloneBrainRuntime.store.stats()
    val adaptive = AdaptiveBrainRuntime.store
    val strongSkills = adaptive.listMicroSkills(200).count { it.confidence >= .82 && it.successCount > 0 }
    val follow = FollowMeLearnerRuntime.progress()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        V27CycloneC(48); Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Cyclone remembers what works", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Every verified action becomes reusable Brain evidence. Failures lower only the skill that failed.")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAi, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask AI") }
                        FilledTonalButton(onClick = onLearn, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Teach") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V27Metric("Micro skills", adaptive.listMicroSkills(400).size, Modifier.weight(1f))
                V27Metric("Strong", strongSkills, Modifier.weight(1f))
                V27Metric("Reports", oldBrain.reports, Modifier.weight(1f))
            }
        }
        if (follow.active) item {
            V27Notice(Icons.Rounded.Visibility, "Follow Me is learning", "${follow.appsSeen} apps · ${follow.screensSeen} screens · ${follow.pathsLearned} paths observed while you control the phone.")
        }
        item {
            V27Section("Ready state") {
                V27Status("Phone control", v27AccessibilityEnabled(context))
                V27Status("Notifications", v27NotificationEnabled(context))
                V27Status("OpenRouter", OpenRouterSecretStore.hasKey(context))
                V27Status("Cyclone Core", DeviceState.bridgeConnected)
            }
        }
    }
}

@Composable
private fun V27AiPage(context: Context, refreshTick: Int, onHistory: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val adaptiveAgent = remember { OpenRouterAdaptiveAgent(context.applicationContext) }
    var mode by rememberSaveable { mutableStateOf(V27AiMode.PHONE) }
    var request by rememberSaveable { mutableStateOf("") }
    var brainText by rememberSaveable { mutableStateOf("") }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var modelSlug by rememberSaveable { mutableStateOf(prefs.getString("openrouter_model", OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id).orEmpty()) }
    var safeMode by rememberSaveable { mutableStateOf(prefs.getBoolean("safe_mode", true)) }
    var overlayEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("trace_overlay", false)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var result by remember { mutableStateOf("") }
    var chatTick by remember { mutableIntStateOf(0) }
    val recent = AgentTraceRuntime.store.listSessions(4)
    val live = AiTraceBus.latest
    val chatHistory = BrainChatRuntime.history(context, 20 + chatTick * 0)
    val recall = if (request.isBlank()) null else AdaptiveBrainRuntime.store.recall(request, null)

    fun config() = QuickAgentConfig(
        model = OpenRouterModelPresets.byId(modelSlug.trim().ifBlank { OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id }),
        safeMode = safeMode,
        providerSort = "latency",
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(30.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Cyclone AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Brain-first phone control", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mode == V27AiMode.PHONE, onClick = { mode = V27AiMode.PHONE }, label = { Text("Control phone") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) })
                        FilterChip(selected = mode == V27AiMode.BRAIN, onClick = { mode = V27AiMode.BRAIN }, label = { Text("Chat with Brain") }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null) })
                    }
                }
            }
        }

        if (mode == V27AiMode.PHONE) {
            item {
                V27Section("Do something on my phone") {
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        enabled = !busy,
                        label = { Text("What should Cyclone do?") },
                        placeholder = { Text("Open Spotify / go Home / find Battery settings") },
                    )
                    if (recall != null && (recall.optJSONArray("microSkills")?.length() ?: 0) > 0) {
                        val best = recall.optJSONArray("microSkills")?.optJSONObject(0)
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("Brain already remembers part of this", fontWeight = FontWeight.SemiBold)
                                Text("${best?.optString("name")} · ${((best?.optDouble("confidence") ?: 0.0) * 100).toInt()}% confidence", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                busy = true; result = ""; status = "Checking Brain first…"
                                scope.launch {
                                    val run = adaptiveAgent.execute(request, config()) { status = it }
                                    result = run.message
                                    status = if (run.ok) "Completed · ${run.decisions} AI decision${if (run.decisions == 1) "" else "s"}" else "Stopped · Brain saved the evidence"
                                    busy = false
                                }
                            },
                            enabled = request.isNotBlank() && !busy && (hasKey || AdaptiveBrainRuntime.store.deterministicPlan(request, null) != null),
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (busy) "Working…" else "Do it") }
                        FilledTonalButton(
                            onClick = {
                                busy = true; result = ""
                                scope.launch {
                                    val run = adaptiveAgent.buildWorkflow(request, config()) { status = it }
                                    result = run.message; status = if (run.ok) "Workflow ready" else "Workflow stopped"; busy = false
                                }
                            },
                            enabled = hasKey && request.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.width(5.dp)); Text("Workflow") }
                    }
                    Text(status, fontWeight = FontWeight.Medium)
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                V27Section("Adaptive Brain") {
                    Text("Every real phone action now creates small success/failure evidence: Home, opening apps, clicks, scrolls, waits and checks. Known paths are recalled before decision 1.", style = MaterialTheme.typography.bodySmall)
                    val skills = AdaptiveBrainRuntime.store.listMicroSkills(4)
                    if (skills.isEmpty()) Text("No micro-skills yet. Run a simple task or use Follow Me.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else skills.forEach { skill -> V27SkillLine(skill) }
                    Text("After the task returns, a background Brain refinement pass reviews the structured memory and adds non-executable lessons without changing safety confidence.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                V27Section("Task-scoped overlay") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live decision summaries", fontWeight = FontWeight.SemiBold)
                            Text("Appears only while a task runs. On Done/Stopped it shows the result briefly, then fades away automatically.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = overlayEnabled,
                            onCheckedChange = { enabled ->
                                overlayEnabled = enabled
                                prefs.edit().putBoolean("trace_overlay", enabled).apply()
                                if (!enabled) AiTraceOverlayV27Runtime.disableImmediately()
                                else if (CycloneAccessibilityService.instance == null) {
                                    overlayEnabled = false
                                    prefs.edit().putBoolean("trace_overlay", false).apply()
                                    Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                        Text(live?.displayText ?: "The overlay is quiet until a task starts.", Modifier.padding(13.dp))
                    }
                    Text("This is an explicit progress/decision stream, not raw hidden chain-of-thought, and it is never fed back into the model prompt.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                V27Section("Chat with your Obsidian Brain") {
                    Text("Ask what Cyclone has learned, or teach it a fact yourself. Type “Remember that …” to save knowledge automatically.", style = MaterialTheme.typography.bodySmall)
                    if (chatHistory.isEmpty()) {
                        V27Notice(Icons.Rounded.AccountTree, "Your Brain is ready", "Example: “What do you know about opening Spotify?” or “Remember that I prefer the Downloads folder for invoices.”")
                    } else {
                        chatHistory.takeLast(10).forEach { message ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(if (message.role == "user") "You" else "Cyclone Brain", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                                    Text(message.text)
                                }
                            }
                        }
                    }
                    OutlinedTextField(value = brainText, onValueChange = { brainText = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text("Ask or teach the Brain") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val text = brainText.trim(); if (text.isBlank()) return@Button
                                brainText = ""; busy = true
                                scope.launch {
                                    result = BrainChatRuntime.chat(context, text, modelSlug.trim().ifBlank { OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id })
                                    chatTick++; busy = false
                                }
                            },
                            enabled = brainText.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Send, null); Spacer(Modifier.width(5.dp)); Text("Chat") }
                        FilledTonalButton(
                            onClick = {
                                runCatching { BrainChatRuntime.saveKnowledge(context, brainText.trim()) }
                                    .onSuccess { brainText = ""; chatTick++; Toast.makeText(context, "Saved to Cyclone Brain", Toast.LENGTH_SHORT).show() }
                            },
                            enabled = brainText.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.width(5.dp)); Text("Save knowledge") }
                    }
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            V27Section("Model & safety") {
                Text("Preset or any custom OpenRouter slug.", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenRouterModelPresets.all.forEach { model ->
                        FilterChip(selected = modelSlug == model.id, onClick = { modelSlug = model.id; prefs.edit().putString("openrouter_model", model.id).apply() }, label = { Text(model.label) })
                    }
                }
                OutlinedTextField(
                    value = modelSlug,
                    onValueChange = { modelSlug = it; prefs.edit().putString("openrouter_model", it.trim()).apply() },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Custom OpenRouter model slug") }, placeholder = { Text("provider/model-name") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Safe Mode")
                        Text("Blocks obvious payment, send, purchase and destructive actions.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = safeMode, onCheckedChange = { safeMode = it; prefs.edit().putBoolean("safe_mode", it).apply() })
                }
            }
        }

        item {
            V27Section("OpenRouter") {
                if (!hasKey) {
                    OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    Button(onClick = {
                        runCatching { OpenRouterSecretStore.save(context, keyDraft) }
                            .onSuccess { keyDraft = ""; hasKey = true }
                            .onFailure { result = it.message ?: "Could not save key" }
                    }, enabled = keyDraft.isNotBlank()) { Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(5.dp)); Text("Secure key") }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(7.dp))
                        Text("Key secured with Android Keystore", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { OpenRouterSecretStore.clear(context); hasKey = false }) { Text("Replace") }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Recent runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Cleaner history with success, failure and technical detail separated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onHistory) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(4.dp)); Text("All") }
            }
        }
        items(recent, key = { it.id }) { V27HistoryCompact(it) }
    }
}

@Composable
private fun V27LearnPage(context: Context, refreshTick: Int) {
    var view by rememberSaveable { mutableStateOf(V27LearnView.HOME) }
    var selectedPackage by rememberSaveable { mutableStateOf("") }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("Learn useful navigation in this app. Do not submit, pay, delete or change anything.") }
    var mode by rememberSaveable { mutableStateOf(LearningMode.GUIDED) }
    var useAi by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf("") }
    var ask by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    val follow = FollowMeLearnerRuntime.progress()
    val appProgress = AppLearnerRuntime.progress()
    val learned = AppLearnerRuntime.learnedApps()
    val apps = remember { v27LauncherApps(context) }

    LaunchedEffect(appProgress.state, appProgress.packageName) {
        if (appProgress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
            selectedPackage = appProgress.packageName.orEmpty(); selectedLabel = appProgress.appLabel.orEmpty(); view = V27LearnView.APP_PROGRESS
        }
    }

    when (view) {
        V27LearnView.HOME -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(34.dp)); Spacer(Modifier.width(9.dp)); Text("Follow Me", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                        Text("Use your phone normally. Cyclone watches your clicks and screen changes in the background, extends each app's semantic map, and learns cross-app routes. Cyclone does not click for you in this mode.")
                        if (follow.active) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                V27Metric("Apps", follow.appsSeen, Modifier.weight(1f)); V27Metric("Screens", follow.screensSeen, Modifier.weight(1f)); V27Metric("Paths", follow.pathsLearned, Modifier.weight(1f))
                            }
                            Text("${follow.currentApp.ifBlank { "Observing phone" }} · ${follow.currentScreen.ifBlank { follow.message }}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { if (follow.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause() }, modifier = Modifier.weight(1f)) {
                                    Icon(if (follow.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(4.dp)); Text(if (follow.paused) "Resume" else "Pause")
                                }
                                OutlinedButton(onClick = { FollowMeLearnerRuntime.stop() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (appProgress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) AppLearnerRuntime.stop()
                                    FollowMeLearnerRuntime.start(context)
                                    Toast.makeText(context, "Follow Me is learning in the background", Toast.LENGTH_SHORT).show()
                                    (context as? Activity)?.moveTaskToBack(true)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.width(6.dp)); Text("Start Follow Me & use my phone") }
                        }
                        Text("Privacy: typed text, passwords, OTPs and sensitive input contents are ignored. User control stays HUMAN for the whole session.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                V27Section("Teach one app more deeply") {
                    Text("Use Guided/Task/Passive App Learner when you want focused semantic exploration of one specific app.")
                    Button(onClick = { view = V27LearnView.NEW_APP }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.School, null); Spacer(Modifier.width(6.dp)); Text("New app learning session") }
                }
            }
            item { Text("Apps Cyclone knows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (learned.isEmpty()) item { V27Empty("No learned apps yet", "Start Follow Me or teach one app.") }
            else items(learned, key = { it.packageName }) { app ->
                Card(onClick = { selectedPackage = app.packageName; selectedLabel = app.label; view = V27LearnView.APP_DETAIL }, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Map, null); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text("${(app.confidence * 100).toInt()}% knowledge · ${app.knowledgeState.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        V27LearnView.NEW_APP -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                V27Heading("Focused App Learner", "Pick one app and tell Cyclone exactly what to learn.")
                OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search installed apps") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
            }
            items(apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }.take(20), key = { it.packageName }) { app ->
                FilterChip(selected = selectedPackage == app.packageName, onClick = { selectedPackage = app.packageName; selectedLabel = app.label }, label = { Text(app.label) })
            }
            item {
                OutlinedTextField(value = instruction, onValueChange = { instruction = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("What should Cyclone learn?") })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("AI guidance"); Text("Only for unknown semantic choices.", style = MaterialTheme.typography.bodySmall) }
                    Switch(checked = useAi, onCheckedChange = { useAi = it })
                }
                Button(
                    onClick = { AppLearnerRuntime.start(context, selectedPackage, selectedLabel, instruction, mode, useAi); view = V27LearnView.APP_PROGRESS },
                    enabled = selectedPackage.isNotBlank() && instruction.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Start learning") }
                OutlinedButton(onClick = { view = V27LearnView.HOME }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
        V27LearnView.APP_PROGRESS -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Learning ${appProgress.appLabel ?: selectedLabel}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Current: ${appProgress.currentScreen ?: "Observing…"}")
                        Text(appProgress.currentActivity, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { V27Metric("Screens", appProgress.screens, Modifier.weight(1f)); V27Metric("Actions", appProgress.actions, Modifier.weight(1f)); V27Metric("Paths", appProgress.transitions, Modifier.weight(1f)) } }
            item {
                OutlinedTextField(value = instruction, onValueChange = { instruction = it; AppLearnerRuntime.updateInstruction(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Guide Cyclone while it learns") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { if (appProgress.state == LearnerSessionState.PAUSED) AppLearnerRuntime.resume() else AppLearnerRuntime.pause() }, modifier = Modifier.weight(1f)) {
                        Icon(if (appProgress.state == LearnerSessionState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(4.dp)); Text(if (appProgress.state == LearnerSessionState.PAUSED) "Resume" else "Pause")
                    }
                    FilledTonalButton(onClick = { if (appProgress.state == LearnerSessionState.WAITING_FOR_HUMAN) AppLearnerRuntime.returnFromTakeover() else AppLearnerRuntime.takeOver() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Security, null); Spacer(Modifier.width(4.dp)); Text(if (appProgress.state == LearnerSessionState.WAITING_FOR_HUMAN) "Return" else "Take over")
                    }
                }
                OutlinedButton(onClick = { AppLearnerRuntime.stop(); view = V27LearnView.HOME }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop") }
                if (appProgress.state !in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
                    Button(onClick = { selectedPackage = appProgress.packageName.orEmpty(); view = V27LearnView.APP_DETAIL }, modifier = Modifier.fillMaxWidth()) { Text("View learned app") }
                }
            }
        }
        V27LearnView.APP_DETAIL -> {
            val graph = AppLearnerRuntime.graph(selectedPackage)
            if (graph == null) V27Empty("No knowledge found", "Teach this app again.")
            else V27AppDetail(graph, ask, answer, onAsk = { ask = it }, onSend = { answer = AppLearnerRuntime.ask(graph.app.packageName, ask) }, onBack = { view = V27LearnView.HOME }, onTeach = { instruction = graph.app.instructionSummary.ifBlank { "Continue learning this app." }; view = V27LearnView.NEW_APP })
        }
    }
}

@Composable
private fun V27AppDetail(graph: AppGraphSnapshot, ask: String, answer: String, onAsk: (String) -> Unit, onSend: () -> Unit, onBack: () -> Unit, onTeach: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(graph.app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${(graph.app.confidence * 100).toInt()}% knowledge · ${graph.screens.size} screens · ${graph.actions.size} actions · ${graph.transitions.size} paths")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onTeach) { Text("Teach more") }; OutlinedButton(onClick = onBack) { Text("All apps") } }
                }
            }
        }
        item {
            V27Section("Ask learned app") {
                OutlinedTextField(value = ask, onValueChange = onAsk, modifier = Modifier.fillMaxWidth(), placeholder = { Text("How do I reach downloads?") })
                Button(onClick = onSend, enabled = ask.isNotBlank()) { Text("Ask graph") }
                if (answer.isNotBlank()) Text(answer)
            }
        }
        item { Text("Screens & confidence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(graph.screens.take(30), key = { it.id }) { screen ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(screen.title, fontWeight = FontWeight.SemiBold); Text(screen.purpose, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                    Text("${(screen.confidence * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        item {
            V27Section("Known navigation") {
                graph.transitions.take(50).forEach { t ->
                    val from = graph.screens.firstOrNull { it.id == t.fromScreenId }?.title ?: "Unknown"
                    val to = graph.screens.firstOrNull { it.id == t.toScreenId }?.title ?: "Unknown"
                    val action = graph.actions.firstOrNull { it.id == t.actionId }?.label ?: "action"
                    Text("$from → $action → $to", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun V27BrainPage(context: Context, refreshTick: Int) {
    val adaptive = AdaptiveBrainRuntime.store
    val old = CycloneBrainRuntime.store
    val skills = adaptive.listMicroSkills(24)
    val apps = adaptive.listApps()
    val paths = adaptive.listPaths(12)
    val notes = adaptive.listNotes(12)
    val reports = old.listReports(12)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AccountTree, null, modifier = Modifier.size(38.dp))
                    Text("Obsidian Brain V2.7", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("One phone map made from App Learner graphs, tiny action skills, installed apps, learned task paths, user notes and post-task reports.")
                    Text("Unknown → AI solves → action evidence → micro-skill → repeated success → deterministic reuse.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { V27Metric("Skills", skills.size, Modifier.weight(1f)); V27Metric("Apps", apps.size, Modifier.weight(1f)); V27Metric("Paths", paths.size, Modifier.weight(1f)) } }
        item {
            V27Section("Installed app map") {
                apps.take(14).forEach { app -> Text("• ${app.label} → ${app.packageName} · ${app.openSuccessCount} verified open(s)", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Text("Small skills Cyclone can reuse", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (skills.isEmpty()) item { V27Empty("No micro-skills yet", "Run a phone task or use Follow Me.") }
        else items(skills, key = { it.signature }) { skill ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(skill.name, fontWeight = FontWeight.SemiBold)
                    Text("${(skill.confidence * 100).toInt()}% · ${skill.successCount} success / ${skill.failureCount} failure · ${skill.source}", style = MaterialTheme.typography.bodySmall)
                    Text(skill.tool, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (paths.isNotEmpty()) {
            item { Text("Learned task paths", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(paths, key = { it.signature }) { path ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(path.goalKey.ifBlank { "Task" }, fontWeight = FontWeight.SemiBold)
                        Text("${(path.confidence * 100).toInt()}% · ${path.successCount} success / ${path.failureCount} failure · ${path.skillSignatures.size} steps", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            V27Section("User & background knowledge") {
                if (notes.isEmpty()) Text("No notes yet. Use Chat with Brain to add knowledge.")
                else notes.forEach { note -> Text("• ${note.text}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        item { Text("Recent task reports", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(reports, key = { it.id }) { report ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(report.goal, fontWeight = FontWeight.SemiBold)
                    Text("${report.status} · ${v27Time(report.createdAt)}", style = MaterialTheme.typography.labelSmall)
                    Text(report.summary, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
        }
    }
}

@Composable
private fun V27HistoryPage(context: Context, refreshTick: Int, selectedId: String?, onSelect: (String?) -> Unit) {
    val sessions = AgentTraceRuntime.store.listSessions(100)
    if (selectedId != null) {
        val session = sessions.firstOrNull { it.id == selectedId }
        val events = AgentTraceRuntime.store.events(selectedId)
        val report = CycloneBrainRuntime.store.listReports(100).firstOrNull { it.id == selectedId }
        var technical by rememberSaveable(selectedId) { mutableStateOf(false) }
        val visible = if (technical) events else events.filterNot { it.kind in setOf("MODEL", "OBSERVE") }
        val successes = events.count { it.ok == true && it.kind in setOf("RESULT", "REPLAY") }
        val failures = events.count { it.ok == false }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedButton(onClick = { onSelect(null) }) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("All runs") }
                Spacer(Modifier.height(10.dp))
                Text(session?.goal ?: "AI run", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                session?.let { Text("${it.status} · ${it.decisions} AI decisions · ${v27Time(it.startedAt)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = if (session?.status == "COMPLETED") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (session?.status == "COMPLETED") "What happened" else "Why it stopped", fontWeight = FontWeight.Bold)
                        Text(session?.result.orEmpty().ifBlank { "No final message recorded." })
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("$successes verified actions", style = MaterialTheme.typography.labelMedium)
                            Text("$failures failures/boundaries", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            report?.let { r ->
                item {
                    V27Section("What Cyclone learned") {
                        Text(r.summary)
                        if (r.reusableSequence.isNotBlank()) Text("Reusable: ${r.reusableSequence}", style = MaterialTheme.typography.bodySmall)
                        if (r.failureSummary.isNotBlank()) {
                            Text("Saved mistakes", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                            Text(r.failureSummary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Timeline", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (technical) "Showing every model/observe event" else "Showing decisions, actions, checks and recovery only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = technical, onCheckedChange = { technical = it })
                }
            }
            items(visible, key = { it.id }) { event ->
                Card(shape = RoundedCornerShape(17.dp), colors = CardDefaults.cardColors(containerColor = when {
                    event.ok == false -> MaterialTheme.colorScheme.errorContainer
                    event.kind == "REPLAY" || event.kind == "BRAIN" -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surface
                })) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(when (event.ok) { false -> MaterialTheme.colorScheme.error; true -> MaterialTheme.colorScheme.primary; null -> MaterialTheme.colorScheme.onSurfaceVariant }))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(v27EventLabel(event.kind), fontWeight = FontWeight.SemiBold)
                                Text(v27Clock(event.timestampMs), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(event.displayText)
                            if (technical) event.code?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            event.detail?.takeIf { it.isNotBlank() && (technical || event.ok == false) }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
    } else {
        var filter by rememberSaveable { mutableStateOf(V27HistoryFilter.ALL) }
        val shown = sessions.filter { session -> when (filter) { V27HistoryFilter.ALL -> true; V27HistoryFilter.SUCCESS -> session.status == "COMPLETED"; V27HistoryFilter.FAILED -> session.status != "COMPLETED" } }
        val successes = sessions.count { it.status == "COMPLETED" }
        val failures = sessions.size - successes
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                V27Heading("AI history", "Runs are organized around outcome first. Open one for the exact action/recovery timeline.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V27Metric("Runs", sessions.size, Modifier.weight(1f)); V27Metric("Done", successes, Modifier.weight(1f)); V27Metric("Stopped", failures, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V27HistoryFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
                }
            }
            if (shown.isEmpty()) item { V27Empty("No matching runs", "Try another filter or run a task from AI.") }
            else items(shown, key = { it.id }) { session ->
                Card(onClick = { onSelect(session.id) }, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(if (session.status == "COMPLETED") Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (session.status == "COMPLETED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(session.goal, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${if (session.status == "COMPLETED") "Completed" else "Stopped"} · ${session.decisions} decisions · ${v27Time(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            session.result?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun V27AutomationsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val automations = AutomationRuntime.store.listAutomations()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            V27Heading("Automations", "Repeat known work deterministically. AI is for recovery and new states.")
            Button(onClick = {
                val service = CycloneAccessibilityService.instance
                if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                else { service.showGuidedRecorderOverlay(); Toast.makeText(context, "Teach bubble opened", Toast.LENGTH_SHORT).show(); (context as? Activity)?.moveTaskToBack(true) }
            }) { Icon(Icons.Rounded.Gesture, null); Spacer(Modifier.width(5.dp)); Text("Teach a routine") }
        }
        if (automations.isEmpty()) item { V27Empty("No automations", "Teach a routine or ask AI to build one.") }
        else items(automations, key = { it.id }) { automation ->
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) { Text(automation.name, fontWeight = FontWeight.SemiBold); Text("${automation.steps.size} steps · ${automation.trigger.type.name.lowercase()}", style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = automation.enabled, onCheckedChange = { AutomationRuntime.store.saveAutomation(automation.copy(enabled = it)); refresh() })
                    }
                    Button(onClick = { AutomationRuntime.router.runManual(automation.id); refresh() }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Run") }
                }
            }
        }
    }
}

@Composable
private fun V27SettingsHub(onConnections: () -> Unit, onPermissions: () -> Unit, onBrain: () -> Unit, onHistory: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V27Heading("Cyclone navigation", "Connections, permissions, Brain and inspectability stay behind the global C button.") }
        item { V27UtilityCard(Icons.Rounded.Link, "Connections", "Cyclone Core and OpenRouter", onConnections) }
        item { V27UtilityCard(Icons.Rounded.Security, "Permissions", "Accessibility, notifications and calendar", onPermissions) }
        item { V27UtilityCard(Icons.Rounded.AccountTree, "Obsidian Brain", "Micro-skills, app inventory, paths and notes", onBrain) }
        item { V27UtilityCard(Icons.Rounded.History, "AI history", "Outcome-first run history and technical trace", onHistory) }
    }
}

@Composable
private fun V27ConnectionsPage(context: Context, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    val defaultName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
    val defaultId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.let { "android-$it" }.orEmpty()
    var url by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var name by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultName).orEmpty()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { V27Heading("Connections", "Pair the phone to Cyclone Core. AI model/key settings live in the central AI tab.") }
        item {
            V27Section("Cyclone Core") {
                OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("WebSocket URL") })
                OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pairing token") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device name") })
                Button(onClick = {
                    prefs.edit().putString("coreWsUrl", url.trim()).putString("coreToken", token).putString("deviceId", prefs.getString("deviceId", defaultId).orEmpty().ifBlank { defaultId }).putString("deviceName", name.trim().ifBlank { defaultName }).apply()
                    BridgeClient.stop(); BridgeClient.start(context); refresh()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(5.dp)); Text(if (DeviceState.bridgeConnected) "Reconnect" else "Save & connect") }
                V27Status("Core", DeviceState.bridgeConnected)
            }
        }
    }
}

@Composable
private fun V27PermissionsPage(context: Context, refresh: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V27Heading("Permissions", "Cyclone acts only through Android capabilities you grant.") }
        item { V27Permission("Phone control", "Accessibility service", v27AccessibilityEnabled(context)) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); refresh() } }
        item { V27Permission("Notifications", "Notification listener", v27NotificationEnabled(context)) { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); refresh() } }
        item { V27Permission("Calendar", "Calendar-aware automations", context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) { (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100); refresh() } }
        item { V27Notice(Icons.Rounded.Visibility, "Follow Me privacy", "Follow Me observes Accessibility navigation events while the user controls the phone. It explicitly ignores text-change contents and does not autonomously click.") }
    }
}

@Composable
private fun V27AboutPage() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    V27CycloneC(58)
                    Text("Cyclone V2.7 Beta", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Adaptive micro-skill Brain + Brain Chat + cleaner history + task-scoped overlay + Follow Me learning.")
                }
            }
        }
        item { V27Section("Learning loop") { Text("Observe once → prove action → store tiny skill → reuse → verify → promote repeated paths toward deterministic execution.") } }
    }
}

@Composable private fun V27DrawerItem(icon: ImageVector, label: String, action: () -> Unit) { NavigationDrawerItem(label = { Text(label) }, selected = false, onClick = action, icon = { Icon(icon, null) }, shape = RoundedCornerShape(18.dp)) }
@Composable private fun V27CycloneC(size: Int) { Box(Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium) } }

@Composable
private fun V27Section(title: String, content: @Composable Column.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); content() } }
}

@Composable
private fun V27Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) } }
}

@Composable
private fun V27SkillLine(skill: BrainMicroSkill) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (skill.failureCount > skill.successCount) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text(skill.name, fontWeight = FontWeight.Medium, maxLines = 1); Text("${(skill.confidence * 100).toInt()}% · ${skill.successCount}/${skill.failureCount} success/failure", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun V27Status(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label); Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)); Spacer(Modifier.width(6.dp)); Text(if (ok) "Ready" else "Needs setup", style = MaterialTheme.typography.bodySmall) } }
}

@Composable
private fun V27Notice(icon: ImageVector, title: String, text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) { Icon(icon, null); Spacer(Modifier.width(9.dp)); Column { Text(title, fontWeight = FontWeight.SemiBold); Text(text, style = MaterialTheme.typography.bodySmall) } } }
}

@Composable
private fun V27Empty(title: String, body: String) { Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(18.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } } }

@Composable
private fun V27Heading(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun V27HistoryCompact(session: AiTraceSession) {
    Card(shape = RoundedCornerShape(18.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) { Icon(if (session.status == "COMPLETED") Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (session.status == "COMPLETED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(session.goal, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis); Text("${if (session.status == "COMPLETED") "Completed" else "Stopped"} · ${session.decisions} decisions · ${v27Time(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}

@Composable
private fun V27UtilityCard(icon: ImageVector, title: String, subtitle: String, action: () -> Unit) { Card(onClick = action, shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(11.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } } }

@Composable
private fun V27Permission(title: String, subtitle: String, enabled: Boolean, action: () -> Unit) { Card(shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error); Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; OutlinedButton(onClick = action) { Text(if (enabled) "Open" else "Enable") } } } }

private fun v27LauncherApps(context: Context): List<V27InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL).mapNotNull { info ->
        val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
        if (pkg == context.packageName) return@mapNotNull null
        V27InstalledApp(pkg, info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { pkg })
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

private fun v27AccessibilityEnabled(context: Context): Boolean {
    val component = ComponentName(context, CycloneAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}

private fun v27NotificationEnabled(context: Context): Boolean = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
private fun v27Time(timestamp: Long): String = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
private fun v27Clock(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
private fun v27EventLabel(kind: String): String = when (kind) { "BRAIN" -> "Brain recall"; "REPLAY" -> "Learned step"; "DECISION" -> "Decision"; "RESULT" -> "Verified"; "RECOVERY" -> "Recovery"; "BOUNDARY" -> "Safety boundary"; "VISION" -> "Visual check"; "ERROR" -> "Error"; "ANSWER" -> "Finished"; "DONE" -> "Done"; "STOPPED" -> "Stopped"; else -> kind.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase) }
