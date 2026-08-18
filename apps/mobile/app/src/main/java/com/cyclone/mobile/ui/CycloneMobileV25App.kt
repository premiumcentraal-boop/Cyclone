package com.cyclone.mobile.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnerProgress
import com.cyclone.mobile.applearner.LearnerSessionState
import com.cyclone.mobile.applearner.LearningMode
import com.cyclone.mobile.applearner.SkillCandidate
import com.cyclone.mobile.automation.AutomationDefinition
import kotlinx.coroutines.delay

private enum class LearnerView { HOME, NEW_SESSION, PROGRESS, DETAIL, MAP, ASK, AUTOMATION }
private data class InstalledAppChoice(val packageName: String, val label: String)

/** Cyclone V2.5 shell: V2.4/V2.3 functionality plus the App Learner Beta knowledge layer. */
@Composable
fun CycloneMobileV25App() {
    CycloneTheme {
        var learnerOpen by rememberSaveable { mutableStateOf(false) }
        Box(Modifier.fillMaxSize()) {
            CycloneMobileV23App()
            ExtendedFloatingActionButton(
                onClick = { learnerOpen = true },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 88.dp),
                icon = { Icon(Icons.Rounded.School, contentDescription = null) },
                text = { Text("Learn app") },
            )
            if (learnerOpen) AppLearnerSheet(onDismiss = { learnerOpen = false })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLearnerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AppLearnerRuntime.initialize(context)
    var tick by remember { mutableIntStateOf(0) }
    var view by rememberSaveable { mutableStateOf(LearnerView.HOME) }
    var selectedPackage by rememberSaveable { mutableStateOf("") }
    var selectedLabel by rememberSaveable { mutableStateOf("") }
    var instruction by rememberSaveable { mutableStateOf("Learn how this app works, focusing on useful navigation. Do not submit or change anything.") }
    var mode by rememberSaveable { mutableStateOf(LearningMode.GUIDED) }
    var useAiPlanner by rememberSaveable { mutableStateOf(true) }
    var askText by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var automationGoal by rememberSaveable { mutableStateOf("") }
    var proposal by remember { mutableStateOf<AutomationDefinition?>(null) }
    var whyScreen by remember { mutableStateOf<LearnedScreen?>(null) }
    val progress = AppLearnerRuntime.progress()
    val learnedApps = AppLearnerRuntime.learnedApps()
    val selectedGraph = selectedPackage.takeIf { it.isNotBlank() }?.let(AppLearnerRuntime::graph)

    LaunchedEffect(Unit) {
        while (true) {
            delay(600)
            tick++
        }
    }

    LaunchedEffect(progress.state, progress.packageName) {
        if (progress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
            selectedPackage = progress.packageName.orEmpty()
            selectedLabel = progress.appLabel.orEmpty()
            view = LearnerView.PROGRESS
        }
    }

    whyScreen?.let { screen ->
        AlertDialog(
            onDismissRequest = { whyScreen = null },
            title = { Text("Why does Cyclone think this?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(screen.purpose)
                    Text("Cyclone recognizes this screen from stable labels, structure and Android accessibility roles. Dynamic order numbers, dates and amounts are normalized instead of becoming new screens.")
                    screen.recognition.stableAnchors.take(10).forEach { Text("• ${it.take(100)}", style = MaterialTheme.typography.bodySmall) }
                    Text("State: ${screen.knowledgeState.pretty()} · ${(screen.confidence * 100).toInt()}% confidence")
                }
            },
            confirmButton = { Button(onClick = { whyScreen = null }) { Text("Got it") } },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.94f).fillMaxWidth()) {
            LearnerHeader(view = view, onBack = { view = LearnerView.HOME }, onDismiss = onDismiss)
            when (view) {
                LearnerView.HOME -> LearnerHome(
                    learnedApps = learnedApps,
                    progress = progress,
                    onNew = { view = LearnerView.NEW_SESSION },
                    onProgress = { view = LearnerView.PROGRESS },
                    onApp = { app -> selectedPackage = app.packageName; selectedLabel = app.label; view = LearnerView.DETAIL },
                )
                LearnerView.NEW_SESSION -> NewLearningSession(
                    context = context,
                    selectedPackage = selectedPackage,
                    selectedLabel = selectedLabel,
                    instruction = instruction,
                    mode = mode,
                    useAiPlanner = useAiPlanner,
                    onSelect = { selectedPackage = it.packageName; selectedLabel = it.label },
                    onInstruction = { instruction = it },
                    onMode = { mode = it },
                    onAi = { useAiPlanner = it },
                    onStart = {
                        if (selectedPackage.isNotBlank()) {
                            AppLearnerRuntime.start(context, selectedPackage, selectedLabel, instruction, mode, useAiPlanner)
                            view = LearnerView.PROGRESS
                        }
                    },
                )
                LearnerView.PROGRESS -> LearnerProgressView(
                    progress = progress,
                    instruction = instruction,
                    onInstruction = { instruction = it; AppLearnerRuntime.updateInstruction(it) },
                    onPause = { if (progress.state == LearnerSessionState.PAUSED) AppLearnerRuntime.resume() else AppLearnerRuntime.pause() },
                    onTakeOver = {
                        if (progress.state == LearnerSessionState.WAITING_FOR_HUMAN) AppLearnerRuntime.returnFromTakeover() else AppLearnerRuntime.takeOver()
                    },
                    onStop = { AppLearnerRuntime.stop(); view = LearnerView.HOME },
                    onComplete = {
                        selectedPackage = progress.packageName.orEmpty(); selectedLabel = progress.appLabel.orEmpty(); view = LearnerView.DETAIL
                    },
                )
                LearnerView.DETAIL -> selectedGraph?.let { graph ->
                    LearnedAppDetail(
                        graph = graph,
                        onMap = { view = LearnerView.MAP },
                        onAsk = { askText = ""; answer = ""; view = LearnerView.ASK },
                        onAutomation = { automationGoal = ""; proposal = null; view = LearnerView.AUTOMATION },
                        onTeachMore = { instruction = graph.app.instructionSummary.ifBlank { "Continue learning useful areas of this app." }; view = LearnerView.NEW_SESSION },
                        onWhy = { whyScreen = it },
                        onIncorrect = { screen -> AppLearnerRuntime.markScreenIncorrect(graph.app.packageName, screen.id); tick++ },
                    )
                } ?: MissingKnowledge { view = LearnerView.NEW_SESSION }
                LearnerView.MAP -> selectedGraph?.let { graph -> VisualAppMap(graph, onScreen = { whyScreen = it }) } ?: MissingKnowledge { view = LearnerView.HOME }
                LearnerView.ASK -> selectedGraph?.let { graph ->
                    AskAppView(
                        graph = graph,
                        question = askText,
                        answer = answer,
                        onQuestion = { askText = it },
                        onAsk = { answer = AppLearnerRuntime.ask(graph.app.packageName, askText) },
                    )
                } ?: MissingKnowledge { view = LearnerView.HOME }
                LearnerView.AUTOMATION -> selectedGraph?.let { graph ->
                    AutomationFromGraphView(
                        graph = graph,
                        goal = automationGoal,
                        proposal = proposal,
                        onGoal = { automationGoal = it },
                        onPlan = { proposal = AppLearnerRuntime.proposeAutomation(graph.app.packageName, automationGoal) },
                        onSave = { p -> AppLearnerRuntime.saveAutomation(p); proposal = p.copy(enabled = false) },
                        onRunLearned = { AppLearnerRuntime.executeLearnedRoute(graph.app.packageName, automationGoal) },
                    )
                } ?: MissingKnowledge { view = LearnerView.HOME }
            }
        }
    }
}

