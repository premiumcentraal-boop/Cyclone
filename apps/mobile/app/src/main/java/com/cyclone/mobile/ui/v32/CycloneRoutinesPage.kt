package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.*

@Composable
fun CycloneRoutinesPage(context: Context, refreshTick: Int, onAi: () -> Unit, refresh: () -> Unit) {
    val all = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    val task by com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collectAsState()
    val runs = remember(refreshTick) { AutomationRuntime.store.listRuns() }
    var query by rememberSaveable { mutableStateOf("") }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var grouping by rememberSaveable { mutableIntStateOf(0) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("") }
    BackHandler(selected != null || group != null || mode.isNotEmpty()) { selected = null; group = null; mode = "" }
    when {
        mode == "teach" -> CycloneFollowMePage(context, refreshTick) { mode = "" }
        mode == "manual" -> Column {
            TextButton(onClick = { mode = "advanced" }) { Text("‹ Advanced") }
            V32TeachPage(context, refreshTick)
        }
        mode == "advanced" -> Column(Modifier.padding(18.dp)) {
            TextButton(onClick = { mode = "" }) { Text("‹ Routines") }
            Text("Advanced", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { mode = "builder" }) { Text("Routine builder") }
            TextButton(onClick = { mode = "manual" }) { Text("Manual teacher & teaching history") }
        }
        mode == "builder" -> V32RoutineBuilder(onBack = { mode = "" }, onSave = { draft ->
            val routine = draft.toAutomationForDevice(notificationAccess = v32NotificationListenerEnabled(context))
            AutomationRuntime.store.saveAutomation(routine)
            if (routine.trigger.type == TriggerType.SCHEDULE) AutomationRuntime.registerSchedule(context, routine)
            refresh(); mode = ""
        })
        selected != null && all.any { it.id == selected } -> V32RoutineDetail(context, all.first { it.id == selected }, { selected = null }, refresh)
        else -> {
            val filtered = all.filter { it.name.contains(query, true) || it.description.contains(query, true) }
            fun keys(r: AutomationDefinition): List<String> = when (grouping) {
                0 -> r.appPackages.ifEmpty { listOf("Other") }
                1 -> r.categories.ifEmpty { listOf("Uncategorized") }
                else -> listOf(r.trigger.type.name.lowercase().replace('_', ' '))
            }
            val groups = filtered.flatMap { r -> keys(r).map { it to r } }.groupBy({ it.first }, { it.second })
            LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Routines", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { grouped = !grouped; group = null }) { Text(if (grouped) "Individual" else "Grouped") }
                    IconButton(onClick = { create = true }) { Icon(androidx.compose.material.icons.Icons.Rounded.Add, "Create routine") }
                } }
                item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search routines") }) }
                item { CycloneSegmentedControl(listOf("Apps", "Categories", "Specifics"), grouping, { grouping = it; group = null }) }
                if (group != null) item { TextButton(onClick = { group = null }) { Text("‹ ${if (grouping == 0) appLabel(context, group!!) else group}") } }
                task?.takeIf { grouping == 0 && group == it.packageName && UiTask(it).active }?.let { current -> item { CycloneTaskProgress(current) } }
                if (filtered.isEmpty()) item { Text(if (query.isNotBlank()) "No routines match your search." else "No routines yet. Describe one or teach Cyclone by doing.") }
                if (grouped && group == null) {
                    items(groups.keys.sorted()) { key ->
                        TextButton(onClick = { group = key }, modifier = Modifier.fillMaxWidth()) {
                            if (grouping == 0) CycloneAppIcon(key)
                            Column(Modifier.weight(1f).padding(12.dp)) {
                                Text(if (grouping == 0 && key != "Other") appLabel(context, key) else key)
                                Text("${groups.getValue(key).size} routines", style = MaterialTheme.typography.bodySmall)
                                val active = runs.count { run -> run.state == RunState.RUNNING && groups.getValue(key).any { it.id == run.automationId } }
                                if (active > 0) Text("$active active", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("›")
                        }
                    }
                } else items(if (group == null) filtered else groups[group].orEmpty(), key = { it.id }) { routine ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CycloneAppIcon(routine.appPackages.firstOrNull())
                        TextButton(onClick = { selected = routine.id }, modifier = Modifier.weight(1f)) { Text(routine.name, modifier = Modifier.fillMaxWidth()) }
                        TextButton(enabled = routine.enabled, onClick = { AutomationRuntime.router.runManual(routine.id) }) { Text("Run") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                }
            }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("Create a routine") }, text = {
        Column {
            TextButton(onClick = { create = false; onAi() }) { Text("✦ Describe it to Cyclone") }
            TextButton(onClick = { create = false; mode = "teach" }) { Text("Teach by doing · Follow Me") }
            TextButton(onClick = { create = false; mode = "advanced" }) { Text("Advanced") }
        }
    }, confirmButton = { TextButton(onClick = { create = false }) { Text("Cancel") } })
}
