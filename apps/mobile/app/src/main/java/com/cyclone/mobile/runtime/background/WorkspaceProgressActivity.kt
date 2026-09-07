package com.cyclone.mobile.runtime.background

import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.ui.v32.CycloneIntelligenceTheme
import kotlinx.coroutines.delay

/** View-only exact-session surface. Opening this page never acquires input authority. */
class WorkspaceProgressActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        com.cyclone.mobile.ui.overlay.OverlayExternalInteraction.active.value = true
    }
    override fun onStop() {
        com.cyclone.mobile.ui.overlay.OverlayExternalInteraction.active.value = false
        super.onStop()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { CycloneIntelligenceTheme {
            val current by WorkspaceTasks.state.collectAsState()
            val task = current?.takeIf { WorkspaceTasks.matches(it, intent.getStringExtra("task"), intent.getStringExtra("session")) }
            var liveAvailable by remember(task?.sessionId) { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text(task?.title ?: "Task unavailable", style = MaterialTheme.typography.titleLarge)
                }
                if (task == null || task.phase == TaskPhase.STOPPED) {
                    Text("This task has ended. Ask Cyclone to start a new one.")
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(progressPlaneLabel(task),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        if (showsVdPreview(task) && task.sessionId != null && task.phase != TaskPhase.HUMAN) {
                            WorkspacePreview(task.sessionId, Modifier.widthIn(max = 300.dp).fillMaxWidth(.80f).aspectRatio(720f / 1280f)) { liveAvailable = it }
                        }
                        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(task.subtitle, style = MaterialTheme.typography.bodyLarge)
                                if (task.phase == TaskPhase.DONE) {
                                    task.steps.distinct().takeLast(4).forEach { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
                                    Text("Task done", color = MaterialTheme.colorScheme.secondary)
                                }
                                if (task.phase == TaskPhase.REVIEW) {
                                    val confirmation = task.confirmation
                                    Text(confirmation?.explanation ?: "You make the final decision in ${task.app}.", style = MaterialTheme.typography.bodyMedium)
                                    if (confirmation != null) {
                                        Text(task.app, style = MaterialTheme.typography.titleMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OutlinedButton(onClick = { WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "handoff"); finish() }) { Text("Modify") }
                                            Button(enabled = liveAvailable, onClick = {
                                                startService(WorkspaceTasks.commandIntent(this@WorkspaceProgressActivity, task, "confirm")
                                                    .putExtra("confirmation", confirmation.token))
                                            }) { Text(confirmation.button) }
                                        }
                                    } else Button(onClick = { WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "handoff"); finish() },
                                        modifier = Modifier.fillMaxWidth()) { Text("Review in ${task.app}") }
                                }
                                task.queued?.let {
                                    Text("Follow-up saved: $it", style = MaterialTheme.typography.bodySmall)
                                    if (!task.working) TextButton(onClick = {
                                        WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "cancel")
                                        startActivity(android.content.Intent(this@WorkspaceProgressActivity, WorkspaceActivity::class.java)
                                            .putExtra("goal", task.queued))
                                        finish()
                                    }) { Text("Start follow-up") }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "cancel"); finish() }) { Text("Stop task") }
                        if (isLayer2(task)) {
                            Button(
                                onClick = {
                                    val launch = packageManager.getLaunchIntentForPackage(task.packageName)
                                    if (launch != null) startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                    finish()
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Open ${task.app}") }
                        } else if (task.sessionId != null) {
                            val human = task.phase == TaskPhase.HUMAN || task.phase == TaskPhase.PAUSED
                            if (!human || task.resumable) Button(
                                onClick = {
                                    WorkspaceTasks.command(this@WorkspaceProgressActivity, task, if (human) "resume" else "handoff")
                                    if (!human) finish()
                                }, modifier = Modifier.weight(1f)) {
                                Text(if (human) "Continue with Cyclone" else if (task.phase == TaskPhase.DONE) "Open ${task.app}" else "Take control")
                            }
                        }
                    }
                }
            }
        } }
    }

    private fun isLayer2(task: WorkspaceTaskUi): Boolean =
        intent.getStringExtra(ViewProgressRouter.EXTRA_PLANE) == ViewProgressRouter.PLANE_LAYER2 ||
            task.workspaceId != null

    private fun showsVdPreview(task: WorkspaceTaskUi): Boolean {
        if (isLayer2(task)) return false
        return ViewProgressRouter.showsVdFrames(task)
    }

    private fun progressPlaneLabel(task: WorkspaceTaskUi): String = when {
        isLayer2(task) -> "Layer 2 workspace · ${task.app}"
        task.phase == TaskPhase.HUMAN -> "You have control · ${task.app}"
        ViewProgressRouter.showsVdFrames(task) -> "Session Kernel VD · ${task.app}"
        else -> "Cyclone's workspace · ${task.app}"
    }

    @Composable
    private fun WorkspacePreview(sessionId: String, modifier: Modifier, onAvailability: (Boolean) -> Unit) {
        var view by remember(sessionId) { mutableStateOf<ImageView?>(null) }
        var available by remember(sessionId) { mutableStateOf(false) }
        val reportAvailability by rememberUpdatedState(onAvailability)
        DisposableEffect(sessionId) { onDispose { view?.setImageDrawable(null) } }
        LaunchedEffect(sessionId, view) {
            while (true) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    val frame = runCatching { LiveVisionRuntime.preview(sessionId) }.getOrNull()
                    available = frame != null
                    // ImageView releases its old bitmap; avoid recycling while RenderThread uses it.
                    view?.setImageBitmap(frame)
                } else { view?.setImageDrawable(null); available = false }
                reportAvailability(available)
                delay(750)
            }
        }
        Box(modifier.clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
            AndroidView(factory = { context -> ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Live view of Cyclone's separate app workspace"
                view = this
            } }, modifier = Modifier.fillMaxSize().padding(5.dp))
            if (!available) Text("Waiting for a live view…", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
