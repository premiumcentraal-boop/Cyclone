package com.cyclone.mobile.ui.overlay

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.capture.LiveCaptureConsentActivity
import com.cyclone.mobile.capture.LiveCaptureService
import com.cyclone.mobile.capture.LiveCaptureSessionManager
import com.cyclone.mobile.capture.ScreenSharePhase
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.v32.CycloneAskTaskPanel
import com.cyclone.mobile.ui.v32.CycloneChatDrawerSurface
import com.cyclone.mobile.ui.v32.CycloneForegroundWorkCard
import com.cyclone.mobile.ui.v32.CyclonePendingRequests
import com.cyclone.mobile.ui.v32.CycloneTrayIconAction
import com.cyclone.mobile.ui.v32.CycloneV32Theme

private val AuroraBlue = Color(0xFF4A8DFF)
private val AuroraCyan = Color(0xFF80E9FF)
private val AuroraWhite = Color(0xFFE8F4FF)

data class OverlayAiSettings(
    val modelId: String = "",
    val reasoningEffort: String = "medium",
)

data class OverlayIdleVisualState(
    val pulseSerial: Int = 0,
    val pulseLevel: Int = 0,
    val activating: Boolean = false,
)

internal data class OverlayIdleTapResult(
    val pulseSerial: Int,
    val pulseLevel: Int,
    val tapCount: Int,
    val activate: Boolean,
    val ignored: Boolean,
)

internal object SheetDismissal {
    fun shouldDismiss(offsetPx: Float, heightPx: Float): Boolean =
        heightPx > 0f && offsetPx >= heightPx * 0.28f
}

/** Three deliberate taps; a hold is never treated as activation. */
internal class OverlayIdleActivationTracker(
    private val maxGapMs: Long = OverlayChromeContract.IDLE_TAP_MAX_GAP_MS,
    private val maxSequenceMs: Long = OverlayChromeContract.IDLE_TAP_MAX_SEQUENCE_MS,
) {
    private var firstTapAtMs = 0L
    private var lastTapAtMs = 0L
    private var tapCount = 0
    private var pulseSerial = 0
    private var activating = false

    fun onTap(atMs: Long): OverlayIdleTapResult {
        if (activating) return OverlayIdleTapResult(pulseSerial, 0, tapCount, false, true)
        val expired = tapCount == 0 || atMs - lastTapAtMs > maxGapMs || atMs - firstTapAtMs > maxSequenceMs
        if (expired) {
            firstTapAtMs = atMs
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapAtMs = atMs
        pulseSerial += 1
        val level = tapCount.coerceIn(1, 3)
        val activate = tapCount >= 3
        if (activate) {
            activating = true
            tapCount = 0
        }
        return OverlayIdleTapResult(pulseSerial, level, tapCount, activate, false)
    }

    fun semanticActivate(): OverlayIdleTapResult {
        if (activating) return OverlayIdleTapResult(pulseSerial, 0, tapCount, false, true)
        pulseSerial += 1
        activating = true
        tapCount = 0
        return OverlayIdleTapResult(pulseSerial, 3, 0, true, false)
    }

    fun reset() {
        firstTapAtMs = 0L
        lastTapAtMs = 0L
        tapCount = 0
        activating = false
    }
}

@Composable
fun OverlayChrome(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    onComposerChanged: (String) -> Unit = {},
    onRequestSubmitted: (String) -> Unit = {},
    onVoiceInput: () -> Unit = {},
    aiSettings: OverlayAiSettings = OverlayAiSettings(),
    onAiSettingsChanged: (OverlayAiSettings) -> Unit = {},
    idleVisualState: OverlayIdleVisualState = OverlayIdleVisualState(),
    onIdleTap: () -> Unit = {},
    onIdleSemanticActivate: () -> Unit = { onAction(OverlayUserAction.ASK_CYCLONE) },
    modifier: Modifier = Modifier,
) {
    CycloneV32Theme(drawBackground = false) {
        val presentation = when {
            snapshot.state == OverlayChromeState.IDLE -> "idle"
            snapshot.launcherCollapsed -> "launcher"
            snapshot.minimized -> "minimized"
            snapshot.state == OverlayChromeState.GATE -> "gate"
            else -> "drawer"
        }
        AnimatedContent(
            targetState = presentation,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 3 })
                    .togetherWith(fadeOut(tween(130)) + slideOutVertically(tween(160)) { it / 4 })
            },
            label = "Cyclone chat drawer",
        ) { mode ->
            when (mode) {
                "idle" -> if (snapshot.idleChipVisible) {
                    IdleActivationHotspot(
                        state = idleVisualState,
                        onTap = onIdleTap,
                        onSemanticActivate = onIdleSemanticActivate,
                        modifier = modifier,
                    )
                } else {
                    Box(Modifier.size(OverlayChromeContract.IDLE_TOUCH_SIZE_DP.dp))
                }
                "launcher" -> if (snapshot.idleChipVisible) {
                    IdleActivationHotspot(
                        state = idleVisualState,
                        onTap = onIdleTap,
                        onSemanticActivate = onIdleSemanticActivate,
                        modifier = modifier,
                    )
                } else {
                    Box(Modifier.size(OverlayChromeContract.IDLE_TOUCH_SIZE_DP.dp))
                }
                "minimized" -> ComposerPanel(
                    snapshot = snapshot,
                    minimized = true,
                    onAction = onAction,
                    onComposerChanged = onComposerChanged,
                    onRequestSubmitted = onRequestSubmitted,
                    onVoiceInput = onVoiceInput,
                    aiSettings = aiSettings,
                    onAiSettingsChanged = onAiSettingsChanged,
                )
                "gate" -> GatePanel(snapshot, onAction)
                else -> ComposerPanel(
                    snapshot = snapshot,
                    minimized = false,
                    onAction = onAction,
                    onComposerChanged = onComposerChanged,
                    onRequestSubmitted = onRequestSubmitted,
                    onVoiceInput = onVoiceInput,
                    aiSettings = aiSettings,
                    onAiSettingsChanged = onAiSettingsChanged,
                )
            }
        }
    }
}