@Composable
private fun LearnerHeader(view: LearnerView, onBack: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Cyclone App Learner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("BETA · Teach Cyclone how your apps work", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view != LearnerView.HOME) OutlinedButton(onClick = onBack) { Text("Back") }
            OutlinedButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun LearnerHome(
    learnedApps: List<LearnedApp>,
    progress: LearnerProgress,
    onNew: () -> Unit,
    onProgress: () -> Unit,
    onApp: (LearnedApp) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.School, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Explore once. Reuse many times.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Cyclone builds a local semantic map of selected apps, remembers navigation, and turns known routes into fast deterministic Skills and Automations.")
                    Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Teach Cyclone an app") }
                    if (progress.state in setOf(LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
                        FilledTonalButton(onClick = onProgress, modifier = Modifier.fillMaxWidth()) { Text("Return to ${progress.appLabel ?: "learning"}") }
                    }
                }
            }
        }
        item { Text("Apps Cyclone knows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (learnedApps.isEmpty()) {
            item { Card(shape = RoundedCornerShape(20.dp)) { Text("No app maps yet. Start with Android Settings or another harmless app.", Modifier.padding(18.dp)) } }
        } else {
            items(learnedApps, key = { it.packageName }) { app ->
                Card(onClick = { onApp(app) }, shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontWeight = FontWeight.SemiBold)
                            Text("${(app.confidence * 100).toInt()}% knowledge · ${app.knowledgeState.pretty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun NewLearningSession(
    context: Context,
    selectedPackage: String,
    selectedLabel: String,
    instruction: String,
    mode: LearningMode,
    useAiPlanner: Boolean,
    onSelect: (InstalledAppChoice) -> Unit,
    onInstruction: (String) -> Unit,
    onMode: (LearningMode) -> Unit,
    onAi: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    val apps = remember { launcherApps(context) }
    var filter by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("1. Select an app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(value = filter, onValueChange = { filter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search installed apps") })
        }
        items(apps.filter { filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true) }.take(14), key = { it.packageName }) { app ->
            FilterChip(selected = selectedPackage == app.packageName, onClick = { onSelect(app) }, label = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
        item {
            if (selectedPackage.isNotBlank()) Text("Selected: $selectedLabel", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("2. Tell Cyclone what to learn", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = instruction,
                onValueChange = onInstruction,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
                placeholder = { Text("Example: Learn where Battery settings are. Don't change any settings.") },
            )
            Spacer(Modifier.height(8.dp))
            Text("3. Learning mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LearningMode.entries.forEach { value ->
                    FilterChip(selected = mode == value, onClick = { onMode(value) }, label = { Text(value.name.lowercase().replaceFirstChar(Char::uppercase)) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Fast AI guidance")
                    Text("Uses your selected OpenRouter model only as a semantic tie-breaker. Deterministic exploration still works without AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = useAiPlanner, onCheckedChange = onAi)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Safety boundary", fontWeight = FontWeight.SemiBold)
                    Text("Cyclone stays inside the selected app and maps but does not automatically press purchase, payment, send, submit, delete, account-security, authentication or permission actions.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = onStart, enabled = selectedPackage.isNotBlank() && instruction.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Start learning")
            }
        }
    }
}

@Composable
private fun LearnerProgressView(
    progress: LearnerProgress,
    instruction: String,
    onInstruction: (String) -> Unit,
    onPause: () -> Unit,
    onTakeOver: () -> Unit,
    onStop: () -> Unit,
    onComplete: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Learning ${progress.appLabel ?: "app"}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Current screen: ${progress.currentScreen ?: "Observing…"}")
                    Text(progress.currentActivity, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    progress.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Screens", progress.screens, Modifier.weight(1f))
                MetricCard("Actions", progress.actions, Modifier.weight(1f))
                MetricCard("Paths", progress.transitions, Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Forms", progress.forms, Modifier.weight(1f))
                MetricCard("Unknown", progress.unknownAreas, Modifier.weight(1f))
                MetricCard("Approval", progress.approvalBoundaries, Modifier.weight(1f))
            }
        }
        if (progress.state in setOf(LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) {
            item {
                OutlinedTextField(value = instruction, onValueChange = onInstruction, modifier = Modifier.fillMaxWidth(), label = { Text("Guide Cyclone while it learns") }, minLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Icon(if (progress.state == LearnerSessionState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text(if (progress.state == LearnerSessionState.PAUSED) "Resume" else "Pause")
                    }
                    FilledTonalButton(onClick = onTakeOver, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Security, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(if (progress.state == LearnerSessionState.WAITING_FOR_HUMAN) "Return" else "Take over")
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Stop, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Stop learning") }
            }
        } else {
            item { Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) { Text("View learned app") } }
        }
    }
}

@Composable
private fun LearnedAppDetail(
    graph: AppGraphSnapshot,
    onMap: () -> Unit,
    onAsk: () -> Unit,
    onAutomation: () -> Unit,
    onTeachMore: () -> Unit,
    onWhy: (LearnedScreen) -> Unit,
    onIncorrect: (LearnedScreen) -> Unit,
) {
    val candidates = AppLearnerRuntime.skillCandidates(graph.app.packageName)
    val unknown = graph.actions.count { it.knowledgeState == KnowledgeState.STALE || it.risk.name == "UNKNOWN" }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(graph.app.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Cyclone knowledge: ${(graph.app.confidence * 100).toInt()}%")
                    Text("${graph.screens.size} screens learned · ${graph.actions.size} useful/observed actions · ${candidates.size} Skill candidates · $unknown unknown/stale areas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAsk) { Text("Ask Cyclone") }
                        FilledTonalButton(onClick = onAutomation) { Text("Create Automation") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onMap) { Icon(Icons.Rounded.Map, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("View Map") }
                        OutlinedButton(onClick = onTeachMore) { Icon(Icons.Rounded.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Teach More") }
                    }
                }
            }
        }
        item { Text("Screens learned", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(graph.screens.take(20), key = { it.id }) { screen ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(screen.title, fontWeight = FontWeight.SemiBold)
                        Text("${(screen.confidence * 100).toInt()}%", color = stateColor(screen.knowledgeState))
                    }
                    Text(screen.purpose, style = MaterialTheme.typography.bodySmall)
                    Text(screen.knowledgeState.pretty(), style = MaterialTheme.typography.labelSmall, color = stateColor(screen.knowledgeState))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onWhy(screen) }) { Icon(Icons.Rounded.Info, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Why?") }
                        OutlinedButton(onClick = { onIncorrect(screen) }) { Text("Mark incorrect") }
                    }
                }
            }
        }
        if (candidates.isNotEmpty()) {
            item { Text("Potential Skills", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(candidates.take(8), key = { it.id }) { candidate -> SkillCandidateCard(graph, candidate) }
        }
    }
}

@Composable
private fun SkillCandidateCard(graph: AppGraphSnapshot, candidate: SkillCandidate) {
    var saved by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(candidate.name.replace('_', ' '), fontWeight = FontWeight.SemiBold)
            Text(candidate.description, style = MaterialTheme.typography.bodySmall)
            Text("Confidence ${(candidate.confidence * 100).toInt()}% · ${candidate.state.pretty()}", style = MaterialTheme.typography.labelSmall)
            Button(onClick = { saved = AppLearnerRuntime.saveSkill(graph.app.packageName, candidate) != null }, enabled = !saved) { Text(if (saved) "Saved" else "Save as Skill") }
        }
    }
}

@Composable
private fun VisualAppMap(graph: AppGraphSnapshot, onScreen: (LearnedScreen) -> Unit) {
    val root = graph.screens.firstOrNull { it.title.contains("home", true) || it.identity.contains("home", true) } ?: graph.screens.firstOrNull()
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("App Map", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("A consumer view of Cyclone's learned navigation. Technical selectors stay hidden unless you inspect why Cyclone recognizes a screen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        root?.let { start ->
            item { MapNode(start, level = 0, onScreen) }
            val visited = mutableSetOf(start.id)
            var frontier = listOf(start.id)
            repeat(4) { level ->
                val nextIds = graph.transitions.filter { it.fromScreenId in frontier }.map { it.toScreenId }.distinct().filter { visited.add(it) }
                if (nextIds.isNotEmpty()) {
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null) } }
                    items(nextIds.mapNotNull { id -> graph.screens.firstOrNull { it.id == id } }, key = { "map-${it.id}" }) { screen -> MapNode(screen, level + 1, onScreen) }
                }
                frontier = nextIds
            }
        }
        item {
            Text("Known transitions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            graph.transitions.take(40).forEach { transition ->
                val from = graph.screens.firstOrNull { it.id == transition.fromScreenId }?.title ?: "Unknown"
                val to = graph.screens.firstOrNull { it.id == transition.toScreenId }?.title ?: "Unknown"
                val action = graph.actions.firstOrNull { it.id == transition.actionId }?.label ?: "action"
                Text("$from  — $action →  $to", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MapNode(screen: LearnedScreen, level: Int, onScreen: (LearnedScreen) -> Unit) {
    Card(onClick = { onScreen(screen) }, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = (level.coerceAtMost(3) * 12).dp)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(screen.title, fontWeight = FontWeight.SemiBold)
                Text(screen.knowledgeState.pretty(), style = MaterialTheme.typography.labelSmall, color = stateColor(screen.knowledgeState))
            }
            Text("${(screen.confidence * 100).toInt()}%")
        }
    }
}

@Composable
private fun AskAppView(graph: AppGraphSnapshot, question: String, answer: String, onQuestion: (String) -> Unit, onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ask Cyclone about ${graph.app.label}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Cyclone answers from its local learned App Graph first, without reopening and rediscovering the app.")
        OutlinedTextField(value = question, onValueChange = onQuestion, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("How do I find invoices?") })
        Button(onClick = onAsk, enabled = question.isNotBlank()) { Text("Ask learned app") }
        if (answer.isNotBlank()) Card(shape = RoundedCornerShape(18.dp)) { Text(answer, Modifier.padding(16.dp)) }
    }
}

