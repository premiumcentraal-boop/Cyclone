package com.cyclone.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.workspaces.ProfileApp
import com.cyclone.mobile.runtime.workspaces.ProfileCornerstones
import com.cyclone.mobile.runtime.workspaces.ProfileInventory
import com.cyclone.mobile.runtime.workspaces.ProfileInventoryStore
import com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore
import com.cyclone.mobile.runtime.workspaces.ProfileSetupRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plan 57 P2 (alpha.120): what each profile has (as last checked on a switch into it), and the apps every profile is
 * built on, with the owner's own Cornerstone marks.
 */
@Composable
internal fun ProfileCornerstonesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inventories by remember { mutableStateOf<List<Pair<String, ProfileInventory>>>(emptyList()) }
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var cloak by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf<List<ProfileApp>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    fun reload() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val labels = runCatching { ProfileRegistryStore.records(context).associate { it.id to it.label } }.getOrDefault(emptyMap())
                Triple(
                    runCatching { ProfileInventoryStore.all(context).map { (labels[it.profileId] ?: "A profile") to it } }.getOrDefault(emptyList()),
                    runCatching { ProfileCornerstones.marked(context) }.getOrDefault(emptySet()),
                    runCatching { ProfileCornerstones.cloakPackage(context) }.getOrNull(),
                )
            }.let { (i, m, c) -> inventories = i; marked = m; cloak = c }
        }
    }
    LaunchedEffect(Unit) { reload() }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What each profile has", style = MaterialTheme.typography.titleSmall)
            if (inventories.isEmpty()) {
                Text("Shown after the first switch into a profile.", style = MaterialTheme.typography.bodySmall)
            }
            inventories.forEach { (label, inventory) ->
                Text("$label has: ${inventory.line()}", style = MaterialTheme.typography.bodySmall)
                inventory.missing.forEach { app ->
                    Text("  ${app.label}: ${app.note ?: "missing"}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Text("Cornerstone apps", style = MaterialTheme.typography.titleSmall)
            Text("Every profile gets Cyclone, your root manager and Shizuku${if (cloak != null) ", and Cyclone Cloak" else ""}. " +
                "Apps you mark here are added to each profile on every switch, when they are installed in this one.",
                style = MaterialTheme.typography.bodySmall)
            if (marked.isNotEmpty()) Text("Yours: ${marked.sorted().joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    apps = withContext(Dispatchers.IO) { runCatching { ProfileSetupRuntime.apps(context) }.getOrDefault(emptyList()) }
                    picking = true
                }
            }) { Text("Choose cornerstone apps") }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Cornerstone apps") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Up to ${ProfileCornerstones.MAX_MARKED}. Root tools such as an LSPosed manager belong here.",
                        style = MaterialTheme.typography.bodySmall)
                    apps.forEach { app ->
                        val on = app.packageName in marked
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = on, enabled = on || marked.size < ProfileCornerstones.MAX_MARKED, onCheckedChange = { want ->
                                note = runCatching { ProfileCornerstones.setMarked(context, app.packageName, want); null }
                                    .getOrElse { it.message }
                                marked = runCatching { ProfileCornerstones.marked(context) }.getOrDefault(marked)
                            })
                            Text(app.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false; reload() }) { Text("Done") } },
        )
    }
}
