package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animate
import androidx.compose.ui.graphics.SolidColor
import com.cyclone.mobile.ui.v32.CycloneOrbitMark
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

private val AuroraBlue = Color(0xFF4A8DFF)
private val AuroraCyan = Color(0xFF80E9FF)
private val AuroraViolet = Color(0xFF8568FF)
private val AuroraMagenta = Color(0xFFE56CFF)
private val AuroraInk = Color(0xFF12171C)

data class OverlayAiSettings(
    val modelId: String = OpenRouterModelPresets.DEFAULT.id,
    val reasoningEffort: String = "medium",
)

private val intelligenceLevels = listOf("low", "medium", "high", "max")

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

/**
 * Pure triple-tap recognizer. It intentionally has no long-press path: a stationary hold can at
 * most become one ordinary tap when the pointer is released, never an activation shortcut.
 */
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
        if (activating) {
            return OverlayIdleTapResult(pulseSerial, 0, tapCount, activate = false, ignored = true)
        }
        val expired = tapCount == 0 ||
            atMs - lastTapAtMs > maxGapMs ||
            atMs - firstTapAtMs > maxSequenceMs
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
        return OverlayIdleTapResult(pulseSerial, level, tapCount, activate, ignored = false)
    }

    fun semanticActivate(): OverlayIdleTapResult {
        if (activating) {
            return OverlayIdleTapResult(pulseSerial, 0, tapCount, activate = false, ignored = true)
        }
        pulseSerial += 1
        activating = true
        tapCount = 0
        return OverlayIdleTapResult(pulseSerial, 3, 0, activate = true, ignored = false)
    }

    fun reset() {
        firstTapAtMs = 0L
        lastTapAtMs = 0L
        tapCount = 0
        activating = false
    }
}

/**
 * One accessibility overlay for the idle orb and every AI-mode state. The chrome only emits
 * Cyclone actions; it never dispatches accessibility actions into the app underneath it.
 */
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
                    (fadeIn(tween(260)) + slideInVertically(tween(360, easing = FastOutSlowInEasing)) { it / 2 })
                        .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(220)) { it / 3 })
                } else {
                    (fadeIn(tween(380)) + slideInVertically(tween(520, easing = FastOutSlowInEasing)) { it })
                        .togetherWith(fadeOut(tween(160)))
                }
            },
            label = "Cyclone AI mode",
        ) { orb ->
            if (orb && snapshot.idleChipVisible) {
                IdleActivationHotspot(
                    state = idleVisualState,
                    onTap = onIdleTap,
                    onSemanticActivate = onIdleSemanticActivate,
                    modifier = modifier,
                )
            } else if (!orb) {
                AuroraPanel(
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
                onClick("Open Cyclone AI") {
                    if (!state.activating) onSemanticActivate()
                    true
                }
            }
            .pointerInput(state.activating) {
                if (!state.activating) {
                    detectTapGestures(onTap = { onTap() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {}
}

/**
 * Ambient compact decoration. The controller hosts this in a separate FLAG_NOT_TOUCHABLE window;
 * this composable must never become the activation hit target.
 */
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
            .size(
                OverlayChromeContract.IDLE_VISUAL_WIDTH_DP.dp,
                OverlayChromeContract.IDLE_VISUAL_HEIGHT_DP.dp,
            )
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
                colors = listOf(
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
private fun AuroraPanel(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    onComposerChanged: (String) -> Unit,
    onRequestSubmitted: (String) -> Unit,
    onVoiceInput: () -> Unit,
    aiSettings: OverlayAiSettings,
    onAiSettingsChanged: (OverlayAiSettings) -> Unit,
    modifier: Modifier,
) {
    var showAiSettings by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var sheetHeight by remember { mutableStateOf(300f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun settle(dismiss: Boolean) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(dragOffset, if (dismiss) sheetHeight else 0f, animationSpec = tween(220)) { value, _ -> dragOffset = value }
            if (dismiss) onAction(OverlayUserAction.MINIMIZE)
            dragOffset = 0f
        }
    }
    Column(modifier.fillMaxWidth().onSizeChanged { sheetHeight = it.height.toFloat() }
        .graphicsLayer { translationY = dragOffset }
        .clip(RoundedCornerShape(30.dp)).background(Color(0xFF191A20))
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(24.dp)
            .semantics { contentDescription = "Drag down to dismiss Cyclone"; onClick("Dismiss") { settle(true); true } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { settleJob?.cancel() },
                    onVerticalDrag = { change, amount -> change.consume(); dragOffset = (dragOffset + amount).coerceAtLeast(0f) },
                    onDragCancel = { settle(false) },
                    onDragEnd = { settle(SheetDismissal.shouldDismiss(dragOffset, sheetHeight)) })
            }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(34.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = .35f)))
        }
        AnimatedVisibility(showAiSettings) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                QuickAiSettings(aiSettings, onAiSettingsChanged)
            }
        }
        if (snapshot.state == OverlayChromeState.ANALYSIS && !snapshot.statusMessage.isNullOrBlank()) {
            Text(snapshot.statusMessage, color = Color.White.copy(alpha = .75f),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 110.dp)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp))
        }
        if (snapshot.state !in setOf(OverlayChromeState.IDLE, OverlayChromeState.ANALYSIS)) {
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                AuroraStateContent(snapshot, onAction)
                if (snapshot.state in setOf(OverlayChromeState.WORKING, OverlayChromeState.LIVE)) {
                    Row {
                        TextButton(onClick = { onAction(OverlayUserAction.TAKE_CONTROL) }) { Text(if (snapshot.userPaused) "Resume" else "Take control") }
                        TextButton(onClick = { onAction(OverlayUserAction.STOP_TASK) }) { Text("Stop task") }
                    }
                }
            }
        }
        if (snapshot.state != OverlayChromeState.GATE) {
            AuroraComposer(snapshot, onComposerChanged, onRequestSubmitted, onVoiceInput, { showAiSettings = !showAiSettings })
        }
    }
}

