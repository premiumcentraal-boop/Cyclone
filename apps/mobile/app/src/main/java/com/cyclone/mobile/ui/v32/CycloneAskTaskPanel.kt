package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

/** Presentation state only. Every action receives the original exact-plane task. */
@Composable
fun CycloneAskTaskPanel(task: WorkspaceTaskUi) {
    var expanded by rememberSaveable(task.taskId) { mutableStateOf(UiTask(task).active) }
    val context = LocalContext.current
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    LaunchedEffect(task.taskId, task.confirmation) {
        if (task.confirmation != null) expanded = true
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CycloneOrbitMark(Modifier.size(28.dp))
            Text("Current task · ${UiTask(task).consumerStatus}", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            IconButton(onClick = { expanded = !expanded }) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (expanded) "Collapse task details" else "Expand task details")
            }
        }
        if (expanded && !keyboardOpen) {
            Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                CycloneTaskProgress(task)
            }
        } else {
            TextButton(onClick = { UiTask(task).open(context) }) {
                Text(if (task.confirmation != null) "Review required" else "View progress & controls")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}
