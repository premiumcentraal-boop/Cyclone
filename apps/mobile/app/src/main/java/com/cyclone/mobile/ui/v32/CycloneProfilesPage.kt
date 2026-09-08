package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.workspaces.*
import com.cyclone.mobile.ui.ProfileSetupPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CycloneProfilesPage(context: Context, refreshTick: Int) {
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var waiting by remember { mutableStateOf(emptyList<String>()) }
    var busy by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(selectedId != null) { selectedId = null }
    var setup by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(emptyList<Workspace>()) }
    var error by remember { mutableStateOf("") }
    val task by WorkspaceTasks.state.collectAsState()
    LaunchedEffect(refreshTick) {
        withContext(Dispatchers.IO) { runCatching { Layer2Workspaces.initialize(context); Layer2Workspaces.engine.snapshot() } }
            .onSuccess { profiles = it; waiting = Layer2Workspaces.engine.queue() }.onFailure { error = "Profiles couldn't load. Open profile setup to repair." }
    }
    fun openForHuman(profile: Workspace) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val params = org.json.JSONObject().put("sessionId", "default-foreground").put("displayId", 0).put("id", profile.id)
                val switched = com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(java.util.UUID.randomUUID().toString(), "workspace.switch", params))
                // Human opening does not retain AI injection authority.
                if (switched.ok) com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(java.util.UUID.randomUUID().toString(), "workspace.pause", params))
                switched
            }
            busy = false
            if (!result.ok) error = "Couldn't open this profile safely. Check profile setup."
        }
    }
    val selected = profiles.firstOrNull { it.id == selectedId }
    if (selected != null) {
        val exactTask = task?.takeIf { it.workspaceId == selected.id }
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { TextButton(onClick = { selectedId = null }) { Text("‹ Profiles") } }
            item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CycloneAppIcon(selected.appPackage)
                Column { Text(selected.label, style = MaterialTheme.typography.headlineSmall); Text(appLabel(context, selected.appPackage)) }
            } }
            if (exactTask != null) {
                item { CycloneTaskProgress(exactTask) }
                item { Text("Recent activity", style = MaterialTheme.typography.titleMedium) }
                items(exactTask.steps.takeLast(8)) { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } else item { Text(if (selected.id in waiting) "Waiting for Cyclone" else "No active task in this profile.") }
            item { Button(enabled = !busy, onClick = { openForHuman(selected) }) { Text("Open profile") } }
            if (error.isNotBlank()) item { Text(error) }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Profiles", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { setup = true }) { Text("+ Add") }
        } }
        task?.takeIf { UiTask(it).active }?.let { active ->
            item { CycloneSectionTitle("Active now") }
            item { CycloneTaskProgress(active) }
        }
        item { CycloneSectionTitle("All profiles") }
        if (error.isNotEmpty()) item { Text(error) }
        if (profiles.isEmpty()) item { Text("Add a profile to keep another app account separate.") }
        items(profiles.sortedBy { if (it.state == WorkspaceState.running) 0 else 1 }, key = { it.id }) { profile ->
            CycloneSurface(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CycloneAppIcon(profile.appPackage)
                Column(Modifier.weight(1f)) {
                    Text(profile.label, style = MaterialTheme.typography.titleMedium)
                    Text(appLabel(context, profile.appPackage), style = MaterialTheme.typography.bodySmall)
                    Text(if (profile.id in waiting && profile.state != WorkspaceState.running) "Waiting" else when (profile.state) { WorkspaceState.running -> "Working"; WorkspaceState.paused -> "Paused"; WorkspaceState.gated -> "Needs you"; else -> "Ready" }, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { selectedId = profile.id }) { Text("Open") }
            } }
        }
    }
    if (setup) ProfileSetupPage { setup = false }
}
