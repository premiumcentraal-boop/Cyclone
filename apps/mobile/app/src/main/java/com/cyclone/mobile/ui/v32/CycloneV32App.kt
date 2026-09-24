package com.cyclone.mobile.ui.v32

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.ui.ProfileRescueBar
import java.time.LocalTime

@Composable
fun CycloneMobileV32App() {
    CycloneV32Theme {
        val context = LocalContext.current
        var destination by rememberSaveable { mutableStateOf(V32Destination.HOME) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var settingsSection by rememberSaveable { mutableStateOf("") }
        fun backFromSettings() {
            if (settingsSection.isNotEmpty()) settingsSection = "" else settingsOpen = false
        }
        androidx.activity.compose.BackHandler(settingsOpen) { backFromSettings() }
        var refreshTick by remember { mutableIntStateOf(0) }

        val task by com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collectAsState()
        val routinesRevision by AutomationRuntime.store.revision.collectAsState()
        LaunchedEffect(destination, settingsOpen, task?.taskId, task?.phase, routinesRevision) { refreshTick++ }
        LaunchedEffect(task) { CycloneRecentActivity.record(task) }

        DisposableEffect(context) {
            val lifecycle = (context as? LifecycleOwner)?.lifecycle
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshTick++
            }
            lifecycle?.addObserver(observer)
            onDispose { lifecycle?.removeObserver(observer) }
        }

        val phoneReady = v32AccessibilityEnabled(context)
        // Teal Matrix owns every destination, so the system bars always match the teal canvas.
        CycloneSignatureSystemBars(enabled = true)
        CycloneSignatureTheme(enabled = destination == V32Destination.AI && !settingsOpen) {
            Scaffold(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                topBar = {
                    if (settingsOpen) {
                        CycloneV32TopBar(
                            title = destination.label,
                            settingsOpen = true,
                            ready = phoneReady,
                            onSettings = {},
                            onBack = { backFromSettings() },
                        )
                    }
                },
                bottomBar = {
                    if (!settingsOpen) CycloneV32BottomBar(destination) { destination = it }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                    Column(Modifier.fillMaxSize()) {
                        ProfileRescueBar()
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            if (settingsOpen) {
                                CycloneSettingsPage426(context, refreshTick, { refreshTick++ }, settingsSection) { settingsSection = it }
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
    val recent by CycloneRecentActivity.items.collectAsState()
    val routines = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    var seed by remember { mutableStateOf(0 to "") }
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
    val readinessLabel = when {
        ready.ready -> "Ready"
        ready.needsRepair -> "Repair"
        else -> "Setup"
    }
    val readinessBody = when {
        ready.ready -> "What would you like to do today?"
        ready.needsRepair -> "Phone control needs a moment"
        else -> "A few steps to get set up"
    }
    val live = task?.takeIf { it.phase != TaskPhase.STOPPED }
    // The live task is already shown as the full card; recent rows list everything else.
    val history = recent.filter { it.taskId != live?.taskId }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = cyclonePageInsets(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CycloneMatrixAppBar(
                    onLeading = onSettings,
                    leadingDescription = "Settings",
                    onMark = onAi,
                    markDescription = "Open Ask Cyclone",
                )
            }

            item {
                CyclonePageHeader(
                    title = greeting,
                    subtitle = readinessBody,
                    modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
                    centered = true,
                    trailing = {
                        if (!ready.ready) {
                            Box(
                                Modifier
                                    .clickable(onClick = onSettings)
                                    .semantics { contentDescription = "Settings, $readinessLabel" }
                                    .heightIn(min = 44.dp)
                                    .padding(horizontal = 2.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CycloneStatusPill(readinessLabel, positive = ready.ready)
                            }
                        }
                    },
                )
            }

            item {
                HomeQuickActions(
                    onSeed = { seed = (seed.first + 1) to it },
                    onRoutines = onRoutines,
                )
            }

            live?.let { current ->
                item { CycloneMatrixSectionHeader("Current task") }
                item { CycloneAskTaskPanel(current) }
            }

            if (history.isNotEmpty()) {
                item { CycloneMatrixSectionHeader("Recent activity", "Open chat", onAi) }
                items(history, key = { "recent-${it.taskId}" }) { HomeRecentRow(it, onAi) }
            }

            item { CycloneMatrixSectionHeader("Your routines", "See all", onRoutines) }

            if (routines.isEmpty()) {
                item {
                    CycloneMatrixCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp, onClick = onRoutines, contentPadding = 14.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CycloneMatrixIconTile(size = 42.dp) {
                                Icon(Icons.Rounded.Bolt, null, Modifier.size(21.dp), tint = TealMatrix.Teal)
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Create your first routine", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Describe it to Cyclone or teach it by doing.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = TealMatrix.Muted.copy(alpha = .7f))
                        }
                    }
                }
            } else {
                items(routines.take(5), key = { it.id }) { routine ->
                    HomeRoutineRow(routine, onRoutines)
                }
            }
        }

        // One Ask Cyclone capsule, pinned above the tab bar exactly like the reference.
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 6.dp)) {
            CycloneHomeComposer(seed = seed) { request ->
                V39AiChatSessionRuntime.pendingRequest = request
                onAi()
            }
        }
    }
}

@Composable
internal fun HomeQuickActions(onSeed: (String) -> Unit, onRoutines: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CycloneMatrixQuickAction(Icons.Rounded.CalendarMonth, "Plan my day", { onSeed("Plan my day") }, Modifier.weight(1f))
            CycloneMatrixQuickAction(Icons.Rounded.Search, "Research a topic", { onSeed("Research ") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CycloneMatrixQuickAction(Icons.Rounded.Apps, "Open an app", { onSeed("Open ") }, Modifier.weight(1f))
            CycloneMatrixQuickAction(Icons.Rounded.AutoAwesome, "Create a routine", onRoutines, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun HomeRecentRow(item: RecentActivityItem, onOpen: () -> Unit) {
    val tone = when (item.state) {
        CycloneTaskVisualState.ACTION_NEEDED, CycloneTaskVisualState.FAILED -> MatrixTone.ATTENTION
        else -> MatrixTone.NEUTRAL
    }
    CycloneMatrixCard(Modifier.fillMaxWidth(), tone = tone, cornerRadius = 18.dp, onClick = onOpen, contentPadding = 12.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CycloneMatrixIconTile(size = 40.dp) {
                CycloneAppIcon(item.packageName.takeIf(String::isNotBlank), Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    CycloneRecentActivity.statusLine(item),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.state == CycloneTaskVisualState.DONE) TealMatrix.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            when (item.state) {
                CycloneTaskVisualState.DONE -> CycloneMatrixCheck()
                CycloneTaskVisualState.WORKING -> CycloneMatrixRing(item.progress)
                else -> CycloneMatrixAttention()
            }
        }
    }
}

@Composable
private fun HomeRoutineRow(routine: AutomationDefinition, onOpen: () -> Unit) {
    CycloneMatrixCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp, onClick = onOpen, contentPadding = 12.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CycloneMatrixIconTile(size = 40.dp) {
                CycloneAppIcon(routine.appPackages.firstOrNull(), Modifier.size(28.dp))
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
                Box(Modifier.size(8.dp).background(TealMatrix.Success, CircleShape))
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = TealMatrix.Muted.copy(alpha = .7f))
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
        contentPadding = cyclonePageInsets(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { CycloneBackRow("Routines", onBack) }
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Routine is ${if (enabled) "on" else "off"}", style = MaterialTheme.typography.titleSmall)
                        Text("Turn it off without deleting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CycloneLiquidToggle(enabled, { value ->
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
                CycloneLiquidTextAction(
                    label = "Run now",
                    enabled = enabled,
                    prominent = true,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        AutomationRuntime.router.runManual(automation.id)
                        Toast.makeText(context, "Routine started", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

internal fun v32AccessibilityEnabled(context: Context): Boolean = CyclonePermissionSetup.phoneControlReady(context)
internal fun v32NotificationListenerEnabled(context: Context): Boolean = CyclonePermissionSetup.notificationAccessEnabled(context)
internal fun v32ResultNotificationsEnabled(context: Context): Boolean = CyclonePermissionSetup.resultNotificationsEnabled(context)
