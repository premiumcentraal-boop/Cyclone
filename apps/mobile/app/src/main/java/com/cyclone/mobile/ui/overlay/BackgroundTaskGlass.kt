package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.v32.CycloneAppIcon
import com.cyclone.mobile.ui.v32.CycloneTaskStatusPill
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState
import com.cyclone.mobile.ui.v32.TaskHumanizer
import com.cyclone.mobile.ui.v32.canContinueAfterHumanFromUi
import com.cyclone.mobile.ui.v32.taskVisualState

/**
 * The host app stays visually primary while Cyclone works.
 *
 * Background work is one compact, tappable status ribbon. Tapping it expands the normal Ask
 * Cyclone surface with full task controls. Human handoff keeps one explicit continuation button.
 * Terminal results never render here; BackgroundGlassPolicy hands those to notification/history.
 */
@Composable
fun BackgroundTaskGlass(task: WorkspaceTaskUi, onAsk: () -> Unit) {
    val outsideCyclone = DeviceState.currentPackage != "com.cyclone.mobile"
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (outsideCyclone && task.phase == TaskPhase.HUMAN) {
            HumanTakeoverRibbon(task)
        } else {
            BackgroundTaskRibbon(task, onAsk)
        }
    }
}

@Composable
private fun BackgroundTaskRibbon(task: WorkspaceTaskUi, onAsk: () -> Unit) {
    val visualState = task.taskVisualState()
    val taskLabel = TaskHumanizer.humanize(task.goal, task.app)

    Surface(
        onClick = onAsk,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .semantics { contentDescription = "Open Cyclone details for $taskLabel" },
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(11.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                CycloneAppIcon(task.packageName, Modifier.padding(5.dp).size(28.dp))
            }
            Text(
                taskLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CycloneTaskStatusPill(visualState)
        }
    }
}

@Composable
private fun HumanTakeoverRibbon(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Cyclone", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    CycloneTaskStatusPill(CycloneTaskVisualState.ACTION_NEEDED)
                }
                Text(
                    "Finish in ${task.app}, then continue Cyclone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = { WorkspaceTasks.command(context, task, "resume") },
                enabled = task.canContinueAfterHumanFromUi(),
                modifier = Modifier.heightIn(min = 44.dp).semantics {
                    contentDescription = "I'm done, continue with Cyclone"
                },
                shape = RoundedCornerShape(15.dp),
            ) { Text("I'm Done") }
        }
    }
}