@Composable
private fun MovingAurora(modifier: Modifier = Modifier) {
    // Static edge light avoids constant movement behind text and respects reduced-motion users.
    Canvas(modifier) {
        drawRect(AuroraInk)
        drawRect(Brush.verticalGradient(listOf(Color(0xFF202932), AuroraInk)))
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, AuroraBlue, AuroraCyan,
            AuroraViolet, Color.Transparent)), Offset(size.width * .08f, 1.dp.toPx()),
            Offset(size.width * .92f, 1.dp.toPx()), strokeWidth = 2.dp.toPx())
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlow(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.34f), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

@Composable
private fun AuroraControls(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    onToggleSettings: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CycloneOrbitMark(Modifier.size(36.dp))
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Cyclone",
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            Text(
                stateLabel(snapshot),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AuroraIconButton("AI settings", onToggleSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(19.dp))
        }
        if (snapshot.state == OverlayChromeState.WORKING || snapshot.state == OverlayChromeState.LIVE) {
            TextButton(onClick = { onAction(OverlayUserAction.STOP_TASK) }) {
                Text("Stop task", color = Color.White)
            }
        }

    }
}

@Composable
private fun QuickAiSettings(
    settings: OverlayAiSettings,
    onChanged: (OverlayAiSettings) -> Unit,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var accessResult by remember(settings.modelId) { mutableStateOf<String?>(null) }
    val selectedModel = OpenRouterModelPresets.byId(settings.modelId)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Model", color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { modelMenuOpen = true }) {
                        Text(selectedModel.label, color = Color.White, maxLines = 1)
                    }
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        OpenRouterModelPresets.all.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.label) },
                                onClick = {
                                    modelMenuOpen = false
                                    onChanged(settings.copy(modelId = model.id))
                                },
                            )
                        }
                    }
                }
            }
            Text("Provider defaults · automatically compatible", color = Color.White.copy(alpha = .65f),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    try {
                        accessResult = when (val result = com.cyclone.mobile.ai.model.ModelQualificationRunner(context).qualify(selectedModel)) {
                            is com.cyclone.mobile.ai.model.ModelQualificationOutcome.Passed -> "${selectedModel.label}: ${if (result.cached) "recently verified" else "verified"} for this account."
                            is com.cyclone.mobile.ai.model.ModelQualificationOutcome.Failed -> "${selectedModel.label}: ${result.failure.userMessage} HTTP ${result.failure.httpStatus}. ${result.failure.providerMessage.orEmpty()}"
                        }
                    } catch (_: Exception) { accessResult = "Could not check model access. Try again." }
                    finally { checking = false }
                }
            }) { Text(if (checking) "Checking model…" else "Check model access") }
            accessResult?.let { Text(it, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodySmall) }

        }
    }
}

@Composable
private fun AuroraIconButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            disabledContainerColor = Color.White.copy(alpha = 0.04f),
            disabledContentColor = Color.White.copy(alpha = 0.32f),
        ),
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        content = content,
    )
}

