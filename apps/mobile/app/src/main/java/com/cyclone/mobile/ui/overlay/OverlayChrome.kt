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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.background.TaskPhase
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.cyclone.mobile.capture.LiveCaptureSessionManager
import com.cyclone.mobile.capture.LiveCaptureService
import com.cyclone.mobile.capture.LiveCaptureConsentActivity
import com.cyclone.mobile.capture.ScreenSharePhase
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import kotlinx.coroutines.launch

private val AuroraBlue = Color(0xFF4A8DFF)
private val AuroraCyan = Color(0xFF80E9FF)
private val AuroraViolet = Color(0xFF8568FF)
private val AuroraMagenta = Color(0xFFE56CFF)
private val ComposerInk: Color
    @Composable get() = MaterialTheme.colorScheme.surface

data class OverlayAiSettings(
    val modelId: String = OpenRouterModelPresets.DEFAULT.id,
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
                !orb && snapshot.state == OverlayChromeState.GATE -> GatePanel(snapshot, onAction, modifier)
                !orb -> ComposerPanel(
                    snapshot = snapshot,
                    onAction = onAction,
                    onComposerChanged = onComposerChanged,
                    onRequestSubmitted = onRequestSubmitted,
                    onVoiceInput = onVoiceInput,
                    aiSettings = aiSettings,
                    onAiSettingsChanged = onAiSettingsChanged,
                    modifier = modifier,
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
    modifier: Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var accessory by remember { mutableStateOf<ComposerAccessory>(ComposerAccessory.NONE) }
    var modelsExpanded by remember { mutableStateOf(false) }
    val sharing by LiveCaptureSessionManager.state.collectAsState()
    val attached by PendingTaskAttachment.present.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var editorFocused by remember { mutableStateOf(false) }
    var restoreEditor by remember { mutableStateOf(false) }
    val workspace by WorkspaceTasks.state.collectAsState()
    val task = workspace?.takeIf { it.phase != TaskPhase.STOPPED }
    val compactRunning = task?.working == true && !editorFocused
    LaunchedEffect(task?.taskId, task?.working) {
        if (task?.working == true) { focusManager.clearFocus(); accessory = ComposerAccessory.NONE }
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp,
            )
            .graphicsLayer { translationY = dragOffset }
            .onSizeChanged { sheetHeight = it.height.toFloat().coerceAtLeast(1f) },
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
            Box(Modifier.size(34.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .36f)))
        }

        if (task != null) {
            Surface(shape = RoundedCornerShape(28.dp), color = ComposerInk, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(task.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text(task.subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    if (task.queued != null) Text("Follow-up saved", color = AuroraCyan, style = MaterialTheme.typography.labelMedium)
                    Button(onClick = { onAction(OverlayUserAction.MINIMIZE); context.startActivity(WorkspaceTasks.progressIntent(context, task)) },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AuroraBlue)) { Text("View progress") }
                }
            }
        }
        if (sharing.phase != ScreenSharePhase.OFF) {
            ScreenSharePill(sharing) { LiveCaptureService.stop(context) }
        }
        if (accessory != ComposerAccessory.NONE) {
            Surface(shape = RoundedCornerShape(26.dp), color = ComposerInk, shadowElevation = 6.dp) {
                when (accessory) {
                    ComposerAccessory.ATTACHMENTS -> Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { launchExternal(Intent(context, OverlayAttachmentActivity::class.java)) }) { Text("File") }
                        TextButton(onClick = { launchExternal(Intent(context, OverlayAttachmentActivity::class.java).putExtra("camera", true)) }) { Text("Photo") }
                        TextButton(enabled = !sharing.active, onClick = {
                            launchExternal(Intent(context, LiveCaptureConsentActivity::class.java))
                        }) { Text("Share screen") }
                    }
                    ComposerAccessory.MODEL -> Column(Modifier.heightIn(max = 310.dp).padding(12.dp).verticalScroll(rememberScrollState())) {
                        Text("Intelligence", style = MaterialTheme.typography.titleSmall)
                        val levels = listOf("low", "medium", "high")
                        androidx.compose.material3.Slider(value = levels.indexOf(aiSettings.reasoningEffort).coerceAtLeast(0).toFloat(), valueRange = 0f..2f, steps = 1,
                            onValueChange = { onAiSettingsChanged(aiSettings.copy(reasoningEffort = levels[kotlin.math.round(it).toInt().coerceIn(0, 2)])) })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("Low", "Medium", "High").forEach { Text(it, style = MaterialTheme.typography.labelSmall) } }
                        Text("Phone autonomy", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                        var autonomy by remember { mutableStateOf(com.cyclone.mobile.ai.CycloneAiAccessProfileStore.read(context)) }
                        Row {
                            listOf(com.cyclone.mobile.ai.CycloneAiAccessProfile.GUIDED to "Ask often", com.cyclone.mobile.ai.CycloneAiAccessProfile.BALANCED to "Balanced", com.cyclone.mobile.ai.CycloneAiAccessProfile.FULL to "Independent").forEach { (profile, label) ->
                                TextButton(onClick = { autonomy = profile; com.cyclone.mobile.ai.CycloneAiAccessProfileStore.write(context, profile) }) { Text((if (autonomy == profile) "✓ " else "") + label, style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                        Text("Sensitive actions always ask.", style = MaterialTheme.typography.bodySmall)

                        TextButton(onClick = { modelsExpanded = !modelsExpanded }) {
                            Text(OpenRouterModelPresets.byId(aiSettings.modelId).label + if (modelsExpanded) " ▴" else " ▾")
                        }
                        if (modelsExpanded) {
                        OpenRouterModelPresets.all.forEach { model ->
                            TextButton(onClick = {
                                onAiSettingsChanged(aiSettings.copy(modelId = model.id))
                                modelsExpanded = false
                            }) { Text((if (model.id == aiSettings.modelId) "✓ " else "") + model.label) }
                        }
                        TextButton(enabled = !sharing.active, onClick = {
                            launchExternal(Intent(context, LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true))
                        }) { Text("Share for cross-app control") }
                        }
                    }
                    ComposerAccessory.NONE -> Unit
                }
            }
        }
        if (attached) {
            Surface(shape = RoundedCornerShape(24.dp), color = ComposerInk) {
                TextButton(onClick = { PendingTaskAttachment.take() }) { Text("Reference attached · Remove") }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(34.dp),
            color = ComposerInk,
            shadowElevation = 8.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = OverlayChromeContract.COMPOSER_HEIGHT_DP.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!compactRunning) IconButton(
                    onClick = { accessory = accessory.toggle(ComposerAccessory.MODEL) },
                    modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                ) {
                    Icon(Icons.Rounded.Tune, "Choose model", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
                IconButton(
                    onClick = { accessory = accessory.toggle(ComposerAccessory.ATTACHMENTS) },
                    modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                ) {
                    Icon(Icons.Rounded.Add, "Add attachment", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(27.dp))
                }


                BasicTextField(
                    value = snapshot.composerText,
                    onValueChange = onComposerChanged,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(AuroraCyan),
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
                                    if (snapshot.voiceListening) OverlayCopy.LISTENING else OverlayCopy.COMPOSER,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .68f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            field()
                        }
                    },
                )

                if (!compactRunning) IconButton(
                    onClick = onVoiceInput,
                    modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = "Dictate request",
                        tint = if (snapshot.voiceListening) AuroraCyan else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(25.dp),
                    )
                }

                FilledIconButton(
                    onClick = { if (task?.working == true && snapshot.composerText.isBlank()) WorkspaceTasks.command(context, task, "cancel") else submit() },
                    enabled = task?.working == true || snapshot.composerText.isNotBlank(),
                    modifier = Modifier.size(OverlayChromeContract.COMPOSER_TOUCH_TARGET_DP.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = AuroraBlue,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f),
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .30f),
                    ),
                ) {
                    Icon(if (task?.working == true && snapshot.composerText.isBlank()) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                        if (task?.working == true && snapshot.composerText.isBlank()) "Stop task" else "Send request", modifier = Modifier.size(23.dp))
                }
            }
        }
    }
}

