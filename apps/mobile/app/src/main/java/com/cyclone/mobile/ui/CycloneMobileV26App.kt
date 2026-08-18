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
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
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
import com.cyclone.mobile.ai.AiTraceOverlayRuntime
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterQuickAgent
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.LearnerSessionState
import com.cyclone.mobile.applearner.LearningMode
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class V26Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    LEARN("Learn", Icons.Rounded.School),
    AI("AI", Icons.Rounded.AutoAwesome),
    AUTOMATIONS("Automations", Icons.Rounded.Bolt),
    BRAIN("Brain", Icons.Rounded.AccountTree),
}

private enum class UtilityPage(val title: String) {
    SETTINGS("Cyclone settings"),
    CONNECTIONS("Connections"),
    PERMISSIONS("Permissions"),
    HISTORY("AI history"),
    ABOUT("About Cyclone V2.6"),
}

private enum class LearnView { HOME, NEW, PROGRESS, DETAIL }
private data class V26InstalledApp(val packageName: String, val label: String)

/**
 * Cyclone V2.6 Beta product shell.
 *
 * The five-tab navigation is now the real app structure. AI is deliberately centered and visually
 * dominant. App Learner is a full page instead of a bottom sheet. All phone control, workflow,
 * App Learner and Hermes/OpenRouter behavior still flows through the existing Agent 1/2/3 runtimes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycloneMobileV26App() {
    CycloneTheme {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        var tab by rememberSaveable { mutableStateOf(V26Tab.AI) }
        var utility by rememberSaveable { mutableStateOf<UtilityPage?>(null) }
        var historySessionId by rememberSaveable { mutableStateOf<String?>(null) }
        var refreshTick by remember { mutableIntStateOf(0) }

        AutomationRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)
        AgentTraceRuntime.initialize(context)
        CycloneBrainRuntime.initialize(context)

        LaunchedEffect(Unit) {
            while (true) {
                delay(700)
                refreshTick++
            }
        }

        fun openUtility(page: UtilityPage) {
            utility = page
            historySessionId = null
            scope.launch { drawer.close() }
        }

        ModalNavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                ModalDrawerSheet {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CycloneC(size = 54)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("Cyclone V2.6", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Your phone AI control center", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        NavigationDrawerItem(Icons.Rounded.Settings, "Settings") { openUtility(UtilityPage.SETTINGS) }
                        NavigationDrawerItem(Icons.Rounded.Link, "Connections") { openUtility(UtilityPage.CONNECTIONS) }
                        NavigationDrawerItem(Icons.Rounded.Security, "Permissions") { openUtility(UtilityPage.PERMISSIONS) }
                        NavigationDrawerItem(Icons.Rounded.AccountTree, "Obsidian Brain") {
                            utility = null
                            tab = V26Tab.BRAIN
                            scope.launch { drawer.close() }
                        }
                        NavigationDrawerItem(Icons.Rounded.History, "AI history") { openUtility(UtilityPage.HISTORY) }
                        NavigationDrawerItem(Icons.Rounded.Info, "About") { openUtility(UtilityPage.ABOUT) }
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
                                    if (DeviceState.bridgeConnected) "Cyclone V2.6 · Core connected" else "Cyclone V2.6 Beta",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        actions = {
                            if (utility != null) {
                                OutlinedButton(onClick = { utility = null }) {
                                    Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Back")
                                }
                            } else {
                                val ready = v26AccessibilityEnabled(context) && DeviceState.controller == DeviceState.Controller.AGENT
                                Box(
                                    Modifier.size(12.dp).clip(CircleShape)
                                        .background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error),
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                        },
                    )
                },
                bottomBar = {
                    if (utility == null) {
                        NavigationBar {
                            V26Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = tab == item,
                                    onClick = { tab = item },
                                    icon = {
                                        if (item == V26Tab.AI) {
                                            Box(
                                                Modifier.size(56.dp).clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primary),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Rounded.AutoAwesome, contentDescription = "AI", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
                                            }
                                        } else {
                                            Icon(item.icon, contentDescription = item.label)
                                        }
                                    },
                                    label = { Text(item.label, fontWeight = if (item == V26Tab.AI) FontWeight.Bold else FontWeight.Normal) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (val page = utility) {
                        UtilityPage.SETTINGS -> SettingsHub(
                            onConnections = { utility = UtilityPage.CONNECTIONS },
                            onPermissions = { utility = UtilityPage.PERMISSIONS },
                            onBrain = { utility = null; tab = V26Tab.BRAIN },
                            onHistory = { utility = UtilityPage.HISTORY },
                        )
                        UtilityPage.CONNECTIONS -> ConnectionsPage(context, refreshTick) { refreshTick++ }
                        UtilityPage.PERMISSIONS -> PermissionsPage(context, refreshTick) { refreshTick++ }
                        UtilityPage.HISTORY -> AiHistoryPage(context, refreshTick, historySessionId) { historySessionId = it }
                        UtilityPage.ABOUT -> AboutPage()
                        null -> when (tab) {
                            V26Tab.HOME -> V26HomePage(context, refreshTick, onAi = { tab = V26Tab.AI }, onLearn = { tab = V26Tab.LEARN })
                            V26Tab.LEARN -> V26LearnPage(context, refreshTick)
                            V26Tab.AI -> V26AiPage(context, refreshTick, onOpenHistory = { utility = UtilityPage.HISTORY })
                            V26Tab.AUTOMATIONS -> V26AutomationsPage(context, refreshTick) { refreshTick++ }
                            V26Tab.BRAIN -> V26BrainPage(context, refreshTick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationDrawerItem(icon: ImageVector, label: String, action: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        onClick = action,
        icon = { Icon(icon, contentDescription = null) },
        shape = RoundedCornerShape(18.dp),
    )
}

@Composable
private fun CycloneC(size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("C", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium)
    }
}

@Composable
private fun V26HomePage(context: Context, refreshTick: Int, onAi: () -> Unit, onLearn: () -> Unit) {
    val brain = CycloneBrainRuntime.store.stats()
    val apps = AppLearnerRuntime.learnedApps()
    val automations = AutomationRuntime.store.listAutomations()
    val lastTask = CycloneBrainRuntime.store.listReports(1).firstOrNull()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(28.dp),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CycloneC(48)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Cyclone learns your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Use AI for the unknown. Turn repeated success into local knowledge.")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAi, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Ask AI") }
                        FilledTonalButton(onClick = onLearn, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.School, null); Spacer(Modifier.width(6.dp)); Text("Teach app") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V26Metric("Apps", apps.size, Modifier.weight(1f))
                V26Metric("Automations", automations.size, Modifier.weight(1f))
                V26Metric("Task memory", brain.reports, Modifier.weight(1f))
            }
        }
        item {
            V26SectionCard("Phone status") {
                V26StatusRow("Phone control", v26AccessibilityEnabled(context))
                V26StatusRow("Notifications", v26NotificationAccessEnabled(context))
                V26StatusRow("Cyclone Core", DeviceState.bridgeConnected)
                V26StatusRow("Agent control", DeviceState.controller == DeviceState.Controller.AGENT)
            }
        }
        lastTask?.let { task ->
            item {
                V26SectionCard("Latest learning report") {
                    Text(task.goal, fontWeight = FontWeight.SemiBold)
                    Text(task.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.failureSummary.isNotBlank()) Text("Cyclone saved what went wrong so it can avoid repeating it.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun V26AiPage(context: Context, refreshTick: Int, onOpenHistory: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterQuickAgent(context.applicationContext) }
    var request by rememberSaveable { mutableStateOf("") }
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var modelSlug by rememberSaveable {
        mutableStateOf(prefs.getString("openrouter_model", OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id).orEmpty())
    }
    var safeMode by rememberSaveable { mutableStateOf(prefs.getBoolean("safe_mode", true)) }
    var overlayEnabled by rememberSaveable { mutableStateOf(prefs.getBoolean("trace_overlay", false)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var result by remember { mutableStateOf("") }
    val live = com.cyclone.mobile.ai.AiTraceBus.latest
    val recent = AgentTraceRuntime.store.listSessions(4)
    val knownRoutine = if (request.isBlank()) null else CycloneBrainRuntime.store.bestRoutineFor(request)

    fun config() = QuickAgentConfig(
        model = OpenRouterModelPresets.byId(modelSlug.trim().ifBlank { OpenRouterModelPresets.DEEPSEEK_V4_FLASH.id }),
        safeMode = safeMode,
        providerSort = "latency",
    )

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Cyclone AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("The main control point for your phone", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .72f))
                        }
                    }
                    OutlinedTextField(
                        value = request,
                        onValueChange = { request = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("What should Cyclone do?") },
                        placeholder = { Text("Find my newest invoice and download it") },
                        minLines = 3,
                        maxLines = 6,
                        enabled = !busy,
                    )
                    if (knownRoutine != null) {
                        Text("Brain match: ${(knownRoutine.confidence * 100).toInt()}% confidence from ${knownRoutine.successCount} successful run(s). Cyclone can prefer learned knowledge instead of rediscovery.", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                busy = true; result = ""
                                scope.launch {
                                    val run = agent.execute(request, config()) { status = it }
                                    result = run.message
                                    status = if (run.ok) "Completed · ${run.decisions} decisions" else "Stopped · see history for why"
                                    busy = false
                                }
                            },
                            enabled = hasKey && request.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (busy) "Working…" else "Do it") }
                        FilledTonalButton(
                            onClick = {
                                busy = true; result = ""
                                scope.launch {
                                    val run = agent.buildWorkflow(request, config()) { status = it }
                                    result = run.message
                                    status = if (run.ok) "Workflow ready for review" else "Workflow stopped"
                                    busy = false
                                }
                            },
                            enabled = hasKey && request.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.width(6.dp)); Text("Workflow") }
                    }
                    Text(status, fontWeight = FontWeight.Medium)
                    if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            V26SectionCard("Live decision stream") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Overlay", fontWeight = FontWeight.SemiBold)
                        Text("Shows short user-facing decision summaries above other apps. It is a separate visual channel and is not fed back into the AI prompt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = overlayEnabled,
                        onCheckedChange = { enabled ->
                            overlayEnabled = enabled
                            prefs.edit().putBoolean("trace_overlay", enabled).apply()
                            val service = CycloneAccessibilityService.instance
                            if (enabled) {
                                if (service == null) {
                                    overlayEnabled = false
                                    prefs.edit().putBoolean("trace_overlay", false).apply()
                                    Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                                } else AiTraceOverlayRuntime.enable(service)
                            } else AiTraceOverlayRuntime.disable()
                        },
                    )
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                    Text(live?.displayText ?: "No live AI decision yet.", Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Text("This deliberately shows decision/progress summaries rather than raw hidden chain-of-thought.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            V26SectionCard("Model") {
                Text("Use a preset or paste any OpenRouter model slug.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenRouterModelPresets.all.forEach { model ->
                        FilterChip(
                            selected = modelSlug == model.id,
                            onClick = {
                                modelSlug = model.id
                                prefs.edit().putString("openrouter_model", model.id).apply()
                            },
                            label = { Text(model.label) },
                        )
                    }
                }
                OutlinedTextField(
                    value = modelSlug,
                    onValueChange = {
                        modelSlug = it
                        prefs.edit().putString("openrouter_model", it.trim()).apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Custom OpenRouter model slug") },
                    placeholder = { Text("provider/model-name") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Safe Mode")
                        Text("Blocks obvious payment, sending, purchase and destructive actions.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = safeMode, onCheckedChange = { safeMode = it; prefs.edit().putBoolean("safe_mode", it).apply() })
                }
            }
        }

        item {
            V26SectionCard("OpenRouter connection") {
                if (!hasKey) {
                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(onClick = {
                        runCatching { OpenRouterSecretStore.save(context, keyDraft) }
                            .onSuccess { keyDraft = ""; hasKey = true }
                            .onFailure { result = it.message ?: "Could not save key" }
                    }, enabled = keyDraft.isNotBlank()) {
                        Icon(Icons.Rounded.Key, null); Spacer(Modifier.width(6.dp)); Text("Secure key")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("OpenRouter key secured in Android Keystore", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { OpenRouterSecretStore.clear(context); hasKey = false }) { Text("Replace") }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Conversation history", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Open a run to see decisions, actions, verification and failures.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onOpenHistory) { Icon(Icons.Rounded.History, null); Spacer(Modifier.width(5.dp)); Text("All") }
            }
        }
        items(recent, key = { it.id }) { session -> AiSessionCompact(session) }
    }
}

@Composable
private fun AiSessionCompact(session: AiTraceSession) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.goal, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${session.status} · ${session.decisions} decisions · ${formatV26Time(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun V26LearnPage(context: Context, refreshTick: Int) {
    var view by rememberSaveable { mutableStateOf(LearnView.HOME) }
    var selectedPackage by rememberSaveable { mutableStateOf("") }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("Learn how this app works. Focus on useful navigation and do not submit, pay, delete or change anything.") }
    var mode by rememberSaveable { mutableStateOf(LearningMode.GUIDED) }
    var useAi by rememberSaveable { mutableStateOf(true) }
    var filter by rememberSaveable { mutableStateOf("") }
    var askText by rememberSaveable { mutableStateOf("") }
    var localAnswer by rememberSaveable { mutableStateOf("") }
    val progress = AppLearnerRuntime.progress()
    val learned = AppLearnerRuntime.learnedApps()
    val apps = remember { v26LauncherApps(context) }

    LaunchedEffect(progress.state, progress.packageName) {
        if (progress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
            selectedPackage = progress.packageName.orEmpty()
            selectedLabel = progress.appLabel.orEmpty()
            view = LearnView.PROGRESS
        }
    }

    when (view) {
        LearnView.HOME -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.School, null, modifier = Modifier.size(34.dp))
                        Text("Teach Cyclone how your apps work", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("App Learner is now a full Cyclone page. It builds semantic maps and reuses known routes instead of rediscovering the same UI every time.")
                        Button(onClick = { view = LearnView.NEW }, modifier = Modifier.fillMaxWidth()) { Text("Teach an app") }
                        if (progress.state in setOf(LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
                            FilledTonalButton(onClick = { view = LearnView.PROGRESS }, modifier = Modifier.fillMaxWidth()) { Text("Return to ${progress.appLabel ?: "learning"}") }
                        }
                    }
                }
            }
            item { Text("Apps Cyclone knows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (learned.isEmpty()) {
                item { V26Empty("No learned app maps yet", "Start with Android Settings or another harmless app.") }
            } else {
                items(learned, key = { it.packageName }) { app ->
                    Card(onClick = { selectedPackage = app.packageName; selectedLabel = app.label; view = LearnView.DETAIL }, shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                Text("${(app.confidence * 100).toInt()}% knowledge · ${app.knowledgeState.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Rounded.Map, null)
                        }
                    }
                }
            }
        }
        LearnView.NEW -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                V26PageHeading("New learning session", "Select one installed app and define what Cyclone should understand.")
                OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search installed apps") })
            }
            items(apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }.take(18), key = { it.packageName }) { app ->
                FilterChip(selected = selectedPackage == app.packageName, onClick = { selectedPackage = app.packageName; selectedLabel = app.label }, label = { Text(app.label) })
            }
            item {
                OutlinedTextField(value = instruction, onValueChange = { instruction = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, label = { Text("What should Cyclone learn?") })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningMode.entries.forEach { item -> FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item.name.lowercase().replaceFirstChar(Char::uppercase)) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("AI guidance")
                        Text("Used only for unknown semantic choices; deterministic exploration handles known states.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = useAi, onCheckedChange = { useAi = it })
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.Security, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cyclone maps but does not autonomously press purchase, payment, send, submit, delete, authentication, permission or account-security boundaries.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = {
                        AppLearnerRuntime.start(context, selectedPackage, selectedLabel, instruction, mode, useAi)
                        view = LearnView.PROGRESS
                    },
                    enabled = selectedPackage.isNotBlank() && instruction.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start learning") }
                OutlinedButton(onClick = { view = LearnView.HOME }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
        LearnView.PROGRESS -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Learning ${progress.appLabel ?: selectedLabel}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Current screen: ${progress.currentScreen ?: "Observing…"}")
                        Text(progress.currentActivity, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        progress.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V26Metric("Screens", progress.screens, Modifier.weight(1f))
                    V26Metric("Actions", progress.actions, Modifier.weight(1f))
                    V26Metric("Paths", progress.transitions, Modifier.weight(1f))
                }
            }
            item {
                OutlinedTextField(value = instruction, onValueChange = { instruction = it; AppLearnerRuntime.updateInstruction(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Guide Cyclone while it learns") }, minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { if (progress.state == LearnerSessionState.PAUSED) AppLearnerRuntime.resume() else AppLearnerRuntime.pause() }, modifier = Modifier.weight(1f)) {
                        Icon(if (progress.state == LearnerSessionState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.width(4.dp)); Text(if (progress.state == LearnerSessionState.PAUSED) "Resume" else "Pause")
                    }
                    FilledTonalButton(onClick = { if (progress.state == LearnerSessionState.WAITING_FOR_HUMAN) AppLearnerRuntime.returnFromTakeover() else AppLearnerRuntime.takeOver() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Security, null); Spacer(Modifier.width(4.dp)); Text(if (progress.state == LearnerSessionState.WAITING_FOR_HUMAN) "Return" else "Take over")
                    }
                }
                OutlinedButton(onClick = { AppLearnerRuntime.stop(); view = LearnView.HOME }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(4.dp)); Text("Stop learning") }
                if (progress.state !in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
                    Button(onClick = { selectedPackage = progress.packageName.orEmpty(); view = LearnView.DETAIL }, modifier = Modifier.fillMaxWidth()) { Text("View learned app") }
                }
            }
        }
        LearnView.DETAIL -> {
            val graph = AppLearnerRuntime.graph(selectedPackage)
            if (graph == null) {
                V26Empty("No knowledge found", "Cyclone needs another learning session for this app.")
            } else {
                V26LearnedAppDetail(graph, askText, localAnswer, onAskText = { askText = it }, onAsk = { localAnswer = AppLearnerRuntime.ask(graph.app.packageName, askText) }, onBack = { view = LearnView.HOME }, onTeachMore = { instruction = graph.app.instructionSummary.ifBlank { "Continue learning useful areas of this app." }; view = LearnView.NEW })
            }
        }
    }
}

@Composable
private fun V26LearnedAppDetail(
    graph: AppGraphSnapshot,
    askText: String,
    answer: String,
    onAskText: (String) -> Unit,
    onAsk: () -> Unit,
    onBack: () -> Unit,
    onTeachMore: () -> Unit,
) {
    val skills = AppLearnerRuntime.skillCandidates(graph.app.packageName)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(graph.app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Cyclone knowledge: ${(graph.app.confidence * 100).toInt()}%")
                    Text("${graph.screens.size} screens · ${graph.actions.size} actions · ${graph.transitions.size} paths · ${skills.size} Skill candidates", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTeachMore) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Teach more") }
                        OutlinedButton(onClick = onBack) { Text("All apps") }
                    }
                }
            }
        }
        item {
            V26SectionCard("Ask this app") {
                OutlinedTextField(value = askText, onValueChange = onAskText, modifier = Modifier.fillMaxWidth(), placeholder = { Text("How do I find invoices?") })
                Button(onClick = onAsk, enabled = askText.isNotBlank()) { Text("Ask learned graph") }
                if (answer.isNotBlank()) Text(answer)
            }
        }
        item { Text("Known screens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(graph.screens.take(24), key = { it.id }) { screen ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(screen.title, fontWeight = FontWeight.SemiBold)
                        Text("${(screen.confidence * 100).toInt()}%")
                    }
                    Text(screen.purpose, style = MaterialTheme.typography.bodySmall)
                    Text(screen.knowledgeState.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            V26SectionCard("Known navigation") {
                graph.transitions.take(40).forEach { transition ->
                    val from = graph.screens.firstOrNull { it.id == transition.fromScreenId }?.title ?: "Unknown"
                    val to = graph.screens.firstOrNull { it.id == transition.toScreenId }?.title ?: "Unknown"
                    val action = graph.actions.firstOrNull { it.id == transition.actionId }?.label ?: "action"
                    Text("$from → $action → $to", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun V26AutomationsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val automations = AutomationRuntime.store.listAutomations()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            V26PageHeading("Automations", "Agent 2 turns learned routes into deterministic reusable work.")
            Button(onClick = {
                val service = CycloneAccessibilityService.instance
                if (service == null) Toast.makeText(context, "Enable Cyclone Accessibility first", Toast.LENGTH_LONG).show()
                else {
                    service.showGuidedRecorderOverlay()
                    Toast.makeText(context, "Teach bubble opened", Toast.LENGTH_SHORT).show()
                    (context as? Activity)?.moveTaskToBack(true)
                }
            }) { Icon(Icons.Rounded.Gesture, null); Spacer(Modifier.width(6.dp)); Text("Teach a routine") }
        }
        if (automations.isEmpty()) {
            item { V26Empty("No automations yet", "Teach a routine, create one from App Learner, or ask AI to build a workflow.") }
        } else {
            items(automations, key = { it.id }) { automation ->
                Card(shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Bolt, null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(automation.name, fontWeight = FontWeight.SemiBold)
                                Text("${automation.steps.size} steps · ${automation.trigger.type.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = automation.enabled, onCheckedChange = { AutomationRuntime.store.saveAutomation(automation.copy(enabled = it)); refresh() })
                        }
                        if (automation.description.isNotBlank()) Text(automation.description, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { AutomationRuntime.router.runManual(automation.id); refresh() }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("Run") }
                    }
                }
            }
        }
    }
}

@Composable
private fun V26BrainPage(context: Context, refreshTick: Int) {
    val brain = CycloneBrainRuntime.store
    val stats = brain.stats()
    val reports = brain.listReports(18)
    val routines = brain.listRoutines(14)
    val apps = AppLearnerRuntime.learnedApps()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.AccountTree, null, modifier = Modifier.size(36.dp))
                    Text("Obsidian Brain", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Cyclone stores semantic app maps, post-task reports, reusable action patterns and optimization evidence locally. The runtime database stays structured; the human-readable mirror lives under Cyclone Brain/.")
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                V26Metric("Reports", stats.reports, Modifier.weight(1f))
                V26Metric("Successes", stats.successfulTasks, Modifier.weight(1f))
                V26Metric("Routines", stats.reusableRoutines, Modifier.weight(1f))
            }
        }
        item {
            V26SectionCard("What Cyclone knows") {
                Text("${apps.size} learned apps · ${AutomationRuntime.store.listAutomations().size} automations")
                apps.take(8).forEach { app -> Text("• ${app.label}: ${(app.confidence * 100).toInt()}% knowledge", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (routines.isNotEmpty()) {
            item { Text("Reusable routine memory", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(routines, key = { it.signature }) { routine ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(routine.goalKey.ifBlank { "Learned routine" }, fontWeight = FontWeight.SemiBold)
                        Text("${(routine.confidence * 100).toInt()}% confidence · ${routine.successCount} success · ${routine.failureCount} failure", style = MaterialTheme.typography.bodySmall)
                        Text(routine.toolSequence.ifBlank { "No action sequence" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Text("Task learning reports", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (reports.isEmpty()) {
            item { V26Empty("No task reports yet", "After an AI phone task, Cyclone writes a small local report about what worked, what failed and what can be reused.") }
        } else {
            items(reports, key = { it.id }) { report ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(report.goal, fontWeight = FontWeight.SemiBold)
                        Text("${report.status} · ${formatV26Time(report.createdAt)}", style = MaterialTheme.typography.labelSmall)
                        Text(report.summary, style = MaterialTheme.typography.bodySmall)
                        if (report.failureSummary.isNotBlank()) Text("Saved failure evidence", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AiHistoryPage(context: Context, refreshTick: Int, selectedId: String?, onSelect: (String?) -> Unit) {
    val sessions = AgentTraceRuntime.store.listSessions(60)
    if (selectedId != null) {
        val session = sessions.firstOrNull { it.id == selectedId }
        val events = AgentTraceRuntime.store.events(selectedId)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedButton(onClick = { onSelect(null) }) { Icon(Icons.Rounded.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text("All history") }
                Spacer(Modifier.height(10.dp))
                Text(session?.goal ?: "AI session", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                session?.let { Text("${it.status} · ${it.model} · ${it.decisions} decisions", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Decision trace", fontWeight = FontWeight.SemiBold)
                        Text("This timeline is built from explicit progress summaries, tool calls, verification and failures. It is not raw hidden chain-of-thought.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(events, key = { it.id }) { event ->
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(event.kind, fontWeight = FontWeight.SemiBold, color = when (event.ok) { false -> MaterialTheme.colorScheme.error; true -> MaterialTheme.colorScheme.primary; null -> MaterialTheme.colorScheme.onSurface })
                            Text(formatV26Time(event.timestampMs), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(event.displayText)
                        event.code?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        event.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { V26PageHeading("AI history", "Open any session to inspect what Cyclone decided, which phone tools ran, verification results and where recovery failed.") }
            if (sessions.isEmpty()) item { V26Empty("No AI sessions yet", "Run a task from the AI tab and its decision history will appear here.") }
            else items(sessions, key = { it.id }) { session ->
                Card(onClick = { onSelect(session.id) }, shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.goal, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${session.status} · ${session.decisions} decisions · ${formatV26Time(session.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        session.result?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHub(onConnections: () -> Unit, onPermissions: () -> Unit, onBrain: () -> Unit, onHistory: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V26PageHeading("Cyclone navigation", "Profile, settings, connections, permissions, Brain and inspectability live behind the C button from every page.") }
        item { UtilityCard(Icons.Rounded.Link, "Connections", "Cyclone Core, OpenRouter and device pairing", onConnections) }
        item { UtilityCard(Icons.Rounded.Security, "Permissions", "Accessibility, notifications and calendar access", onPermissions) }
        item { UtilityCard(Icons.Rounded.AccountTree, "Obsidian Brain", "Learned apps, task reports and reusable routine memory", onBrain) }
        item { UtilityCard(Icons.Rounded.History, "AI history", "Decision traces and failure inspection", onHistory) }
    }
}

@Composable
private fun ConnectionsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    val defaultName = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
    val defaultId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)?.let { "android-$it" }.orEmpty()
    var coreUrl by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var deviceName by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultName).orEmpty()) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { V26PageHeading("Connections", "Pair Cyclone Mobile with Core/Hermes and keep AI credentials under user control.") }
        item {
            V26SectionCard("Cyclone Core") {
                OutlinedTextField(value = coreUrl, onValueChange = { coreUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("WebSocket URL") }, placeholder = { Text("ws://192.168.1.10:8787/api/v1/mobile/connect") })
                OutlinedTextField(value = token, onValueChange = { token = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pairing token") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = deviceName, onValueChange = { deviceName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Device name") })
                Button(onClick = {
                    prefs.edit()
                        .putString("coreWsUrl", coreUrl.trim())
                        .putString("coreToken", token)
                        .putString("deviceId", prefs.getString("deviceId", defaultId).orEmpty().ifBlank { defaultId })
                        .putString("deviceName", deviceName.trim().ifBlank { defaultName })
                        .apply()
                    BridgeClient.stop(); BridgeClient.start(context); refresh()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(6.dp)); Text(if (DeviceState.bridgeConnected) "Reconnect" else "Save & connect") }
                V26StatusRow("Core connection", DeviceState.bridgeConnected)
            }
        }
        item {
            V26SectionCard("OpenRouter") {
                V26StatusRow("Encrypted API key", OpenRouterSecretStore.hasKey(context))
                Text("Add, replace and choose custom models from the central AI tab.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { V26PageHeading("Permissions", "Cyclone only acts through capabilities Android or the user explicitly grants.") }
        item { PermissionCard("Phone control", "Accessibility service", v26AccessibilityEnabled(context)) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); refresh() } }
        item { PermissionCard("Notifications", "Open and understand selected notification events", v26NotificationAccessEnabled(context)) { context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); refresh() } }
        item { PermissionCard("Calendar", "Deterministic schedule and conflict checks", context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) { (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100); refresh() } }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("AI trace overlay", fontWeight = FontWeight.SemiBold)
                    Text("V2.6 uses Cyclone's existing AccessibilityService-owned TYPE_ACCESSIBILITY_OVERLAY. It does not require the generic Draw over other apps permission for this feature.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AboutPage() {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CycloneC(58)
                    Text("Cyclone V2.6 Beta", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("AI-first navigation + full-page App Learner + custom models + decision history + user-only trace overlay + persistent Obsidian Brain.")
                }
            }
        }
        item {
            V26SectionCard("Architecture boundary") {
                Text("Level 4 · Hermes / natural language")
                Text("Level 3 · learned app knowledge + Skills")
                Text("Level 2 · Automation Studio")
                Text("Level 1 · universal phone toolbox")
                Text("Level 0 · Android Accessibility / OS")
                Text("V2.6 changes the product shell and adds memory/inspectability without replacing those layers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UtilityCard(icon: ImageVector, title: String, subtitle: String, action: () -> Unit) {
    Card(onClick = action, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PermissionCard(title: String, subtitle: String, enabled: Boolean, action: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = action) { Text(if (enabled) "Open" else "Enable") }
        }
    }
}

@Composable
private fun V26SectionCard(title: String, content: @Composable Column.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun V26Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun V26StatusRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(6.dp))
            Text(if (ok) "Ready" else "Needs setup", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun V26PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun V26Empty(title: String, body: String) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun v26LauncherApps(context: Context): List<V26InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            V26InstalledApp(pkg, info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { pkg })
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun v26AccessibilityEnabled(context: Context): Boolean {
    val component = ComponentName(context, CycloneAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}

private fun v26NotificationAccessEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

private fun formatV26Time(timestamp: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(timestamp))
