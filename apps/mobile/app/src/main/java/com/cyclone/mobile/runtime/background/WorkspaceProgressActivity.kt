package com.cyclone.mobile.runtime.background

import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.ui.v32.CycloneAppIcon
import com.cyclone.mobile.ui.v32.CycloneV32Theme
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
        setContent {
            CycloneV32Theme {
                val current by WorkspaceTasks.state.collectAsState()
                val task = current?.takeIf {
                    WorkspaceTasks.matches(it, intent.getStringExtra("task"), intent.getStringExtra("session"))
                }
                var liveAvailable by remember(task?.sessionId) { mutableStateOf(false) }

                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ProgressHeader(task = task, onBack = { finish() })

                    if (task == null || task.phase == TaskPhase.STOPPED) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 1.dp,
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("This task has ended", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Return to Ask Cyclone when you want to start something new.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ProgressStateCard(task)

                            if (showsVdPreview(task) && task.sessionId != null && task.phase != TaskPhase.HUMAN) {
                                WorkspacePreview(
                                    sessionId = task.sessionId,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 310.dp),
                                    onAvailability = { liveAvailable = it },
                                )
                            }

                            if (task.phase == TaskPhase.REVIEW) {
                                ReviewCard(task, liveAvailable)
                            }

                            task.queued?.let { queued ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("Up next", style = MaterialTheme.typography.labelMedium)
                                            Text(
                                                queued,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (!task.working) {
                                            TextButton(onClick = {
                                                WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "cancel")
                                                startActivity(
                                                    android.content.Intent(this@WorkspaceProgressActivity, WorkspaceActivity::class.java)
                                                        .putExtra("goal", queued),
                                                )
                                                finish()
                                            }) { Text("Start") }
                                        }
                                    }
                                }
                            }
                        }

                        ProgressActions(task)
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressHeader(task: WorkspaceTaskUi?, onBack: () -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            if (task != null) {
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    CycloneAppIcon(task.packageName, Modifier.padding(5.dp).size(30.dp))
                }
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(task.app, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        progressPlaneLabel(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text("Task", style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    @Composable
    private fun ProgressStateCard(task: WorkspaceTaskUi) {
        val title = task.title
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = when (task.phase) {
                TaskPhase.DONE -> MaterialTheme.colorScheme.secondaryContainer
                TaskPhase.FAILED -> MaterialTheme.colorScheme.errorContainer
                TaskPhase.REVIEW -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
            contentColor = when (task.phase) {
                TaskPhase.DONE -> MaterialTheme.colorScheme.onSecondaryContainer
                TaskPhase.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                TaskPhase.REVIEW -> MaterialTheme.colorScheme.onTertiaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.phase == TaskPhase.DONE) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(task.subtitle, style = MaterialTheme.typography.bodyMedium)
                task.semanticSteps.takeLast(8).forEach { step ->
                    val status = when (step.state) {
                        SemanticStepState.DONE -> "✓"
                        SemanticStepState.ACTIVE -> "Working"
                        SemanticStepState.PENDING -> "Pending"
                        SemanticStepState.ACTION_NEEDED -> "Action Needed"
                        SemanticStepState.FAILED -> "Not verified"
                    }
                    Text("$status · ${step.label}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    @Composable
    private fun ReviewCard(task: WorkspaceTaskUi, liveAvailable: Boolean) {
        val confirmation = task.confirmation
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    confirmation?.explanation ?: "Check the prepared page in ${task.app} and make the final decision.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (confirmation != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "handoff")
                                finish()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Modify") }
                        Button(
                            enabled = liveAvailable,
                            onClick = {
                                startService(
                                    WorkspaceTasks.commandIntent(this@WorkspaceProgressActivity, task, "confirm")
                                        .putExtra("confirmation", confirmation.token),
                                )
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(confirmation.button) }
                    }
                } else {
                    Button(
                        onClick = {
                            WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "handoff")
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Review in ${task.app}") }
                }
            }
        }
    }

    @Composable
    private fun ProgressActions(task: WorkspaceTaskUi) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "cancel")
                    finish()
                },
            ) { Text(if (task.phase == TaskPhase.DONE || task.phase == TaskPhase.FAILED) "Close task" else "Stop task") }

            when {
                isLayer2(task) -> Button(
                    onClick = {
                        val launch = packageManager.getLaunchIntentForPackage(task.packageName)
                        if (launch != null) startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Open ${task.app}") }

                task.sessionId != null -> {
                    val human = task.interruption?.canResumeAfterHuman == true
                    if (human || task.interruption?.canTakeOver == true || task.working) {
                        Button(
                            onClick = {
                                WorkspaceTasks.command(this@WorkspaceProgressActivity, task, if (human) "resume" else "handoff")
                                if (!human) finish()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                when {
                                    human -> "Continue with Cyclone"
                                    task.phase == TaskPhase.DONE -> "Open ${task.app}"
                                    task.phase == TaskPhase.FAILED -> "Take control"
                                    else -> "Take control"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isLayer2(task: WorkspaceTaskUi): Boolean =
        intent.getStringExtra(ViewProgressRouter.EXTRA_PLANE) == ViewProgressRouter.PLANE_LAYER2 || task.workspaceId != null

    private fun showsVdPreview(task: WorkspaceTaskUi): Boolean {
        if (isLayer2(task)) return false
        return ViewProgressRouter.showsVdFrames(task)
    }

    private fun progressPlaneLabel(task: WorkspaceTaskUi): String = when {
        isLayer2(task) -> "App profile"
        task.phase == TaskPhase.HUMAN -> "You have control"
        ViewProgressRouter.showsVdFrames(task) -> "Live workspace"
        else -> "Phone task"
    }

    @Composable
    private fun WorkspacePreview(sessionId: String, modifier: Modifier, onAvailability: (Boolean) -> Unit) {
        var view by remember(sessionId) { mutableStateOf<ImageView?>(null) }
        var available by remember(sessionId) { mutableStateOf(false) }
        var attempts by remember(sessionId) { mutableIntStateOf(0) }
        val reportAvailability by rememberUpdatedState(onAvailability)

        DisposableEffect(sessionId) { onDispose { view?.setImageDrawable(null) } }
        LaunchedEffect(sessionId, view) {
            while (true) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    val frame = runCatching { LiveVisionRuntime.preview(sessionId) }.getOrNull()
                    attempts++
                    available = frame != null
                    view?.setImageBitmap(frame)
                } else {
                    view?.setImageDrawable(null)
                    available = false
                }
                reportAvailability(available)
                delay(750)
            }
        }

        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 1.dp,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = "Live view of Cyclone's separate app workspace"
                            view = this
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(5.dp),
                )
                if (!available) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (attempts < 3) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("Connecting live view…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Live view unavailable", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "You can still take control.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
