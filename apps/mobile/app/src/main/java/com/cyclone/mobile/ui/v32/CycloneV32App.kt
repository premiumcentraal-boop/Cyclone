package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlusOne
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.TaskResultNotifierV292
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import kotlinx.coroutines.delay
import java.time.LocalTime

@Composable
fun CycloneMobileV32App() {
    CycloneV32Theme {
        val context = LocalContext.current
        var destination by rememberSaveable { mutableStateOf(V32Destination.HOME) }
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
            while (true) {
                delay(800)
                refreshTick++
            }
        }

        val phoneReady = v32AccessibilityEnabled(context)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CycloneV32TopBar(
                    title = destination.label,
                    settingsOpen = settingsOpen,
                    ready = phoneReady,
                    onSettings = { settingsOpen = true },
                    onBack = { settingsOpen = false },
                )
            },
            bottomBar = {
                if (!settingsOpen) CycloneV32BottomBar(destination) { destination = it }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (settingsOpen) {
                    V32SettingsPage(context, refreshTick) { refreshTick++ }
                } else {
                    when (destination) {
                        V32Destination.HOME -> V32HomePage(
                            context = context,
                            refreshTick = refreshTick,
                            onAi = { destination = V32Destination.AI },
                            onTeach = { destination = V32Destination.TEACH },
                            onRoutines = { destination = V32Destination.ROUTINES },
                            onSettings = { settingsOpen = true },
                        )
                        V32Destination.TEACH -> V32TeachPage(context, refreshTick)
                        V32Destination.AI -> V39AiChatPage(context, refreshTick) { settingsOpen = true }
                        V32Destination.ROUTINES -> V32RoutinesPage(context, refreshTick) { refreshTick++ }
                        V32Destination.BRAIN -> CycloneV39BrainPage(context, refreshTick)
                    }
                }
            }
        }
    }
}

