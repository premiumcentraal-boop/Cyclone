package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.*

@Composable
fun CycloneRoutinesPage(context: Context, refreshTick: Int, onAi: () -> Unit, refresh: () -> Unit) {
    val all = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    var query by rememberSaveable { mutableStateOf("") }
    var grouped by rememberSaveable { mutableStateOf(true) }
    var grouping by rememberSaveable { mutableIntStateOf(0) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var create by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("") }
    BackHandler(selected != null || group != null || mode.isNotEmpty()) { selected = null; group = null; mode = "" }
    when {
        mode == "teach" -> Column {
            TextButton(onClick = { mode = "" }) { Text("‹ Routines") }
            V32TeachPage(context, refreshTick)
        }
        mode == "advanced" -> V32RoutineBuilder(onBack = { mode = "" }, onSave = { draft ->
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
                    TextButton(onClick = { create = true }) { Text("+") }
                } }
                item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search routines") }) }
                item { CycloneSegmentedControl(listOf("Apps", "Categories", "Specifics"), grouping, { grouping = it; group = null }) }
                if (group != null) item { TextButton(onClick = { group = null }) { Text("‹ ${if (grouping == 0) appLabel(context, group!!) else group}") } }
                if (filtered.isEmpty()) item { Text("No routines yet. Describe one or teach Cyclone by doing.") }
                if (grouped && group == null) {
                    items(groups.keys.sorted()) { key ->
                        TextButton(onClick = { group = key }, modifier = Modifier.fillMaxWidth()) {
                            if (grouping == 0) CycloneAppIcon(key)
                            Column(Modifier.weight(1f).padding(12.dp)) {
                                Text(if (grouping == 0 && key != "Other") appLabel(context, key) else key)
                                Text("${groups.getValue(key).size} routines", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("›")
                        }
                    }
                } else items(if (group == null) filtered else groups[group].orEmpty(), key = { it.id }) { routine ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CycloneAppIcon(routine.appPackages.firstOrNull())
                        TextButton(onClick = { selected = routine.id }, modifier = Modifier.weight(1f)) { Text(routine.name, modifier = Modifier.fillMaxWidth()) }
                        TextButton(onClick = { AutomationRuntime.router.runManual(routine.id) }) { Text("Run") }
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
