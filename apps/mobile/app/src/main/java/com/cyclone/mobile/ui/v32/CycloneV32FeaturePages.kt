package com.cyclone.mobile.ui.v32

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings as SettingsIcon
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.cyclone.mobile.BridgeClient
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.TaskResultActivityV292
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.applearner.LearnerSessionState
import com.cyclone.mobile.applearner.discardFollowMeSession
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.debug.PageDebugSandboxV293
import com.cyclone.mobile.gateway.GatewaySettingsActivity
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.ui.GatewayAiCard
import kotlinx.coroutines.launch

private const val TEAMWORK_SNIPER_PACKAGE = "com.cyclone.teamworksniper"

@Composable
internal fun V32TeachPage(context: Context, refreshTick: Int) {
    val follow = FollowMeLearnerRuntime.progress()
    val appProgress = AppLearnerRuntime.progress()
    val learnedApps = AppLearnerRuntime.learnedApps()
    val gestureCount = follow.teachingSessionId?.let { TeachingGestureEvidenceV292.list(context, it).size } ?: 0

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CyclonePageIntro("Show it once", "Teach Cyclone", "Use your phone normally. Cyclone turns the useful path into reusable knowledge.") }
        item {
            CycloneHeroCard(
                title = if (follow.active) if (follow.paused) "Teaching paused" else "Learning with you" else "Follow Me",
                body = if (follow.active) follow.currentApp.ifBlank { follow.message } else "Tap, swipe and navigate naturally while Cyclone learns the before-and-after path.",
                icon = Icons.Rounded.Visibility,
                tone = CyclonePastel.MINT,
            ) {
                if (follow.active) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V32SmallMetric("Pages", follow.screensSeen, Modifier.weight(1f))
                        V32SmallMetric("Actions", follow.actionsSeen, Modifier.weight(1f))
                        V32SmallMetric("Swipes", gestureCount, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (follow.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause() }, modifier = Modifier.weight(1f)) {
                            Icon(if (follow.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.size(5.dp)); Text(if (follow.paused) "Resume" else "Pause")
                        }
                        Button(onClick = { FollowMeLearnerRuntime.stop() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.size(5.dp)); Text("Finish") }
                    }
                    OutlinedButton(onClick = { discardFollowMeSession(context) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.size(5.dp)); Text("Discard") }
                } else {
                    Button(onClick = {
                        if (appProgress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) AppLearnerRuntime.stop()
                        FollowMeLearnerRuntime.start(context)
                        Toast.makeText(context, "Follow Me started", Toast.LENGTH_SHORT).show()
                        (context as? Activity)?.moveTaskToBack(true)
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.size(6.dp)); Text("Start Follow Me") }
                }
                Text("Typed text, passwords, OTPs and sensitive fields are not stored.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { CycloneSectionTitle("Other ways to teach") }
        item {
            CycloneSimpleCard {
                V32FeatureRow(Icons.Rounded.Gesture, "Place exact steps", "Add Tap, Hold, Swipe, Check, Wait, Back and Home steps.")
                Button(onClick = {
                    val service = CycloneAccessibilityService.instance
                    if (service == null) Toast.makeText(context, "Enable phone control first", Toast.LENGTH_LONG).show()
                    else { service.showGuidedRecorderOverlay(); (context as? Activity)?.moveTaskToBack(true) }
                }, enabled = !follow.active, modifier = Modifier.fillMaxWidth()) { Text("Open manual teacher") }
            }
        }
        item {
            CycloneSimpleCard {
                V32FeatureRow(Icons.Rounded.Memory, "Understand one page", "Freeze the current page and inspect what Android and Cyclone understand.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val service = CycloneAccessibilityService.instance
                        if (service == null) Toast.makeText(context, "Enable phone control first", Toast.LENGTH_LONG).show()
                        else { PageDebugSandboxV293.start(service); (context as? Activity)?.moveTaskToBack(true) }
                    }, enabled = !follow.active, modifier = Modifier.weight(1f)) { Text("Capture") }
                    OutlinedButton(onClick = { PageDebugSandboxV293.launchReport(context) }, modifier = Modifier.weight(1f)) { Text("Inspect") }
                }
            }
        }
        item { OutlinedButton(onClick = { RoutineTeachingRuntime.launchReport(context, null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.size(6.dp)); Text("Teaching history") } }
        item { CycloneSectionTitle("Apps Cyclone knows") }
        if (learnedApps.isEmpty()) item { V32EmptyCard("No learned apps yet", "Start Follow Me and move through one useful task.") }
        else items(learnedApps.take(20), key = { it.packageName }) { app ->
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.Bold)
                        Text("${(app.confidence * 100).toInt()}% confidence · ${app.knowledgeState.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private enum class V32AiMode { PHONE, BRAIN }

@Composable
internal fun V32AiPage(context: Context, refreshTick: Int, onSettings: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val agent = remember { OpenRouterAdaptiveAgent(context) }
    var mode by rememberSaveable { mutableStateOf(V32AiMode.PHONE) }
    var phoneRequest by rememberSaveable { mutableStateOf("") }
    var brainRequest by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var historyTick by remember { mutableIntStateOf(0) }
    val modelSlug = prefs.getString("openrouter_model", OpenRouterModelPresets.DEFAULT.id).orEmpty().ifBlank { OpenRouterModelPresets.DEFAULT.id }
    val accessProfile = CycloneAiAccessProfileStore.read(context)
    val hasKey = OpenRouterSecretStore.hasKey(context)
    val history = remember(refreshTick, historyTick) { BrainChatRuntime.history(context, 12) }
    fun config() = QuickAgentConfig(
        model = OpenRouterModelPresets.byId(modelSlug),
        safeMode = accessProfile != CycloneAiAccessProfile.FULL,
        accessProfile = accessProfile,
    )

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CyclonePageIntro("One clear request", "Cyclone AI", "Known routes first, AI for uncertainty, vision only when structured phone evidence is not enough.") }
        item { CycloneSegmentedControl(listOf("Control phone", "Ask Brain"), if (mode == V32AiMode.PHONE) 0 else 1, { mode = if (it == 0) V32AiMode.PHONE else V32AiMode.BRAIN }) }
        if (mode == V32AiMode.PHONE) {
            item {
                CycloneHeroCard("What should happen?", "Describe the outcome. Cyclone handles the phone one verified step at a time.", Icons.Rounded.AutoAwesome, tone = CyclonePastel.LILAC) {
                    OutlinedTextField(phoneRequest, { phoneRequest = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, enabled = !busy, label = { Text("Phone task") }, placeholder = { Text("Open my podcast app and find saved episodes") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            busy = true; status = "Starting…"; result = ""
                            scope.launch {
                                val run = agent.execute(phoneRequest, config()) { status = it }
                                result = run.message; status = if (run.ok) "Completed and checked" else "Stopped safely"; busy = false
                            }
                        }, enabled = phoneRequest.isNotBlank() && !busy && hasKey, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.size(5.dp)); Text(if (busy) "Working…" else "Do it") }
                        FilledTonalButton(onClick = {
                            busy = true; status = "Building a reviewable routine…"; result = ""
                            scope.launch {
                                val run = agent.buildWorkflow(phoneRequest, config()) { status = it }
                                result = run.message; status = if (run.ok) "Routine ready for review" else "No routine created"; busy = false
                            }
                        }, enabled = phoneRequest.isNotBlank() && !busy && hasKey, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.size(5.dp)); Text("Make routine") }
                    }
                    if (!hasKey) OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Key, null); Spacer(Modifier.size(5.dp)); Text("Add AI key in Settings") }
                }
            }
        } else {
            if (history.isNotEmpty()) items(history.takeLast(8), key = { it.id }) { message ->
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(if (message.role == "user") "You" else "Cyclone Brain", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold); Text(message.text) }
                }
            }
            item {
                CycloneSimpleCard {
                    OutlinedTextField(brainRequest, { brainRequest = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5, label = { Text("Ask or teach the Brain") })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val prompt = brainRequest.trim(); brainRequest = ""; busy = true
                            scope.launch { result = BrainChatRuntime.chat(context, prompt, modelSlug); historyTick++; busy = false }
                        }, enabled = brainRequest.isNotBlank() && !busy && hasKey, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Rounded.Send, null); Spacer(Modifier.size(5.dp)); Text("Ask") }
                        FilledTonalButton(onClick = { BrainChatRuntime.saveKnowledge(context, brainRequest.trim()); brainRequest = ""; historyTick++ }, enabled = brainRequest.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.size(5.dp)); Text("Remember") }
                    }
                }
            }
        }
        if (status.isNotBlank() || result.isNotBlank()) item {
            CycloneSimpleCard {
                if (status.isNotBlank()) Text(status, fontWeight = FontWeight.Bold)
                if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val latest = AgentTraceRuntime.store.listSessions(1).firstOrNull()
                if (latest != null && latest.status != "RUNNING") OutlinedButton(onClick = {
                    context.startActivity(Intent(context, TaskResultActivityV292::class.java).putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, latest.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.size(5.dp)); Text("Open decision timeline") }
            }
        }
        item { GatewayAiCard(context, refreshTick) }
    }
}

