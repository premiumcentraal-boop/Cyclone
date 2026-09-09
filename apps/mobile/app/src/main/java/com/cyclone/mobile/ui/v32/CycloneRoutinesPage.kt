package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.RunState
import com.cyclone.mobile.automation.TriggerType

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

    BackHandler(selected != null || group != null || mode.isNotEmpty()) {
        selected = null
        group = null
        mode = ""
    }

    when {
        mode == "teach" -> CycloneFollowMePage(context, refreshTick) { mode = "" }
        mode == "manual" -> Column {
            TextButton(onClick = { mode = "advanced" }) { Text("‹ Advanced") }
            V32TeachPage(context, refreshTick)
        }
        mode == "advanced" -> LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TextButton(onClick = { mode = "" }) { Text("‹ Routines") } }
            item { CyclonePageIntro("Optional", "Advanced", "Build or inspect a routine when you need precise control.") }
            item {
                CycloneSimpleCard(Modifier.fillMaxWidth()) {
                    RoutineCreateRow(Icons.Rounded.Tune, "Routine builder", "Build triggers and steps manually") { mode = "builder" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    RoutineCreateRow(Icons.Rounded.School, "Manual teacher", "Inspect teaching history and low-level captures") { mode = "manual" }
                }
            }
        }
        mode == "builder" -> V32RoutineBuilder(
            onBack = { mode = "" },
            onSave = { draft ->
                val routine = draft.toAutomationForDevice(notificationAccess = v32NotificationListenerEnabled(context))
                AutomationRuntime.store.saveAutomation(routine)
                if (routine.trigger.type == TriggerType.SCHEDULE) AutomationRuntime.registerSchedule(context, routine)
                refresh()
                mode = ""
            },
        )
        selected != null && all.any { it.id == selected } -> {
            V32RoutineDetail(context, all.first { it.id == selected }, { selected = null }, refresh)
        }
        else -> {
            val normalizedQuery = query.trim()
            val filtered = all.filter { routine ->
                normalizedQuery.isBlank() ||
                    routine.name.contains(normalizedQuery, true) ||
                    routine.description.contains(normalizedQuery, true) ||
                    routine.categories.any { it.contains(normalizedQuery, true) } ||
                    routine.appPackages.any { pkg -> appLabel(context, pkg).contains(normalizedQuery, true) }
            }

            fun keys(routine: AutomationDefinition): List<String> = when (grouping) {
                0 -> routine.appPackages.ifEmpty { listOf("Other") }
                1 -> routine.categories.ifEmpty { listOf("Uncategorized") }
                else -> emptyList()
            }

            val groups = if (grouping == 2) emptyMap() else {
                filtered.flatMap { routine -> keys(routine).map { key -> key to routine } }
                    .groupBy({ it.first }, { it.second })
            }
            val showGrouped = grouped && grouping != 2 && group == null
            val visibleRoutines = if (group == null) filtered else groups[group].orEmpty()

            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Routines", style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "${all.size} ${if (all.size == 1) "routine" else "routines"} · organized your way",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { grouped = !grouped; group = null }, modifier = Modifier.size(44.dp)) {
                            Icon(
                                if (grouped) Icons.Rounded.List else Icons.Rounded.GridView,
                                if (grouped) "Show individual routines" else "Show grouped routines",
                                modifier = Modifier.size(21.dp),
                            )
                        }
                        FilledIconButton(onClick = { create = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Add, "Create routine", modifier = Modifier.size(22.dp))
                        }
                    }
                }

                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { field ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) Text(
                                            "Search routines",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        field()
                                    }
                                },
                            )
                        }
                    }
                }

                item {
                    CycloneSegmentedControl(
                        listOf("Apps", "Categories", "Specifics"),
                        grouping,
                        onSelect = {
                            grouping = it
                            group = null
                        },
                    )
                }

                group?.let { activeGroup ->
                    item {
                        TextButton(onClick = { group = null }) {
                            Text("‹ ${if (grouping == 0 && activeGroup != "Other") appLabel(context, activeGroup) else activeGroup}")
                        }
                    }
                }

                task?.takeIf { grouping == 0 && group == it.packageName && UiTask(it).active }?.let { current ->
                    item { CycloneTaskProgress(current) }
                }

                if (filtered.isEmpty()) {
                    item {
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text(
                                if (query.isNotBlank()) "No routines match your search" else "No routines yet",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (query.isNotBlank()) "Try a different app, category or routine name." else "Describe one to Cyclone or teach it by doing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (showGrouped) {
                    val orderedKeys = groups.keys.sortedWith(compareBy<String> {
                        when {
                            it == "Other" || it == "Uncategorized" -> 1
                            else -> 0
                        }
                    }.thenBy { key ->
                        if (grouping == 0 && key != "Other") appLabel(context, key).lowercase() else key.lowercase()
                    })
                    items(orderedKeys) { key ->
                        val routines = groups.getValue(key)
                        val active = runs.count { run -> run.state == RunState.RUNNING && routines.any { it.id == run.automationId } }
                        RoutineGroupCard(
                            context = context,
                            key = key,
                            grouping = grouping,
                            count = routines.size,
                            active = active,
                            onClick = { group = key },
                        )
                    }
                } else {
                    items(visibleRoutines, key = { it.id }) { routine ->
                        RoutineListCard(
                            routine = routine,
                            onOpen = { selected = routine.id },
                            onRun = { AutomationRuntime.router.runManual(routine.id) },
                        )
                    }
                }
            }
        }
    }

    if (create) {
        AlertDialog(
            onDismissRequest = { create = false },
            title = { Text("Create a routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoutineCreateRow(Icons.Rounded.AutoAwesome, "Describe it to Cyclone", "Tell Cyclone what you want in plain language") {
                        create = false
                        onAi()
                    }
                    RoutineCreateRow(Icons.Rounded.School, "Teach by doing", "Use Follow Me while you perform the routine") {
                        create = false
                        mode = "teach"
                    }
                    RoutineCreateRow(Icons.Rounded.Tune, "Advanced", "Build it manually or inspect teaching tools") {
                        create = false
                        mode = "advanced"
                    }
                }
            },
            confirmButton = { TextButton(onClick = { create = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoutineGroupCard(
    context: Context,
    key: String,
    grouping: Int,
    count: Int,
    active: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (grouping == 0 && key != "Other") {
                        CycloneAppIcon(key, Modifier.size(30.dp))
                    } else {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (grouping == 0 && key != "Other") appLabel(context, key) else key,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$count ${if (count == 1) "routine" else "routines"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (active > 0) CycloneStatus("$active active")
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        }
    }
}

@Composable
private fun RoutineListCard(
    routine: AutomationDefinition,
    onOpen: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                CycloneAppIcon(routine.appPackages.firstOrNull(), Modifier.padding(7.dp).size(29.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(routine.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    routine.v32TriggerSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                routine.categories.firstOrNull()?.let { category ->
                    Text(category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (routine.enabled) {
                TextButton(onClick = onRun) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(17.dp))
                    Spacer(Modifier.size(3.dp))
                    Text("Run", style = MaterialTheme.typography.labelMedium)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        }
    }
}

@Composable
private fun RoutineCreateRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
    }
}
