package com.cyclone.mobile.ui.v32

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
import com.cyclone.mobile.runtime.background.TaskPhase
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

        val task by com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collectAsState()
        val routinesRevision by AutomationRuntime.store.revision.collectAsState()
        LaunchedEffect(destination, settingsOpen, task?.taskId, task?.phase, routinesRevision) { refreshTick++ }

        DisposableEffect(context) {
            val lifecycle = (context as? LifecycleOwner)?.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshTick++
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }

        val phoneReady = v32AccessibilityEnabled(context)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (settingsOpen) {
                    CycloneV32TopBar(
                        title = destination.label,
                        settingsOpen = true,
                        ready = phoneReady,
                        onSettings = {},
                        onBack = { settingsOpen = false },
                    )
                }
            },
            bottomBar = {
                if (!settingsOpen) CycloneV32BottomBar(destination) { destination = it }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (settingsOpen) {
                    CycloneSettingsPage426(context, refreshTick) { refreshTick++ }
                } else {
                    when (destination) {
                        V32Destination.HOME -> V32HomePage(
                            context = context,
                            refreshTick = refreshTick,
                            onAi = { destination = V32Destination.AI },
                            onRoutines = { destination = V32Destination.ROUTINES },
                            onSettings = { settingsOpen = true },
                        )
                        V32Destination.PROFILES -> CycloneProfilesPage(context, refreshTick) { destination = V32Destination.AI }
                        V32Destination.AI -> V39AiChatPage(context, refreshTick) { settingsOpen = true }
                        V32Destination.ROUTINES -> CycloneRoutinesPage(
                            context,
                            refreshTick,
                            { destination = V32Destination.AI },
                        ) { refreshTick++ }
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
    onRoutines: () -> Unit,
    onSettings: () -> Unit,
) {
    val ready = CyclonePermissionSetup.phoneControlSnapshot(context)
    val task by com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collectAsState()
    val routines = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clickable(onClick = onSettings),
                    shape = RoundedCornerShape(999.dp),
                    color = when {
                        ready.ready -> MaterialTheme.colorScheme.secondaryContainer
                        ready.needsRepair -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.tertiaryContainer
                    },
                    contentColor = when {
                        ready.ready -> MaterialTheme.colorScheme.onSecondaryContainer
                        ready.needsRepair -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = when {
                                ready.ready -> MaterialTheme.colorScheme.secondary
                                ready.needsRepair -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.tertiary
                            },
                            modifier = Modifier.size(6.dp),
                        ) {}
                        Text(
                            when {
                                ready.ready -> "Ready"
                                ready.needsRepair -> "Repair"
                                else -> "Setup"
                            },
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        item {
            CycloneHomeComposer { request ->
                V39AiChatSessionRuntime.pendingRequest = request
                onAi()
            }
        }

        task?.takeIf { it.phase != TaskPhase.STOPPED }?.let { current ->
            item { CycloneSectionTitle("Current task") }
            item { CycloneAskTaskPanel(current) }
        }

        item {
            CycloneSectionTitle("Your routines") {
                TextButton(onClick = onRoutines) { Text("See all") }
            }
        }

        if (routines.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onRoutines).padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Bolt, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Create your first routine", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Describe it to Cyclone or teach it by doing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                    }
                }
            }
        } else {
            items(routines.take(5), key = { it.id }) { routine ->
                HomeRoutineRow(routine, onRoutines)
            }
        }
    }
}

@Composable
private fun HomeRoutineRow(routine: AutomationDefinition, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                CycloneAppIcon(routine.appPackages.firstOrNull(), Modifier.padding(6.dp).size(31.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(routine.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    routine.v32TriggerSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (routine.enabled) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.size(8.dp))
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
        }
    }
}

@Composable
internal fun V32RoutineDetail(
    context: Context,
    automation: AutomationDefinition,
    onBack: () -> Unit,
    refresh: () -> Unit,
) {
    var enabled by remember(automation.id, automation.enabled) { mutableStateOf(automation.enabled) }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                Spacer(Modifier.size(6.dp))
                Text("All routines")
            }
        }
        item { CyclonePageIntro("Routine", automation.name, "${automation.steps.size} steps · ${automation.v32TriggerSummary()}") }
        item { CycloneRoutineAssociations(automation, refresh) }
        item { CycloneSectionTitle("Steps") }
        items(automation.steps.withIndex().toList(), key = { it.value.id }) { (index, step) ->
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(step.v32ReadableName(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            step.type.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (step.confirmationRequired) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text("Asks you", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Routine is ${if (enabled) "on" else "off"}", style = MaterialTheme.typography.titleSmall)
                        Text("Turn it off without deleting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(enabled, { value ->
                        enabled = value
                        val updated = automation.copy(enabled = value)
                        AutomationRuntime.store.saveAutomation(updated)
                        if (updated.trigger.type == TriggerType.SCHEDULE) {
                            if (value) AutomationRuntime.registerSchedule(context, updated)
                            else AutomationRuntime.cancelSchedule(context, updated.id)
                        }
                        refresh()
                    })
                }
                Button(
                    enabled = enabled,
                    onClick = {
                        AutomationRuntime.router.runManual(automation.id)
                        Toast.makeText(context, "Routine started", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Run now")
                }
            }
        }
    }
}

internal fun v32AccessibilityEnabled(context: Context): Boolean = CyclonePermissionSetup.phoneControlReady(context)
internal fun v32NotificationListenerEnabled(context: Context): Boolean = CyclonePermissionSetup.notificationAccessEnabled(context)
internal fun v32ResultNotificationsEnabled(context: Context): Boolean = CyclonePermissionSetup.resultNotificationsEnabled(context)
