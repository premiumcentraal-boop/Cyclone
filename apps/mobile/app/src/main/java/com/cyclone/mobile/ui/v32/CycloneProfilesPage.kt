package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.workspaces.Layer2Workspaces
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.runtime.workspaces.WorkspaceState
import com.cyclone.mobile.ui.ProfileSetupPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CycloneProfilesPage(context: Context, refreshTick: Int) {
    val scope = rememberCoroutineScope()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    var activeOnly by rememberSaveable { mutableStateOf(false) }
    var setup by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(emptyList<Workspace>()) }
    var error by remember { mutableStateOf("") }

    BackHandler(selectedId != null) { selectedId = null }

    val task by WorkspaceTasks.state.collectAsState()
    val profileSetup by ProfileSetupRuntime.state.collectAsState()
    val revision by Layer2Workspaces.engine.revision.collectAsState()

    LaunchedEffect(refreshTick, revision) {
        withContext(Dispatchers.IO) {
            runCatching {
                Layer2Workspaces.initialize(context)
                Layer2Workspaces.engine.snapshot()
            }
        }.onSuccess {
            profiles = it
            waiting = Layer2Workspaces.engine.queue()
        }.onFailure {
            error = "Profiles couldn't load. Open profile setup to repair."
        }
    }

    fun openForHuman(profile: Workspace) {
        busy = true
        error = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val params = org.json.JSONObject()
                        .put("sessionId", "default-foreground")
                        .put("displayId", 0)
                        .put("id", profile.id)
                    val switched = com.cyclone.mobile.PhoneToolExecutor.execute(
                        context,
                        com.cyclone.mobile.PhoneToolRequest(
                            java.util.UUID.randomUUID().toString(),
                            "workspace.switch",
                            params,
                        ),
                    )
                    if (switched.ok) {
                        com.cyclone.mobile.PhoneToolExecutor.execute(
                            context,
                            com.cyclone.mobile.PhoneToolRequest(
                                java.util.UUID.randomUUID().toString(),
                                "workspace.pause",
                                params,
                            ),
                        )
                    } else switched
                }
                if (!result.ok) {
                    error = "Couldn't hand control to you safely. Review the current task or check profile setup."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = "Profile opening was interrupted. You can try again."
            } finally {
                busy = false
            }
        }
    }

    val inventory = remember(refreshTick, revision, profileSetup) { WorkspaceTasks.queueDestinations(context) }
    val activeProfiles = profiles.filter { it.state != WorkspaceState.idle || it.id in waiting }
    val visibleProfiles = (if (activeOnly) activeProfiles else profiles).sortedWith(
        compareBy<Workspace> {
            when {
                it.state == WorkspaceState.gated -> 0
                it.state == WorkspaceState.running -> 1
                it.id in waiting -> 2
                it.state == WorkspaceState.paused -> 3
                else -> 4
            }
        }.thenBy { it.label.lowercase() },
    )
    val selected = profiles.firstOrNull { it.id == selectedId }

    if (selected != null) {
        val exactTask = task?.takeIf { UiTask(it).belongsToProfile(selected.id) }
        val stateLabel = profileStateLabel(selected, waiting, exactTask)
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { TextButton(onClick = { selectedId = null }) { Text("‹ Profiles") } }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        CycloneAppIcon(selected.appPackage, Modifier.padding(8.dp).size(36.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(selected.label, style = MaterialTheme.typography.headlineSmall)
                        Text(appLabel(context, selected.appPackage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ProfileStatePill(stateLabel, stateLabel !in setOf("Needs you", "Couldn't load"))
                }
            }

            if (exactTask != null) {
                item { CycloneTaskProgress(exactTask) }
                if (exactTask.steps.isNotEmpty()) {
                    item { CycloneSectionTitle("Recent activity") }
                    items(exactTask.steps.takeLast(6)) { step ->
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                item {
                    CycloneSimpleCard(Modifier.fillMaxWidth()) {
                        Text(
                            if (selected.id in waiting) "Waiting for Cyclone" else "No active task",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (selected.id in waiting) "Cyclone will rotate into this profile when it is ready." else "This profile is ready for a new task.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Button(
                    enabled = !busy,
                    onClick = { openForHuman(selected) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "Opening…" else "Take control")
                }
            }
            if (error.isNotBlank()) {
                item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        return
    }

    val incompleteInventory = inventory.filter { destination ->
        destination.androidUserId != Layer2Workspaces.currentAndroidUserId() &&
            profiles.none { it.androidUserId == destination.androidUserId }
    }

    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Profiles", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Accounts and app spaces Cyclone can work in",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledIconButton(onClick = { setup = true }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Add, "Add profile", modifier = Modifier.size(20.dp))
                }
            }
        }

        profileSetup.issue?.takeIf { !profileSetup.busy && !profileSetup.ready }?.let { issue ->
            item {
                CycloneSurface(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(issue.headline, style = MaterialTheme.typography.titleMedium)
                        Text(issue.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (issue.retryUseful) {
                            Button(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(issue.action)
                            }
                        } else {
                            Text(issue.action, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        task?.takeIf { UiTask(it).active }?.let { active ->
            item { CycloneSectionTitle("Active now") }
            item { CycloneTaskProgress(active) }
        }

        if (activeProfiles.size > 1) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Cyclone rotates between ${activeProfiles.size} active profiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            CycloneSegmentedControl(
                listOf("All (${profiles.size})", "Active (${activeProfiles.size})"),
                if (activeOnly) 1 else 0,
                { activeOnly = it == 1 },
            )
        }

        if (activeOnly && activeProfiles.isEmpty()) {
            item {
                CycloneSimpleCard(Modifier.fillMaxWidth()) {
                    Text("Nothing active right now", style = MaterialTheme.typography.titleSmall)
                    Text("Profiles with running or queued work will pin here automatically.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (error.isNotEmpty()) {
            item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        items(visibleProfiles, key = { it.id }) { profile ->
            val exactTask = task?.takeIf { UiTask(it).belongsToProfile(profile.id) }
            ProfileCard(
                context = context,
                profile = profile,
                waiting = profile.id in waiting,
                task = exactTask,
                onOpen = { selectedId = profile.id },
            )
        }

        if (!activeOnly && incompleteInventory.isNotEmpty()) {
            item { CycloneSectionTitle("Finish setup") }
            items(incompleteInventory, key = { "profile-${it.androidUserId}" }) { profile ->
                Card(
                    onClick = { setup = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Row(
                        Modifier.padding(15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Person, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(profile.label, style = MaterialTheme.typography.titleSmall)
                            Text("Choose apps to finish this profile", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (!activeOnly && profiles.isEmpty() && incompleteInventory.isEmpty() && profileSetup.issue == null) {
            item {
                CycloneSimpleCard(Modifier.fillMaxWidth()) {
                    Text("Add your first profile", style = MaterialTheme.typography.titleMedium)
                    Text("Keep another account or app setup ready for Cyclone without mixing its app data with your main profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { setup = true }, modifier = Modifier.fillMaxWidth()) { Text("Add profile") }
                }
            }
        }
    }

    if (setup) ProfileSetupPage { setup = false }
}

@Composable
private fun ProfileCard(
    context: Context,
    profile: Workspace,
    waiting: Boolean,
    task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?,
    onOpen: () -> Unit,
) {
    val stateLabel = profileStateLabel(profile, if (waiting) listOf(profile.id) else emptyList(), task)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .68f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                CycloneAppIcon(profile.appPackage, Modifier.padding(7.dp).size(31.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profile.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    task?.title?.takeIf { it.isNotBlank() } ?: appLabel(context, profile.appPackage),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProfileStatePill(stateLabel, stateLabel !in setOf("Needs you", "Couldn't load"))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProfileStatePill(label: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when {
            label == "Needs you" -> MaterialTheme.colorScheme.tertiaryContainer
            label == "Working" -> MaterialTheme.colorScheme.primaryContainer
            positive -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.errorContainer
        },
        contentColor = when {
            label == "Needs you" -> MaterialTheme.colorScheme.onTertiaryContainer
            label == "Working" -> MaterialTheme.colorScheme.primary
            positive -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> MaterialTheme.colorScheme.onErrorContainer
        },
    ) {
        Text(label, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun profileStateLabel(
    profile: Workspace,
    waiting: List<String>,
    task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?,
): String = when {
    task != null && UiTask(task).active -> UiTask(task).consumerStatus
    profile.state == WorkspaceState.gated -> "Needs you"
    profile.state == WorkspaceState.running -> "Working"
    profile.id in waiting -> "Waiting"
    profile.state == WorkspaceState.paused -> "Paused"
    else -> "Ready"
}
