package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.TriggerType

@Composable
fun CycloneRoutinesPage(context: Context, refreshTick: Int, onAi: () -> Unit, onMarketplace: () -> Unit = {}, refresh: () -> Unit) {
    val all = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // R6: a routine picked in the smart search opens straight to its detail.
    LaunchedEffect(RoutinesNav.pending) {
        RoutinesNav.pending?.let { selected = it; RoutinesNav.pending = null }
    }
    var mode by rememberSaveable { mutableStateOf("") }

    BackHandler(selected != null || mode.isNotEmpty()) {
        when {
            selected != null -> selected = null
            else -> mode = ""
        }
    }

    when {
        mode == "teach" -> CycloneFollowMePage(context, refreshTick) { mode = "" }
        mode == "manual" -> Column {
            CycloneBackRow("Advanced") { mode = "advanced" }
            V32TeachPage(context, refreshTick)
        }
        mode == "advanced" -> LazyColumn(
            contentPadding = cyclonePageInsets(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CycloneBackRow("Skills") { mode = "" } }
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
        else -> CycloneSkillsLibraryPage(context, refreshTick, all,
            onRoutine = { selected = it }, onAi = onAi,
            onTeach = { mode = "teach" }, onAdvanced = { mode = "advanced" }, onMarketplace = onMarketplace)
    }
}

@Composable
internal fun RoutineListCard(
    routine: AutomationDefinition,
    onOpen: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
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
                val ground = com.cyclone.mobile.automation.RoutineGrounding.of(routine)
                if (ground != com.cyclone.mobile.automation.RoutineGround.NO_PHONE) {
                    Text(ground.label, style = MaterialTheme.typography.labelSmall,
                        color = if (ground == com.cyclone.mobile.automation.RoutineGround.GROUNDED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
    }
}

/** R6: where the smart search leaves the routine to open. */
internal object RoutinesNav {
    var pending by androidx.compose.runtime.mutableStateOf<String?>(null)
}