@Composable
private fun GatePanel(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = 12.dp,
                end = 12.dp,
                bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp,
            ),
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = ComposerInk, shadowElevation = 10.dp) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Confirmation needed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onAction(OverlayUserAction.EXIT) }) {
                        Icon(Icons.Rounded.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
                    }
                }
                Text(OverlayCopy.GATE, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f))
                TextButton(
                    onClick = { onAction(OverlayUserAction.GATE_CONFIRM) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(OverlayCopy.CONFIRM)
                }
                if (snapshot.gateClass != null) {
                    Text(
                        "Cyclone will authorize only this exact ${snapshot.gateClass.wire} action.",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .58f),
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
    Surface(shape = RoundedCornerShape(26.dp), color = ComposerInk, shadowElevation = 6.dp) {
        Row(Modifier.heightIn(min = 52.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(when (state.phase) {
                ScreenSharePhase.LIVE -> "Sharing screen"
                ScreenSharePhase.REQUESTING_PERMISSION -> "Waiting for permission"
                ScreenSharePhase.STARTING -> state.message ?: "Starting screen share"
                ScreenSharePhase.STOPPING -> "Stopping screen share"
                ScreenSharePhase.ERROR -> state.message ?: "Screen sharing failed"
                ScreenSharePhase.REVOKED -> "Screen sharing ended"
                ScreenSharePhase.OFF -> "Screen sharing off"
            }, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
            if (state.active) TextButton(onClick = onStop, enabled = state.phase != ScreenSharePhase.STOPPING) { Text("Stop") }
        }
    }
}
