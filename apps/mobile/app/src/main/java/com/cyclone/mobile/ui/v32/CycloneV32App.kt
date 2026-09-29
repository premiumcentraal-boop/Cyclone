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
        var marketOpen by rememberSaveable { mutableStateOf(false) }
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

        // Plan 30: the setup cards open by themselves only when a setting is off that the owner has never seen.
        var setupOpen by rememberSaveable { mutableStateOf(false) }
        var setupOnly by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { if (com.cyclone.mobile.setup.SetupState.shouldOpen(context)) setupOpen = true }

        val phoneReady = v32AccessibilityEnabled(context)
        // Teal Matrix owns every destination, so the system bars always match the teal canvas.
        CycloneSignatureSystemBars(enabled = true)
        CycloneSignatureTheme(enabled = destination == V32Destination.AI && !settingsOpen) {
            Box(Modifier.fillMaxSize()) {
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
                        // The Marketplace opens over Routines; any tab closes it.
                        if (!settingsOpen) CycloneV32BottomBar(destination) {
                            destination = it
                            marketOpen = false
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        Column(Modifier.fillMaxSize()) {
                            ProfileRescueBar()
                            Box(Modifier.weight(1f).fillMaxSize()) {
                                if (settingsOpen) {
                                    CycloneSettingsPage426(context, refreshTick, { refreshTick++ }, settingsSection, onSetup = { card ->
                                        setupOnly = card?.id
                                        setupOpen = true
                                    }) { settingsSection = it }
                                } else if (marketOpen) {
                                    CycloneMarketplacePage(context, onBack = { marketOpen = false }) {
                                        marketOpen = false
                                        settingsSection = "Model & API"
                                        settingsOpen = true
                                    }
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
                                        V32Destination.AI -> V39AiChatPage(
                                            context,
                                            refreshTick,
                                            onSettingsSection = { section ->
                                                settingsSection = section
                                                settingsOpen = true
                                            },
                                            onRoutines = { destination = V32Destination.ROUTINES },
                                            onBrain = { destination = V32Destination.BRAIN },
                                        ) { settingsOpen = true }
                                        V32Destination.ROUTINES -> CycloneRoutinesPage(
                                            context,
                                            refreshTick,
                                            { destination = V32Destination.AI },
                                            onMarketplace = { marketOpen = true },
                                        ) { refreshTick++ }
                                        V32Destination.BRAIN -> CycloneV39BrainPage(context, refreshTick)
                                    }
                                }
                            }
                        }
                    }
                }
                if (setupOpen) {
                    SetupCardSheet(context, refreshTick, com.cyclone.mobile.setup.SetupCard.byId(setupOnly)) {
                        setupOpen = false
                        setupOnly = null
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

    // R4: Home on the AI page's material (R3): the Cyclone rain (drawn, no video) behind smoked glass.
    com.cyclone.mobile.ui.v32.ask.AskGlassPage(withScene = false) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = cyclonePageInsets(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { com.cyclone.mobile.ui.v32.ask.AskHomeHeader(onSettings = onSettings, onAi = onAi) }

                item {
                    com.cyclone.mobile.ui.v32.ask.AskGreeting(greeting, readinessBody, Modifier.padding(top = 8.dp)) {
                        if (!ready.ready) com.cyclone.mobile.ui.v32.ask.AskStatusChip(readinessLabel, positive = false, onClick = onSettings)
                    }
                }

                item {
                    HomeQuickActions(
                        onSeed = { seed = (seed.first + 1) to it },
                        onRoutines = onRoutines,
                    )
                }

                live?.let { current ->
                    item { com.cyclone.mobile.ui.v32.ask.AskSectionHeader("Current task") }
                    item {
                        com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight()
                        InAppTaskStack(current)
                    }
                }

                if (history.isNotEmpty()) {
                    item { com.cyclone.mobile.ui.v32.ask.AskSectionHeader("Recent activity", "Open chat", onAi) }
                    item {
                        com.cyclone.mobile.ui.v32.ask.AskGlassList(shineOffset = 0.45f) {
                            history.forEachIndexed { index, entry ->
                                if (index > 0) com.cyclone.mobile.ui.v32.ask.AskDivider(Modifier.padding(start = 62.dp))
                                HomeRecentRow(entry, onAi)
                            }
                        }
                    }
                }

                item { com.cyclone.mobile.ui.v32.ask.AskSectionHeader("Your routines", "See all", onRoutines) }
                item {
                    com.cyclone.mobile.ui.v32.ask.AskGlassList(shineOffset = 0.55f) {
                        if (routines.isEmpty()) {
                            com.cyclone.mobile.ui.v32.ask.AskListRow(
                                title = "Create your first routine",
                                subtitle = "Describe it to Cyclone or teach it by doing.",
                                onClick = onRoutines,
                                leading = {
                                    com.cyclone.mobile.ui.v32.ask.AskTile {
                                        Icon(Icons.Rounded.Bolt, null, Modifier.size(20.dp), tint = com.cyclone.mobile.ui.v32.ask.AskGlass.Ink)
                                    }
                                },
                                trailing = {
                                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = com.cyclone.mobile.ui.v32.ask.AskGlass.Faint)
                                },
                            )
                        } else {
                            routines.take(5).forEachIndexed { index, routine ->
                                if (index > 0) com.cyclone.mobile.ui.v32.ask.AskDivider(Modifier.padding(start = 62.dp))
                                HomeRoutineRow(routine, onRoutines)
                            }
                        }
                    }
                }
            }

            // One Ask Cyclone capsule, pinned above the tab bar: the AI page's smoked Ask bar.
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 6.dp)) {
                CycloneHomeComposer(seed = seed) { request ->
                    V39AiChatSessionRuntime.pendingRequest = request
                    onAi()
                }
            }
        }
    }
}