@Composable
private fun V32HomePage(
    context: Context,
    refreshTick: Int,
    onAi: () -> Unit,
    onTeach: () -> Unit,
    onRoutines: () -> Unit,
    onSettings: () -> Unit,
) {
    val phoneReady = v32AccessibilityEnabled(context)
    val notificationReady = v32NotificationListenerEnabled(context)
    val resultReady = v32ResultNotificationsEnabled(context)
    val batteryReady = CyclonePermissionSetup.batteryUnrestricted(context)
    val readiness = listOf(phoneReady, notificationReady, resultReady, batteryReady).count { it }
    val automations = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { CyclonePageIntro("Your phone, simplified", greeting, "Automate the repetitive parts and keep the decisions that matter.") }
        item {
            CycloneHeroCard(
                title = "What should Cyclone do?",
                body = "Describe a phone task, or show Cyclone once and reuse it later.",
                icon = Icons.Rounded.AutoAwesome,
                tone = CyclonePastel.LILAC,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onAi, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.size(6.dp)); Text("Ask Cyclone") }
                    FilledTonalButton(onClick = onTeach, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.School, null); Spacer(Modifier.size(6.dp)); Text("Teach") }
                }
            }
        }
        item {
            CycloneSimpleCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (readiness == 4) "Your phone is ready" else "Finish phone setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (readiness == 4) "Control, triggers, results and reliable background work are available." else "$readiness of 4 phone essentials ready", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CycloneStatusPill(if (readiness == 4) "Ready" else "$readiness/4", readiness == 4)
                }
                if (readiness < 4) OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Finish setup") }
            }
        }
        item { CycloneSectionTitle("Your routines") { TextButton(onClick = onRoutines) { Text("See all") } } }
        if (automations.isEmpty()) {
            item {
                CycloneSimpleCard {
                    Text("Nothing repetitive yet", fontWeight = FontWeight.Bold)
                    Text("Create a routine here, teach one by demonstration, or ask AI to build a reviewable draft.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onRoutines, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Bolt, null); Spacer(Modifier.size(6.dp)); Text("Create a routine") }
                }
            }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 18.dp)) {
                    items(automations.take(6), key = { it.id }) { automation ->
                        V32RoutineMiniCard(automation, automations.indexOf(automation), onRoutines)
                    }
                }
            }
        }
        item {
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                    Column {
                        Text("Proof, not guesswork", fontWeight = FontWeight.Bold)
                        Text("Cyclone checks the phone after every changing action and saves reusable evidence only after success.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
    }
}

@Composable
private fun V32RoutineMiniCard(automation: AutomationDefinition, index: Int, onOpen: () -> Unit) {
    val tone = listOf(CyclonePastel.MINT, CyclonePastel.SKY, CyclonePastel.LEMON, CyclonePastel.PEACH, CyclonePastel.LILAC)[index % 5]
    val colors = cyclonePastel(tone)
    Card(
        onClick = onOpen,
        modifier = Modifier.size(width = 190.dp, height = 146.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container, contentColor = colors.content),
    ) {
        Column(Modifier.fillMaxSize().padding(17.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Surface(shape = CircleShape, color = colors.content.copy(alpha = 0.12f)) {
                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(19.dp)) }
            }
            Column {
                Text(automation.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${automation.steps.size} ${if (automation.steps.size == 1) "action" else "actions"}", style = MaterialTheme.typography.labelSmall, color = colors.content.copy(alpha = .72f))
            }
        }
    }
}

@Composable
private fun V32RoutinesPage(context: Context, refreshTick: Int, refresh: () -> Unit) {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var builderOpen by rememberSaveable { mutableStateOf(false) }
    val all = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    val selected = selectedId?.let { id -> all.firstOrNull { it.id == id } }

    when {
        builderOpen -> V32RoutineBuilder(
            onBack = { builderOpen = false },
            onSave = { draft ->
                val automation = draft.toAutomationForDevice(notificationAccess = v32NotificationListenerEnabled(context))
                AutomationRuntime.store.saveAutomation(automation)
                if (automation.trigger.type == TriggerType.SCHEDULE) AutomationRuntime.registerSchedule(context, automation)
                refresh()
                builderOpen = false
                Toast.makeText(context, if (automation.enabled) "${automation.name} saved" else "${automation.name} saved off — enable notification access first", Toast.LENGTH_LONG).show()
            },
        )
        selected != null -> V32RoutineDetail(context, selected, { selectedId = null }, refresh)
        else -> {
            val visible = if (segment == 0) all.filter { it.trigger.type != TriggerType.MANUAL } else all.filter { it.trigger.type == TriggerType.MANUAL }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(contentPadding = PaddingValues(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { CyclonePageIntro("When → Then → Check", "Routines", "Build phone automations that remain easy to read, review and stop.") }
                    item { CycloneSegmentedControl(listOf("Automations", "One tap"), segment, { segment = it }) }
                    if (visible.isEmpty()) {
                        item {
                            CycloneHeroCard(
                                title = if (segment == 0) "Automate a moment" else "Make a one-tap shortcut",
                                body = if (segment == 0) "Start from a notification, time, app or Cyclone connection." else "Put a useful phone sequence behind one clear button.",
                                icon = Icons.Rounded.Bolt,
                                tone = CyclonePastel.SKY,
                            ) {
                                Button(onClick = { builderOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Create routine") }
                            }
                        }
                    } else {
                        items(visible, key = { it.id }) { automation ->
                            val index = all.indexOfFirst { it.id == automation.id }.coerceAtLeast(0)
                            CycloneRoutineCard(
                                automation = automation,
                                tone = listOf(CyclonePastel.MINT, CyclonePastel.SKY, CyclonePastel.LEMON, CyclonePastel.PEACH, CyclonePastel.LILAC)[index % 5],
                                onOpen = { selectedId = automation.id },
                                onEnabledChange = { enabled ->
                                    val updated = automation.copy(enabled = enabled)
                                    AutomationRuntime.store.saveAutomation(updated)
                                    if (updated.trigger.type == TriggerType.SCHEDULE) {
                                        if (enabled) AutomationRuntime.registerSchedule(context, updated) else AutomationRuntime.cancelSchedule(context, updated.id)
                                    }
                                    refresh()
                                },
                            )
                        }
                    }
                }
                FloatingActionButton(onClick = { builderOpen = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(22.dp), shape = CircleShape) {
                    Icon(Icons.Rounded.PlusOne, "Create routine")
                }
            }
        }
    }
}

@Composable
private fun V32RoutineDetail(context: Context, automation: AutomationDefinition, onBack: () -> Unit, refresh: () -> Unit) {
    var enabled by remember(automation.id, automation.enabled) { mutableStateOf(automation.enabled) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OutlinedButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.size(6.dp)); Text("All routines") } }
        item { CyclonePageIntro("Routine", automation.name, automation.description.ifBlank { "A readable, reviewable phone routine." }) }
        item {
            CycloneHeroCard(automation.v32TriggerSummary(), "This is when Cyclone starts.", Icons.Rounded.Bolt, tone = CyclonePastel.SKY)
        }
        item { CycloneSectionTitle("Then") }
        items(automation.steps.withIndex().toList(), key = { it.value.id }) { (index, step) ->
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Text("${index + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(step.v32ReadableName(), fontWeight = FontWeight.Bold)
                        Text(step.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (step.confirmationRequired) CycloneStatusPill("Asks you", false)
                }
            }
        }
        item {
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Routine is ${if (enabled) "on" else "off"}", fontWeight = FontWeight.Bold)
                        Text("Turn it off at any time without deleting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(enabled, { value ->
                        enabled = value
                        val updated = automation.copy(enabled = value)
                        AutomationRuntime.store.saveAutomation(updated)
                        if (updated.trigger.type == TriggerType.SCHEDULE) {
                            if (value) AutomationRuntime.registerSchedule(context, updated) else AutomationRuntime.cancelSchedule(context, updated.id)
                        }
                        refresh()
                    })
                }
                Button(
                    onClick = { AutomationRuntime.router.runManual(automation.id); Toast.makeText(context, "Routine started", Toast.LENGTH_SHORT).show() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Run now") }
            }
        }
    }
}

internal fun v32AccessibilityEnabled(context: Context): Boolean {
    return CyclonePermissionSetup.primaryControlEnabled(context)
}

internal fun v32NotificationListenerEnabled(context: Context): Boolean = CyclonePermissionSetup.notificationAccessEnabled(context)

internal fun v32ResultNotificationsEnabled(context: Context): Boolean = CyclonePermissionSetup.resultNotificationsEnabled(context)
