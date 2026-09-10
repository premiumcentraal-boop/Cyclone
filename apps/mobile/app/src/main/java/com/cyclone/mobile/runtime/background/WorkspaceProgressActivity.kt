package com.cyclone.mobile.runtime.background

import android.os.Bundle
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
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
import com.cyclone.mobile.ui.v32.CycloneTaskStatusPill
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import com.cyclone.mobile.ui.v32.TaskHumanizer
import com.cyclone.mobile.ui.v32.canContinueAfterHumanFromUi
import com.cyclone.mobile.ui.v32.canTakeOverFromUi
import com.cyclone.mobile.ui.v32.taskVisualState
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
        // Live workspace frames can contain credentials or account data. Keep this surface out of
        // screenshots, screen recording, and the Android recent-app snapshot.
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
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProgressHeader(task = task, onBack = { finish() })

                    if (task == null || task.phase == TaskPhase.STOPPED) {
                        EndedCard()
                    } else {
                        Column(
                            Modifier.weight(1f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TaskExperienceCard(task, liveAvailable) { available ->
                                liveAvailable = available
                            }
                            task.queued?.let { queued -> QueueCard(task, queued) }
                        }
                        TaskFooter(task)
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
    private fun TaskExperienceCard(
        task: WorkspaceTaskUi,
        liveAvailable: Boolean,
        onAvailability: (Boolean) -> Unit,
    ) {
        val visualState = task.taskVisualState()
        val taskLabel = TaskHumanizer.humanize(task.goal, task.app)
        val layer2 = isLayer2(task)
        Surface(
            modifier = Modifier.fillMaxWidth().animateContentSize(),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        task.app,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CycloneTaskStatusPill(visualState)
                }

                Text(
                    taskLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                task.subtitle.trim().takeIf { it.isNotBlank() && it != taskLabel }?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    task.phase == TaskPhase.HUMAN -> HumanControlHint(task)
                    showsVdPreview(task) && task.sessionId != null -> WorkspacePreview(
                        sessionId = task.sessionId,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp),
                        onAvailability = onAvailability,
                    )
                    else -> Unit
                }

                SemanticProgress(task, visualState)
                TaskActions(task, visualState, layer2, liveAvailable)
            }
        }
    }

    @Composable
    private fun HumanControlHint(task: WorkspaceTaskUi) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .55f),
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("You're in control", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Finish the step in ${task.app}, then come back and tap I'm Done.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun SemanticProgress(task: WorkspaceTaskUi, visualState: CycloneTaskVisualState) {
        val steps = task.steps.distinct().takeLast(4)
        if (steps.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            steps.forEach { step ->
                Text(
                    if (visualState == CycloneTaskVisualState.DONE) "✓  $step" else step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    @Composable
    private fun TaskActions(
        task: WorkspaceTaskUi,
        visualState: CycloneTaskVisualState,
        layer2: Boolean,
        liveAvailable: Boolean,
    ) {
        when (visualState) {
            CycloneTaskVisualState.WORKING -> {
                Text(
                    "Cyclone will keep this workspace grounded while it works.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            CycloneTaskVisualState.ACTION_NEEDED -> {
                if (layer2) {
                    Button(
                        onClick = { openInstalledApp(task.packageName) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text("Open ${task.app}") }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Button(
                            onClick = {
                                WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "handoff")
                                finish()
                            },
                            enabled = task.canTakeOverFromUi(),
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                            shape = RoundedCornerShape(15.dp),
                        ) { Text(if (task.phase == TaskPhase.HUMAN) "In control" else "Take Over") }

                        if (task.confirmation != null) {
                            OutlinedButton(
                                enabled = liveAvailable,
                                onClick = {
                                    startService(
                                        WorkspaceTasks.commandIntent(this@WorkspaceProgressActivity, task, "confirm")
                                            .putExtra("confirmation", task.confirmation.token),
                                    )
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                shape = RoundedCornerShape(15.dp),
                            ) { Text(task.confirmation.button) }
                        } else {
                            OutlinedButton(
                                onClick = { WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "resume") },
                                enabled = task.canContinueAfterHumanFromUi(),
                                modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                                shape = RoundedCornerShape(15.dp),
                            ) { Text("I'm Done") }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Autofill")
                        Text("Soon", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            CycloneTaskVisualState.DONE -> {
                Button(
                    onClick = {
                        if (layer2) openInstalledApp(task.packageName)
                        else {
                            WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "handoff")
                            finish()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = RoundedCornerShape(15.dp),
                ) { Text("Open Result") }
            }
        }
    }

    @Composable
    private fun QueueCard(task: WorkspaceTaskUi, queued: String) {
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

    @Composable
    private fun TaskFooter(task: WorkspaceTaskUi) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(
                onClick = {
                    WorkspaceTasks.command(this@WorkspaceProgressActivity, task, "cancel")
                    finish()
                },
            ) {
                Text(if (task.phase == TaskPhase.DONE || task.phase == TaskPhase.FAILED) "Close task" else "Stop task")
            }
        }
    }

    @Composable
    private fun EndedCard() {
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
    }

    private fun openInstalledApp(packageName: String) {
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
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
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
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
