package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.automation.*
import com.cyclone.mobile.guided.*

/** Consumer review of the existing Follow Me timeline/compiler, never a second recorder. */
@Composable
fun CycloneFollowMePage(context: Context, refreshTick: Int, onBack: () -> Unit) {
    val progress by produceState(FollowMeLearnerRuntime.progress(), refreshTick) {
        while (true) { value = FollowMeLearnerRuntime.progress(); kotlinx.coroutines.delay(800) }
    }
    val session = remember(refreshTick, progress) { RoutineTeachingRuntime.listSessions().firstOrNull { it.id == progress.teachingSessionId && it.endedAt != null } }
    var draft by remember { mutableStateOf<AutomationDefinition?>(null) }
    var message by remember { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick = onBack) { Text("‹ Routines") } }
        item { Text("Teach by doing", style = MaterialTheme.typography.headlineSmall) }
        if (progress.active) {
            item { Text(if (progress.paused) "Teaching paused" else "Learning with you", style = MaterialTheme.typography.titleMedium) }
            item { Text(progress.currentApp) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { if (progress.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause() }) { Text(if (progress.paused) "Continue" else "Pause") }
                Button(onClick = { FollowMeLearnerRuntime.stop() }) { Text("Finish & review") }
            } }
        } else if (draft == null) {
            item { Text("Show Cyclone a useful sequence in an app. Review the proposed routine before using it.") }
            item { Button(onClick = {
                if (v32AccessibilityEnabled(context)) { FollowMeLearnerRuntime.start(context); (context as? Activity)?.moveTaskToBack(true) }
                else message = "Enable or repair Phone control in Settings first."
            }) { Text("Start Follow Me") } }
            if (session != null) {
                item { Text(session.name, style = MaterialTheme.typography.titleMedium) }
                if (session.aiAnalysis.isNotBlank()) item { Text(session.aiAnalysis, style = MaterialTheme.typography.bodyMedium) }
                item { Button(onClick = {
                    val existingId = session.optimizedAutomationId ?: session.copiedAutomationId
                    draft = existingId?.let { AutomationRuntime.store.getAutomation(it) }
                        ?: AutomationRuntime.store.listAutomations().firstOrNull { "[Follow Me session ${session.id}]" in it.description }
                        ?: TeachingRoutineCompilerV292.compileAndSave(context, session)
                    if (draft == null) message = "There isn't enough reliable evidence yet. Try a shorter demonstration."
                }) { Text("Review proposed routine") } }
            }
        }
        draft?.let { routine ->
            item { OutlinedTextField(routine.name, { draft = routine.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("Routine name") }) }
            itemsIndexed(routine.steps, key = { _, step -> step.id }) { index, step ->
                Row {
                    Text(step.v32ReadableName(), modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(onClick = { draft = routine.copy(steps = routine.steps.filterIndexed { i, _ -> i != index }) }) { Text("Remove") }
                }
            }
            item { Button(enabled = routine.name.isNotBlank() && routine.steps.isNotEmpty(), onClick = {
                AutomationRuntime.store.saveAutomation(routine.copy(enabled = false))
                message = "Saved. Open the routine to review its checks and turn it on."
                draft = null
            }) { Text("Save routine") } }
        }
        if (message.isNotBlank()) item { Text(message, style = MaterialTheme.typography.bodyMedium) }
    }
}
