package com.cyclone.mobile.ui.overlay

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.capture.LiveCaptureConsentActivity
import com.cyclone.mobile.capture.LiveCaptureService
import com.cyclone.mobile.capture.LiveCaptureSessionManager
import com.cyclone.mobile.capture.ScreenSharePhase
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.v32.CycloneAskTaskPanel
import com.cyclone.mobile.ui.v32.CycloneForegroundWorkCard
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidIconButton
import com.cyclone.mobile.ui.v32.CycloneLiquidPanel
import com.cyclone.mobile.ui.v32.CycloneLiquidTray
import com.cyclone.mobile.ui.v32.CyclonePendingRequests
import com.cyclone.mobile.ui.v32.CycloneTrayIconAction
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import com.cyclone.mobile.ui.v32.LocalCycloneLiquidBackdrop
import kotlinx.coroutines.launch

private val AuroraBlue = Color(0xFF4A8DFF)
private val AuroraCyan = Color(0xFF80E9FF)
private val AuroraViolet = Color(0xFF8568FF)
private val AuroraMagenta = Color(0xFFE56CFF)

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
    CycloneV32Theme {
        val showOrb = snapshot.state == OverlayChromeState.IDLE || snapshot.minimized
        AnimatedContent(
            targetState = showOrb,
            transitionSpec = {
                if (targetState) {
                    (fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 2 })
                        .togetherWith(fadeOut(tween(130)))
                } else {
                    (fadeIn(tween(220)) + slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it / 2 })
                        .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 })
                }
            },
            label = "Cyclone composer",
        ) { orb ->
            when {
                orb && snapshot.idleChipVisible -> IdleActivationHotspot(
                    state = idleVisualState,
                    onTap = onIdleTap,
                    onSemanticActivate = onIdleSemanticActivate,
                    modifier = modifier,
                )
                !orb && snapshot.state == OverlayChromeState.GATE -> GatePanel(snapshot, onAction)
                !orb -> ComposerPanel(
                    snapshot = snapshot,
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
                    AuroraMagenta.copy(alpha = 0.10f + response * 0.09f),
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
                    AuroraViolet.copy(alpha = 0.025f + response * 0.06f),
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
    onAction: (OverlayUserAction) -> Unit,
    onComposerChanged: (String) -> Unit,
    onRequestSubmitted: (String) -> Unit,
    onVoiceInput: () -> Unit,
    aiSettings: OverlayAiSettings,
    onAiSettingsChanged: (OverlayAiSettings) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var accessory by remember { mutableStateOf<ComposerAccessory>(ComposerAccessory.NONE) }
    val sharing by LiveCaptureSessionManager.state.collectAsState()
    val attached by PendingTaskAttachment.present.collectAsState()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var editorFocused by remember { mutableStateOf(false) }
    var restoreEditor by remember { mutableStateOf(false) }
    val workspace by WorkspaceTasks.state.collectAsState()
    val task = workspace?.takeIf { it.phase != TaskPhase.STOPPED }
    val foregroundWorking = snapshot.state == OverlayChromeState.WORKING || snapshot.state == OverlayChromeState.LIVE
    val activeWork = task?.working == true || foregroundWorking
    val compactRunning = activeWork && !editorFocused
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
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

    var dragOffset by remember { mutableStateOf(0f) }
    var sheetHeight by remember { mutableStateOf(220f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val submit = {
        if (snapshot.composerText.isNotBlank()) {
            onRequestSubmitted(snapshot.composerText)
            focusManager.clearFocus()
        }
    }

    fun settle(dismiss: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(dragOffset, if (dismiss) sheetHeight else 0f, animationSpec = tween(180)) { value, _ ->
                dragOffset = value
            }
            if (dismiss) onAction(OverlayUserAction.MINIMIZE)
            dragOffset = 0f
        }
    }

    // Never inherit a full-screen sizing modifier here. The old overlay did, which turned the
    // translucent sheet into the photographed full-screen white rectangle. This surface owns only
    // its content height; the surrounding overlay window remains transparent and touch-through.
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp)
            .graphicsLayer { translationY = dragOffset }
            .onSizeChanged { sheetHeight = it.height.toFloat().coerceAtLeast(1f) },
    ) {
        CycloneLiquidPanel(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            cornerRadius = 32.dp,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { settleJob?.cancel() },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset = (dragOffset + amount).coerceAtLeast(0f)
                                },
                                onDragCancel = { settle(false) },
                                onDragEnd = { settle(SheetDismissal.shouldDismiss(dragOffset, sheetHeight)) },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(34.dp, 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .30f)),
                    )
                }

                Column(
                    Modifier
                        .fillMaxWidth()
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

                if (sharing.phase != ScreenSharePhase.OFF) {
                    ScreenSharePill(sharing) { LiveCaptureService.stop(context) }
                }

                if (accessory != ComposerAccessory.NONE) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .045f),
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        when (accessory) {
                            ComposerAccessory.ATTACHMENTS -> Row(
                                Modifier.heightIn(min = 52.dp).padding(horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { launchExternal(Intent(context, OverlayAttachmentActivity::class.java)) }) { Text("File") }
                                TextButton(onClick = { launchExternal(Intent(context, OverlayAttachmentActivity::class.java).putExtra("camera", true)) }) { Text("Photo") }
                                TextButton(enabled = !sharing.active, onClick = {
                                    launchExternal(Intent(context, LiveCaptureConsentActivity::class.java))
                                }) { Text("Share screen") }
                                TextButton(enabled = !sharing.active, onClick = {
                                    launchExternal(Intent(context, LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true))
                                }) { Text("Cross-app") }
                            }
                            ComposerAccessory.MODEL -> com.cyclone.mobile.ui.v32.CycloneModelIntelligencePanel(
                                aiSettings.modelId,
                                aiSettings.reasoningEffort,
                            ) { model, effort ->
                                onAiSettingsChanged(aiSettings.copy(modelId = model, reasoningEffort = effort))
                            }
                            ComposerAccessory.NONE -> Unit
                        }
                    }
                }

                if (attached) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Reference attached",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { PendingTaskAttachment.take() }) { Text("Remove") }
                    }
                }

                // The editor is part of the one parent glass object. Tool icons are transparent hit
                // targets; Send alone gets a separate primary liquid lens.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OverlayChromeContract.COMPOSER_HEIGHT_DP.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!compactRunning) {
                        CycloneTrayIconAction(
                            onClick = { accessory = accessory.toggle(ComposerAccessory.MODEL) },
                            modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Tune,
                                "Choose model",
                                tint = if (accessory == ComposerAccessory.MODEL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    CycloneTrayIconAction(
                        onClick = { accessory = accessory.toggle(ComposerAccessory.ATTACHMENTS) },
                        modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                    ) {
                        Icon(Icons.Rounded.Add, "Add attachment", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(25.dp))
                    }

                    BasicTextField(
                        value = snapshot.composerText,
                        onValueChange = onComposerChanged,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { editorFocused = it.isFocused }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 8.dp, vertical = 13.dp)
                            .semantics { contentDescription = OverlayCopy.COMPOSER },
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (snapshot.composerText.isEmpty()) {
                                    Text(
                                        when {
                                            foregroundWorking -> "Working on it…"
                                            snapshot.voiceListening -> OverlayCopy.LISTENING
                                            else -> OverlayCopy.COMPOSER
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                field()
                            }
                        },
                    )

                    if (!compactRunning) {
                        CycloneTrayIconAction(
                            onClick = onVoiceInput,
                            modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Mic,
                                contentDescription = "Dictate request",
                                tint = if (snapshot.voiceListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }

                    val backdrop = LocalCycloneLiquidBackdrop.current
                    if (backdrop != null) {
                        CycloneKyantLiquidIconButton(
                            onClick = {
                                if (foregroundWorking) onAction(OverlayUserAction.STOP_TASK) else submit()
                            },
                            backdrop = backdrop,
                            enabled = if (foregroundWorking) true else snapshot.composerText.isNotBlank(),
                            modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        ) {
                            if (foregroundWorking) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.onPrimary),
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.ArrowUpward,
                                    "Send new task",
                                    modifier = Modifier.size(22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                }
            }
        }
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
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp),
    ) {
        CycloneLiquidPanel(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp,
            contentPadding = PaddingValues(18.dp),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                TextButton(
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

@Composable
internal fun ScreenSharePill(state: com.cyclone.mobile.capture.ScreenShareState, onStop: () -> Unit) {
    CycloneLiquidTray(modifier = Modifier.fillMaxWidth(), height = 52.dp, contentPadding = 4.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (state.active) {
                TextButton(
                    onClick = onStop,
                    enabled = state.phase != ScreenSharePhase.STOPPING,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("Stop") }
            }
        }
    }
}