@Composable
private fun IdleActivationHotspot(
    state: OverlayIdleVisualState,
    onTap: () -> Unit,
    onSemanticActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(OverlayChromeContract.IDLE_TOUCH_SIZE_DP.dp)
            .semantics {
                contentDescription = "Cyclone AI. Triple tap to open."
                onClick("Open Cyclone") {
                    if (!state.activating) onSemanticActivate()
                    true
                }
            }
            .pointerInput(state.activating) {
                if (!state.activating) detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {}
}

/** Non-touchable ambient decoration hosted by the controller in its own window. */
@Composable
internal fun OverlayIdleHalo(
    state: OverlayIdleVisualState,
    modifier: Modifier = Modifier,
) {
    val pulseScale = remember { Animatable(1f) }
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(state.pulseSerial) {
        if (state.pulseSerial <= 0) return@LaunchedEffect
        pulseScale.snapTo(1f)
        pulseAlpha.snapTo(0f)
        val peak = when {
            state.activating -> 1.15f
            state.pulseLevel >= 2 -> 1.11f
            else -> 1.08f
        }
        pulseAlpha.animateTo(if (state.activating) 1f else 0.72f, tween(90))
        pulseScale.animateTo(peak, tween(110, easing = FastOutSlowInEasing))
        pulseScale.animateTo(1f, tween(if (state.activating) 190 else 150, easing = FastOutSlowInEasing))
        pulseAlpha.animateTo(0f, tween(170))
    }

    Canvas(
        modifier
            .size(OverlayChromeContract.IDLE_VISUAL_WIDTH_DP.dp, OverlayChromeContract.IDLE_VISUAL_HEIGHT_DP.dp)
            .graphicsLayer {
                scaleX = pulseScale.value
                scaleY = pulseScale.value
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val response = pulseAlpha.value
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(
                    Color.Transparent,
                    AuroraBlue.copy(alpha = 0.07f + response * 0.07f),
                    AuroraCyan.copy(alpha = 0.15f + response * 0.11f),
                    AuroraWhite.copy(alpha = 0.10f + response * 0.09f),
                    Color.Transparent,
                ),
            ),
            start = Offset(size.width * 0.08f, center.y),
            end = Offset(size.width * 0.92f, center.y),
            strokeWidth = 1.2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(
                    AuroraCyan.copy(alpha = 0.06f + response * 0.13f),
                    AuroraBlue.copy(alpha = 0.025f + response * 0.06f),
                    Color.Transparent,
                ),
                center = center,
                radius = 31.dp.toPx(),
            ),
            radius = 31.dp.toPx(),
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.34f + response * 0.34f),
            radius = if (state.activating) 3.2.dp.toPx() else 2.3.dp.toPx(),
            center = center,
        )
        drawCircle(
            color = AuroraCyan.copy(alpha = 0.13f + response * 0.20f),
            radius = 20.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

@Composable
private fun ComposerPanel(
    snapshot: OverlayChromeSnapshot,
    minimized: Boolean,
    onAction: (OverlayUserAction) -> Unit,
    onComposerChanged: (String) -> Unit,
    onRequestSubmitted: (String) -> Unit,
    onVoiceInput: () -> Unit,
    aiSettings: OverlayAiSettings,
    onAiSettingsChanged: (OverlayAiSettings) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var accessory by remember { mutableStateOf(ComposerAccessory.NONE) }
    val sharing by LiveCaptureSessionManager.state.collectAsState()
    val attached by PendingTaskAttachment.present.collectAsState()
    val queued by WorkspaceTasks.requests.state.collectAsState()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var editorFocused by remember { mutableStateOf(false) }
    var restoreEditor by remember { mutableStateOf(false) }
    val workspace by WorkspaceTasks.state.collectAsState()
    val clearedCards = com.cyclone.mobile.ui.v32.TaskCardDismissals.cleared(context)
    val task = workspace?.takeIf { com.cyclone.mobile.ui.v32.taskCardVisible(it, clearedCards) }
    val foregroundWorking = snapshot.state == OverlayChromeState.WORKING || snapshot.state == OverlayChromeState.LIVE
    val activeWork = task?.working == true || foregroundWorking
    val imePx = LocalOverlayImeBottomPx.current
    val keyboardOpen = imePx > 0 || WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val taskAreaMax = if (keyboardOpen) {
        OverlayChromeContract.TASK_AREA_KEYBOARD_MAX_HEIGHT_DP
    } else {
        OverlayChromeContract.TASK_AREA_MAX_HEIGHT_DP
    }

    LaunchedEffect(task?.taskId, task?.working, foregroundWorking) {
        if (activeWork) {
            focusManager.clearFocus()
            accessory = ComposerAccessory.NONE
        }
    }

    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && restoreEditor) {
                view.post {
                    if (view.isAttachedToWindow && restoreEditor) {
                        restoreEditor = false
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                }
            }
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        onDispose { view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
    }

    fun launchExternal(intent: Intent) {
        OverlayExternalInteraction.active.value = true
        restoreEditor = editorFocused
        accessory = ComposerAccessory.NONE
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                restoreEditor = false
                OverlayExternalInteraction.active.value = false
                android.widget.Toast.makeText(context, "This action is unavailable.", android.widget.Toast.LENGTH_SHORT).show()
            }
    }

    val submit = {
        if (snapshot.composerText.isNotBlank()) {
            onRequestSubmitted(snapshot.composerText)
            focusManager.clearFocus()
        }
    }

    CycloneChatDrawerSurface(
        onCollapse = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            accessory = ComposerAccessory.NONE
            onAction(OverlayUserAction.MINIMIZE)
        },
        onExpand = if (minimized) {
            {
                accessory = ComposerAccessory.NONE
                onAction(OverlayUserAction.ASK_CYCLONE)
            }
        } else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        containerColor = Color(0xFF111317).copy(alpha = .98f),
        contentColor = Color(0xFFF5F5F7),
        outlineColor = Color.White.copy(alpha = .10f),
        handleColor = Color.White.copy(alpha = .34f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        if (!minimized && (task != null || foregroundWorking || queued.isNotEmpty())) {
            OverlayAppleGlass(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 24.dp,
                strong = true,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                        .heightIn(max = taskAreaMax.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        task != null -> CycloneAskTaskPanel(task)
                        foregroundWorking -> CycloneForegroundWorkCard(snapshot)
                    }
                    CyclonePendingRequests { onAction(OverlayUserAction.MINIMIZE) }
                }
            }
        }

        if (sharing.phase != ScreenSharePhase.OFF) {
            OverlayAppleStatusPill(
                text = when (sharing.phase) {
                    ScreenSharePhase.LIVE -> "Sharing screen"
                    ScreenSharePhase.REQUESTING_PERMISSION -> "Waiting for permission"
                    ScreenSharePhase.STARTING -> sharing.message ?: "Starting screen share"
                    ScreenSharePhase.STOPPING -> "Stopping screen share"
                    ScreenSharePhase.ERROR -> sharing.message ?: "Screen sharing failed"
                    ScreenSharePhase.REVOKED -> "Screen sharing ended"
                    ScreenSharePhase.OFF -> "Screen sharing off"
                },
                actionLabel = if (sharing.active) "Stop" else null,
                onAction = if (sharing.active) ({ LiveCaptureService.stop(context) }) else null,
            )
        }

        when (accessory) {
            ComposerAccessory.ATTACHMENTS -> OverlayAppleToolsMenu(
                sharingActive = sharing.active,
                onCamera = {
                    launchExternal(Intent(context, OverlayAttachmentActivity::class.java).putExtra("camera", true))
                },
                onPhotos = {
                    launchExternal(Intent(context, OverlayAttachmentActivity::class.java).putExtra("photos", true))
                },
                onFiles = {
                    launchExternal(Intent(context, OverlayAttachmentActivity::class.java))
                },
                onShareScreen = {
                    launchExternal(Intent(context, LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true))
                },
                onCrossAppShare = {
                    launchExternal(Intent(context, LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true))
                },
                onModelAndIntelligence = { accessory = ComposerAccessory.MODEL },
                modifier = Modifier.fillMaxWidth(),
            )

            ComposerAccessory.MODEL -> OverlayAppleGlass(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 30.dp,
                strong = true,
            ) {
                Box(Modifier.padding(12.dp)) {
                    com.cyclone.mobile.ui.v32.CycloneModelIntelligencePanel(
                        aiSettings.modelId,
                        aiSettings.reasoningEffort,
                    ) { model, effort ->
                        onAiSettingsChanged(aiSettings.copy(modelId = model, reasoningEffort = effort))
                    }
                }
            }

            ComposerAccessory.NONE -> Unit
        }

        if (attached) {
            OverlayAppleStatusPill(
                text = "Reference attached",
                actionLabel = "Remove",
                onAction = { PendingTaskAttachment.take() },
            )
        }

        val quickModelLabel = com.cyclone.mobile.ui.v32.cycloneShortModelLabel(
            com.cyclone.mobile.ui.v32.V39AiChatContract.modelForStored(aiSettings.modelId).label.ifBlank { "Cyclone" },
        )
        val quickIntelligenceLabel = aiSettings.reasoningEffort
            .takeIf(String::isNotBlank)
            ?.let { com.cyclone.mobile.ui.v32.reasoningEffortLabel(it) }
            ?: "Auto"

        OverlayAppleComposerBar(
            text = snapshot.composerText,
            onTextChanged = onComposerChanged,
            focusRequester = focusRequester,
            onFocusChanged = { editorFocused = it },
            placeholder = when {
                foregroundWorking -> "Working on it…"
                snapshot.voiceListening -> OverlayCopy.LISTENING
                else -> OverlayCopy.COMPOSER
            },
            menuOpen = accessory != ComposerAccessory.NONE,
            voiceListening = snapshot.voiceListening,
            working = foregroundWorking || snapshot.userPaused,
            paused = snapshot.userPaused,
            taskKey = snapshot.sessionId,
            onPause = { onAction(OverlayUserAction.TAKE_CONTROL) },
            onStop = { onAction(OverlayUserAction.STOP_TASK) },
            onMenu = {
                accessory = if (accessory == ComposerAccessory.ATTACHMENTS) ComposerAccessory.NONE else ComposerAccessory.ATTACHMENTS
            },
            onDictate = onVoiceInput,
            onPrimary = {
                if (!foregroundWorking && !snapshot.userPaused && snapshot.composerText.isNotBlank()) submit()
            },
            modelLabel = quickModelLabel,
            intelligenceLabel = quickIntelligenceLabel,
            onModelQuick = {
                accessory = if (accessory == ComposerAccessory.MODEL) ComposerAccessory.NONE else ComposerAccessory.MODEL
            },
        )
    }
}