@Composable
private fun AutomationFromGraphView(
    graph: AppGraphSnapshot,
    goal: String,
    proposal: AutomationDefinition?,
    onGoal: (String) -> Unit,
    onPlan: () -> Unit,
    onSave: (AutomationDefinition) -> Unit,
    onRunLearned: () -> Result<String>,
) {
    var runMessage by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Create from learned path", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Cyclone retrieves a known route and compiles it into Automation Studio. The model is not invoked for each deterministic step.")
        OutlinedTextField(value = goal, onValueChange = onGoal, modifier = Modifier.fillMaxWidth(), label = { Text("What should Cyclone do?") }, placeholder = { Text("Open Battery settings") })
        Button(onClick = onPlan, enabled = goal.isNotBlank()) { Icon(Icons.Rounded.AccountTree, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Find learned route") }
        proposal?.let { p ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Cyclone can automate this using a learned path.", fontWeight = FontWeight.SemiBold)
                    p.steps.take(14).forEachIndexed { index, step -> Text("${index + 1}. ${step.name}", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSave(p) }) { Text("Save") }
                        OutlinedButton(onClick = { runMessage = onRunLearned().fold({ it }, { "Test failed: ${it.message}" }) }) { Text("Test route") }
                    }
                }
            }
        }
        if (runMessage.isNotBlank()) Text(runMessage, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MetricCard(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MissingKnowledge(onLearn: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Info, contentDescription = null)
        Spacer(Modifier.height(8.dp))
        Text("Cyclone needs more learning here.")
        Button(onClick = onLearn) { Text("Start learning") }
    }
}

private fun launcherApps(context: Context): List<InstalledAppChoice> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            InstalledAppChoice(pkg, info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { pkg })
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

private fun KnowledgeState.pretty(): String = name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
@Composable private fun stateColor(state: KnowledgeState) = when (state) {
    KnowledgeState.VERIFIED -> MaterialTheme.colorScheme.primary
    KnowledgeState.UNDERSTOOD -> MaterialTheme.colorScheme.secondary
    KnowledgeState.DISCOVERED -> MaterialTheme.colorScheme.tertiary
    KnowledgeState.STALE -> MaterialTheme.colorScheme.error
    KnowledgeState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}
