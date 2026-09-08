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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CycloneProfilesPage(context: Context, refreshTick: Int) {
    var setup by remember { mutableStateOf(false) }
    var profiles by remember { mutableStateOf(emptyList<Workspace>()) }
    var error by remember { mutableStateOf("") }
    val task by WorkspaceTasks.state.collectAsState()
    LaunchedEffect(refreshTick) {
        withContext(Dispatchers.IO) { runCatching { Layer2Workspaces.initialize(context); Layer2Workspaces.engine.snapshot() } }
            .onSuccess { profiles = it }.onFailure { error = "Profiles couldn't load. Open profile setup to repair." }
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
                    Text(when (profile.state) { WorkspaceState.running -> "Working"; WorkspaceState.paused -> "Paused"; WorkspaceState.gated -> "Needs you"; else -> "Ready" }, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { setup = true }) { Text("Open") }
            } }
        }
    }
    if (setup) ProfileSetupPage { setup = false }
}