@Composable
private fun AuroraStateContent(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = snapshot.state,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(tween(240)) + slideInVertically(tween(300)) { it / 4 })
                .togetherWith(fadeOut(tween(150)))
        },
        label = "AI state",
    ) { state ->
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            when (state) {
                OverlayChromeState.IDLE -> Unit
                OverlayChromeState.ANALYSIS -> {
                    AnimatedVisibility(snapshot.bullets.isNotEmpty()) {
                        Text(
                            snapshot.bullets.joinToString(separator = "\n"),
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (snapshot.bullets.isNotEmpty()) {
                        Button(
                            onClick = {
                                onAction(
                                    if (snapshot.analysisCta == OverlayAnalysisCta.COMMERCE) OverlayUserAction.COMMERCE
                                    else OverlayUserAction.CONFIRM,
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.92f),
                                contentColor = Color(0xFF102044),
                            ),
                            modifier = Modifier.height(40.dp),
                        ) { Text(OverlayCopy.primaryCta(snapshot.analysisCta)) }
                    }
                }
                OverlayChromeState.WORKING -> Text(
                    snapshot.statusMessage ?: OverlayCopy.WORKING_BODY,
                    color = Color.White.copy(alpha = 0.84f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OverlayChromeState.LIVE -> Text(
                    if (snapshot.userPaused) "Cyclone is paused while you take control."
                    else snapshot.statusMessage ?: OverlayCopy.STATUS,
                    color = Color.White.copy(alpha = 0.88f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OverlayChromeState.GATE -> {
                    Text(OverlayCopy.GATE, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { onAction(OverlayUserAction.GATE_CONFIRM) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF18234A)),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) { Text(OverlayCopy.CONFIRM) }
                }
                OverlayChromeState.DONE -> Text(
                    snapshot.statusMessage ?: "Task completed",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun AuroraComposer(
    snapshot: OverlayChromeSnapshot,
    onComposerChanged: (String) -> Unit,
    onRequestSubmitted: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var additions by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val submit = {
        if (snapshot.composerText.isNotBlank()) {
            onRequestSubmitted(snapshot.composerText)
            focusManager.clearFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { additions = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Add, "Add files or photo", tint = Color.White) }
                DropdownMenu(expanded = additions, onDismissRequest = { additions = false }) {
                    fun openAttachment(camera: Boolean) {
                        additions = false
                        context.startActivity(android.content.Intent(context, OverlayAttachmentActivity::class.java)
                            .putExtra("camera", camera).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    DropdownMenuItem(text = { Text("Add file or image") }, onClick = { openAttachment(false) })
                    DropdownMenuItem(text = { Text("Take a picture") }, onClick = { openAttachment(true) })
                    DropdownMenuItem(text = { Text("Share screen with Cyclone") }, onClick = {
                        additions = false
                        context.startActivity(android.content.Intent(context, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                    DropdownMenuItem(text = { Text("Background task") }, onClick = {
                        additions = false
                        context.startActivity(android.content.Intent(context, com.cyclone.mobile.runtime.background.WorkspaceActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                }
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Tune, "Settings", tint = Color.White) }
            BasicTextField(value = snapshot.composerText, onValueChange = onComposerChanged,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                cursorBrush = SolidColor(AuroraCyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 13.dp)
                    .semantics { contentDescription = OverlayCopy.COMPOSER },
                decorationBox = { field -> Box {
                    if (snapshot.composerText.isEmpty()) Text(OverlayCopy.COMPOSER, color = Color.White.copy(alpha = 0.6f))
                    field()
                } })
            IconButton(onClick = onVoiceInput, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.Mic, contentDescription = "Dictate request", tint = Color.White, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = submit, enabled = snapshot.composerText.isNotBlank(), modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send request", tint = if (snapshot.composerText.isNotBlank()) AuroraCyan else Color.Gray, modifier = Modifier.size(24.dp))
            }
        }
        snapshot.voiceMessage?.let { message ->
            Text(message, color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun stateLabel(snapshot: OverlayChromeSnapshot): String = when (snapshot.state) {
    OverlayChromeState.IDLE -> OverlayCopy.COMPOSER
    OverlayChromeState.ANALYSIS -> if (snapshot.voiceListening) OverlayCopy.LISTENING else OverlayCopy.ANALYSIS_TITLE
    OverlayChromeState.WORKING -> if (snapshot.userPaused) OverlayCopy.RESUME else snapshot.statusMessage ?: OverlayCopy.WORKING_TITLE
    OverlayChromeState.LIVE -> if (snapshot.userPaused) OverlayCopy.RESUME else snapshot.statusMessage ?: OverlayCopy.STATUS
    OverlayChromeState.GATE -> "Confirmation needed"
    OverlayChromeState.DONE -> "Ready"
}