@Composable
private fun GatePanel(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 12.dp),
    ) {
        OverlayAppleGlass(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp,
            strong = true,
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Confirmation needed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    CycloneTrayIconAction(onClick = { onAction(OverlayUserAction.EXIT) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(OverlayCopy.GATE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { onAction(OverlayUserAction.GATE_CONFIRM) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(OverlayCopy.CONFIRM) }
                if (snapshot.gateClass != null) {
                    Text(
                        "Cyclone will authorize only this exact ${snapshot.gateClass.wire} action.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

internal enum class ComposerAccessory {
    NONE, ATTACHMENTS, MODEL;
    fun toggle(next: ComposerAccessory): ComposerAccessory = if (this == next) NONE else next
}

/** Quiet inline status kept for callers/tests outside the Apple-style composer path. */
@Composable
internal fun ScreenSharePill(state: com.cyclone.mobile.capture.ScreenShareState, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state.active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            when (state.phase) {
                ScreenSharePhase.LIVE -> "Sharing screen"
                ScreenSharePhase.REQUESTING_PERMISSION -> "Waiting for permission"
                ScreenSharePhase.STARTING -> state.message ?: "Starting screen share"
                ScreenSharePhase.STOPPING -> "Stopping screen share"
                ScreenSharePhase.ERROR -> state.message ?: "Screen sharing failed"
                ScreenSharePhase.REVOKED -> "Screen sharing ended"
                ScreenSharePhase.OFF -> "Screen sharing off"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.active) {
            TextButton(
                onClick = onStop,
                enabled = state.phase != ScreenSharePhase.STOPPING,
                modifier = Modifier.heightIn(min = 40.dp),
            ) { Text("Stop") }
        }
    }
}