@Composable
internal fun V32BrainPage(context: Context, refreshTick: Int) {
    val store = AdaptiveBrainRuntime.store
    val skills = remember(refreshTick) { store.listMicroSkills(60) }
    val apps = remember(refreshTick) { store.listApps() }
    val paths = remember(refreshTick) { store.listPaths(40) }
    val notes = remember(refreshTick) { store.listNotes(30) }
    val reports = remember(refreshTick) { CycloneBrainRuntime.store.listReports(12) }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CyclonePageIntro("Learn once, reuse", "Cyclone Brain", "A simple view of what Cyclone knows, how strong the evidence is and what changed recently.") }
        item {
            CycloneHeroCard("${skills.count { it.confidence >= .7 }} strong skills", "Across ${apps.size} apps and ${paths.size} reusable paths.", Icons.Rounded.AccountTree, tone = CyclonePastel.SKY) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    V32SmallMetric("Skills", skills.size, Modifier.weight(1f)); V32SmallMetric("Apps", apps.size, Modifier.weight(1f)); V32SmallMetric("Paths", paths.size, Modifier.weight(1f))
                }
            }
        }
        item { CycloneSectionTitle("Reusable skills") }
        if (skills.isEmpty()) item { V32EmptyCard("Nothing verified yet", "Teach a routine or complete a phone task to create evidence.") }
        else items(skills.take(16), key = { it.signature }) { skill ->
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.Bold)
                        Text("${skill.successCount} success · ${skill.failureCount} failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CycloneStatusPill("${(skill.confidence * 100).toInt()}%", skill.confidence >= .55)
                }
            }
        }
        if (notes.isNotEmpty()) item {
            CycloneSimpleCard {
                Text("Latest learning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                notes.take(6).forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (reports.isNotEmpty()) item {
            CycloneSimpleCard {
                Text("Recent outcomes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                reports.take(5).forEach { Text("• ${it.goal}: ${it.summary}", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
internal fun V32SettingsPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    val prefs = context.getSharedPreferences("cyclone", Context.MODE_PRIVATE)
    val aiPrefs = context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
    val defaultName = listOf(Build.MANUFACTURER, Build.MODEL).filter(String::isNotBlank).joinToString(" ")
    var keyDraft by rememberSaveable { mutableStateOf("") }
    var hasKey by remember(refreshTick) { mutableStateOf(OpenRouterSecretStore.hasKey(context)) }
    var selectedModel by rememberSaveable { mutableStateOf(aiPrefs.getString("openrouter_model", OpenRouterModelPresets.DEFAULT.id).orEmpty().ifBlank { OpenRouterModelPresets.DEFAULT.id }) }
    var accessProfile by rememberSaveable { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    var url by rememberSaveable { mutableStateOf(prefs.getString("coreWsUrl", "").orEmpty()) }
    var token by rememberSaveable { mutableStateOf(prefs.getString("coreToken", "").orEmpty()) }
    var name by rememberSaveable { mutableStateOf(prefs.getString("deviceName", defaultName).orEmpty()) }
    val primaryControl = CyclonePermissionSetup.primaryControlEnabled(context)
    val notificationAccess = CyclonePermissionSetup.notificationAccessEnabled(context)
    val resultNotifications = CyclonePermissionSetup.resultNotificationsEnabled(context)
    val batteryUnrestricted = CyclonePermissionSetup.batteryUnrestricted(context)
    val essentialReady = listOf(primaryControl, notificationAccess, resultNotifications, batteryUnrestricted).count { it }
    val teamworkSniperInstalled = remember(refreshTick) {
        context.packageManager.getLaunchIntentForPackage(TEAMWORK_SNIPER_PACKAGE) != null
    }
    fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CyclonePageIntro("Keep control", "Settings", "Phone access, AI, connections and safety in one quiet place.") }
        item {
            CycloneHeroCard(
                title = if (essentialReady == 4) "Phone setup complete" else "$essentialReady of 4 essentials ready",
                body = "Every permission is optional, Android-owned and reversible. Cyclone asks only after you tap a setup row.",
                icon = Icons.Rounded.Security,
                tone = if (essentialReady == 4) CyclonePastel.MINT else CyclonePastel.LEMON,
            ) {
                CycloneStatusPill(if (essentialReady == 4) "Ready" else "Finish setup", essentialReady == 4)
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("Essential access")
                CyclonePermissionRow(Icons.Rounded.Security, "Phone control", "Read semantic controls and perform policy-approved taps, typing and gestures.", primaryControl, if (primaryControl) "Manage" else "Enable") {
                    open(CyclonePermissionSetup.accessibilitySettings())
                }
                CyclonePermissionRow(Icons.Rounded.Notifications, "Notification triggers", "React to selected app notifications without watching screenshots.", notificationAccess, if (notificationAccess) "Manage" else "Enable") {
                    open(CyclonePermissionSetup.notificationAccessSettings())
                }
                CyclonePermissionRow(Icons.Rounded.History, "Result notifications", "Show a concise, visible result after an AI task or routine.", resultNotifications, if (resultNotifications) "Manage" else "Allow") {
                    if (!resultNotifications && Build.VERSION.SDK_INT >= 33) {
                        (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 320) }
                    } else {
                        open(CyclonePermissionSetup.appDetails(context))
                    }
                }
                CyclonePermissionRow(Icons.Rounded.BatteryChargingFull, "Unrestricted battery", "Keep PC sessions and scheduled automation reliable while the phone is idle.", batteryUnrestricted, if (batteryUnrestricted) "Manage" else "Allow") {
                    open(if (batteryUnrestricted) CyclonePermissionSetup.batteryOptimizationSettings() else CyclonePermissionSetup.batteryExemptionRequest(context))
                }
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("Advanced control")
                val enhancedControl = CyclonePermissionSetup.enhancedControlEnabled(context)
                val agentKeyboard = CyclonePermissionSetup.agentKeyboardEnabled(context)
                val overlay = CyclonePermissionSetup.overlayEnabled(context)
                val exactTiming = CyclonePermissionSetup.exactTimingEnabled(context)
                val calendar = CyclonePermissionSetup.calendarEnabled(context)
                CyclonePermissionRow(Icons.Rounded.Layers, "Enhanced control engine", "Optional second Accessibility backend for difficult apps and richer takeover tools.", enhancedControl, if (enhancedControl) "Manage" else "Enable") {
                    open(CyclonePermissionSetup.accessibilitySettings())
                }
                CyclonePermissionRow(Icons.Rounded.Keyboard, "Cyclone Agent Keyboard", "Optional input method for more reliable text entry in stubborn fields.", agentKeyboard, if (agentKeyboard) "Manage" else "Enable") {
                    open(CyclonePermissionSetup.keyboardSettings())
                }
                CyclonePermissionRow(Icons.Rounded.Layers, "Display over apps", "Show visible takeover and connection controls above the current app.", overlay, if (overlay) "Manage" else "Allow") {
                    open(CyclonePermissionSetup.overlaySettings(context))
                }
                CyclonePermissionRow(Icons.Rounded.Schedule, "Precise timing", "Optional exact scheduling for routines that cannot tolerate a flexible window.", exactTiming, if (exactTiming) "Manage" else "Allow") {
                    open(CyclonePermissionSetup.exactTimingSettings(context))
                }
                CyclonePermissionRow(Icons.Rounded.CalendarMonth, "Calendar context", "Optional read-only matching for calendar-aware routines.", calendar, if (calendar) "Manage" else "Allow") {
                    if (!calendar) {
                        (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.READ_CALENDAR), 321) }
                    } else {
                        open(CyclonePermissionSetup.appDetails(context))
                    }
                }
                V32FeatureRow(Icons.Rounded.ScreenShare, "Screen sharing asks every session", "Android's screen-capture consent is never converted into a permanent background grant.")
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("AI access profile")
                Text("Android permissions decide what Cyclone can do. This separate profile decides what AI may use without stopping.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CycloneAiAccessProfile.entries.forEach { profile ->
                    V32AiAccessProfileCard(
                        profile = profile,
                        selected = accessProfile == profile,
                        onClick = {
                            accessProfile = profile
                            CycloneAiAccessProfileStore.write(context, profile)
                            refresh()
                        },
                    )
                }
                Text("Payments, credentials, destructive changes, security settings and final send actions still require a current local confirmation in every profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("AI model & key")
                if (hasKey) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.size(8.dp)); Column(Modifier.weight(1f)) { Text("OpenRouter key secured", fontWeight = FontWeight.Bold); Text("Protected by Android Keystore", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        OutlinedButton(onClick = { OpenRouterSecretStore.clear(context); hasKey = false; refresh() }) { Text("Remove") }
                    }
                } else {
                    OutlinedTextField(keyDraft, { keyDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("OpenRouter API key") }, visualTransformation = PasswordVisualTransformation())
                    Button(onClick = { OpenRouterSecretStore.save(context, keyDraft.trim()); keyDraft = ""; hasKey = true; refresh() }, enabled = keyDraft.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Key, null); Spacer(Modifier.size(5.dp)); Text("Secure key") }
                }
                Text("Model", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpenRouterModelPresets.all.forEach { model -> FilterChip(selected = selectedModel == model.id, onClick = { selectedModel = model.id; aiPrefs.edit().putString("openrouter_model", model.id).apply() }, label = { Text(model.label) }) }
                }
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("Companion apps")
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Teamwork Sniper", fontWeight = FontWeight.Bold)
                        Text(
                            "Automatic Picnic Teamwork shift matching. Rules, permissions and armed state stay in the separate companion app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CycloneStatusPill(if (teamworkSniperInstalled) "Installed" else "Separate APK", teamworkSniperInstalled)
                }
                if (teamworkSniperInstalled) {
                    Button(
                        onClick = {
                            val launch = context.packageManager.getLaunchIntentForPackage(TEAMWORK_SNIPER_PACKAGE)
                            if (launch == null) {
                                Toast.makeText(context, "Teamwork Sniper is not installed.", Toast.LENGTH_SHORT).show()
                            } else {
                                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Bolt, null)
                        Spacer(Modifier.size(6.dp))
                        Text("Open Teamwork Sniper")
                    }
                } else {
                    Text(
                        "Install Teamwork-Sniper-3.5.2-beta.apk separately to use shift matching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("Connections")
                Button(onClick = { context.startActivity(Intent(context, GatewaySettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Smartphone, null); Spacer(Modifier.size(6.dp)); Text("PC Gateway & QR pairing") }
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Optional Cyclone Core URL") })
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Core pairing token") }, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Device name") })
                FilledTonalButton(onClick = {
                    prefs.edit().putString("coreWsUrl", url.trim()).putString("coreToken", token.trim()).putString("deviceName", name.trim().ifBlank { defaultName }).apply()
                    BridgeClient.stop(); BridgeClient.start(context); refresh()
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.size(6.dp)); Text(if (DeviceState.bridgeConnected) "Reconnect Core" else "Save Core connection") }
            }
        }
        item {
            CycloneSimpleCard {
                CycloneSectionTitle("Privacy & safety")
                V32FeatureRow(Icons.Rounded.Security, "One action authority", "Every UI, AI, routine and PC request goes through Cyclone policy and the canonical phone executor.")
                V32FeatureRow(Icons.Rounded.Visibility, "Verified changes", "Transport success never substitutes for an observed phone result.")
                V32FeatureRow(Icons.Rounded.Key, "Sensitive text stays private", "Passwords, OTPs, tokens and typed values are excluded from learning reports.")
            }
        }
        item { Text("${CycloneRelease.label} · com.cyclone.mobile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) }
    }
}

@Composable
private fun V32AiAccessProfileCard(
    profile: CycloneAiAccessProfile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surface,
            ) {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Security,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(profile.displayName, fontWeight = FontWeight.Bold)
                    if (profile == CycloneAiAccessProfile.BALANCED) CycloneStatusPill("Recommended", true)
                }
                Text(profile.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun V32FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) } }
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun V32SmallMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .58f)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun V32EmptyCard(title: String, body: String) {
    CycloneSimpleCard { Text(title, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