/** Home's quick actions (R4): four smoked-glass chips that fill the Ask bar; the owner still sends. */
@Composable
internal fun HomeQuickActions(onSeed: (String) -> Unit, onRoutines: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.cyclone.mobile.ui.v32.ask.AskChip(Icons.Rounded.CalendarMonth, "Plan my day", "Calendar and to-dos", Modifier.weight(1f), 0.30f,
                onClickLabel = "Write in the Ask bar") { onSeed("Plan my day") }
            com.cyclone.mobile.ui.v32.ask.AskChip(Icons.Rounded.Search, "Research a topic", "Sources and a summary", Modifier.weight(1f), 0.35f,
                onClickLabel = "Write in the Ask bar") { onSeed("Research ") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.cyclone.mobile.ui.v32.ask.AskChip(Icons.Rounded.Apps, "Open an app", "And do something in it", Modifier.weight(1f), 0.40f,
                onClickLabel = "Write in the Ask bar") { onSeed("Open ") }
            com.cyclone.mobile.ui.v32.ask.AskChip(Icons.Rounded.AutoAwesome, "Create a routine", "Automate something", Modifier.weight(1f), 0.45f,
                onClick = onRoutines)
        }
    }
}

@Composable
internal fun HomeRecentRow(item: RecentActivityItem, onOpen: () -> Unit) {
    com.cyclone.mobile.ui.v32.ask.AskListRow(
        title = item.title,
        subtitle = CycloneRecentActivity.statusLine(item),
        subtitleColor = if (item.state == CycloneTaskVisualState.DONE) com.cyclone.mobile.ui.v32.ask.AskGlass.Done
            else com.cyclone.mobile.ui.v32.ask.AskGlass.Muted,
        onClick = onOpen,
        leading = {
            com.cyclone.mobile.ui.v32.ask.AskTile { CycloneAppIcon(item.packageName.takeIf(String::isNotBlank), Modifier.size(26.dp)) }
        },
        trailing = {
            com.cyclone.mobile.ui.v32.ask.AskStatePip(
                when (item.state) {
                    CycloneTaskVisualState.DONE -> com.cyclone.mobile.ui.v32.ask.AskPipState.DONE
                    CycloneTaskVisualState.WORKING -> com.cyclone.mobile.ui.v32.ask.AskPipState.WORKING
                    else -> com.cyclone.mobile.ui.v32.ask.AskPipState.NEEDS_YOU
                },
                item.progress,
            )
        },
    )
}

@Composable
private fun HomeRoutineRow(routine: AutomationDefinition, onOpen: () -> Unit) {
    com.cyclone.mobile.ui.v32.ask.AskListRow(
        title = routine.name,
        subtitle = routine.v32TriggerSummary(),
        onClick = onOpen,
        leading = {
            com.cyclone.mobile.ui.v32.ask.AskTile { CycloneAppIcon(routine.appPackages.firstOrNull(), Modifier.size(26.dp)) }
        },
        trailing = {
            if (routine.enabled) {
                Box(Modifier.size(8.dp).background(com.cyclone.mobile.ui.v32.ask.AskGlass.Done, CircleShape))
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = com.cyclone.mobile.ui.v32.ask.AskGlass.Faint)
        },
    )
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
