package com.cyclone.mobile.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

private val AuroraBlue = Color(0xFF4A8DFF)
private val AuroraCyan = Color(0xFF80E9FF)
private val AuroraViolet = Color(0xFF8568FF)
private val AuroraMagenta = Color(0xFFE56CFF)
private val AuroraInk = Color(0xFF060B18)

data class OverlayAiSettings(
    val modelId: String = OpenRouterModelPresets.DEFAULT.id,
    val reasoningEffort: String = "medium",
)

private val intelligenceLevels = listOf("low", "medium", "high", "max")

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
                Box(
                    modifier = modifier.padding(end = 18.dp, bottom = 18.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    IdleOrb(onActivate = { onAction(OverlayUserAction.ASK_CYCLONE) })
                }
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
private fun IdleOrb(onActivate: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var activating by remember { mutableStateOf(false) }

    fun activate() {
        if (activating) return
        activating = true
        scope.launch {
            delay(680)
            onActivate()
        }
    }

    val motion = rememberInfiniteTransition(label = "Idle orb glow")
    val rotation by motion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (activating) 850 else 5_800, easing = LinearEasing)),
        label = "Rainbow rotation",
    )
    val pulse by motion.animateFloat(
        initialValue = 0.94f,
        targetValue = if (activating) 1.16f else 1.04f,
        animationSpec = infiniteRepeatable(tween(if (activating) 360 else 1_700), RepeatMode.Reverse),
        label = "Glow pulse",
    )

    Box(
        modifier = Modifier
            .size(if (activating) 72.dp else 62.dp)
            .graphicsLayer { scaleX = pulse; scaleY = pulse }
            .semantics {
                contentDescription = "Cyclone AI. Triple tap or hold for 3 seconds."
                onClick("Open Cyclone AI mode") {
                    activate()
                    true
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val hold = scope.launch {
                            delay(3_000)
                            activate()
                        }
                        tryAwaitRelease()
                        hold.cancel()
                    },
                    onTap = {
                        val now = SystemClock.elapsedRealtime()
                        tapCount = if (now - lastTapAt <= 650L) tapCount + 1 else 1
                        lastTapAt = now
                        if (tapCount >= 3) {
                            tapCount = 0
                            activate()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer { rotationZ = rotation }) {
            drawCircle(
                brush = Brush.sweepGradient(
                    listOf(AuroraCyan, AuroraBlue, AuroraViolet, AuroraMagenta, AuroraCyan),
                    center,
                ),
                radius = size.minDimension * 0.47f,
                style = Stroke(width = if (activating) 7.dp.toPx() else 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Surface(
            shape = CircleShape,
            color = AuroraInk.copy(alpha = if (activating) 0.52f else 0.30f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
            shadowElevation = if (activating) 18.dp else 6.dp,
            modifier = Modifier.size(if (activating) 56.dp else 50.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(23.dp))
            }
        }
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
    LaunchedEffect(snapshot.state, snapshot.minimized) {
        if (snapshot.state == OverlayChromeState.DONE && !snapshot.minimized) {
            delay(5_500)
            onAction(OverlayUserAction.EXIT)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 224.dp, max = 360.dp)
            .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp)),
    ) {
        MovingAurora(Modifier.matchParentSize())
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AuroraControls(snapshot, onAction, onToggleSettings = { showAiSettings = !showAiSettings })
            AnimatedVisibility(showAiSettings) {
                QuickAiSettings(aiSettings, onAiSettingsChanged)
            }
            AuroraStateContent(snapshot, onAction, Modifier.weight(1f, fill = false))
            if (snapshot.state != OverlayChromeState.GATE && snapshot.state != OverlayChromeState.DONE) {
                AuroraComposer(snapshot, onComposerChanged, onRequestSubmitted, onVoiceInput)
            }
            if (snapshot.state == OverlayChromeState.GATE || snapshot.state == OverlayChromeState.ANALYSIS) {
                Text(
                    OverlayCopy.LEGAL,
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MovingAurora(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "Moving aurora")
    val phase by motion.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(7_600, easing = LinearEasing)),
        label = "Aurora phase",
    )
    val breathe by motion.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2_900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Aurora breathing",
    )

    Canvas(modifier) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.30f to AuroraInk.copy(alpha = 0.34f),
                1f to AuroraInk.copy(alpha = 0.88f),
            ),
        )
        val baseY = size.height * 0.86f
        val spread = size.width * 0.28f
        val wave = sin(phase) * spread
        drawGlow(
            center = Offset(size.width * 0.48f + wave * 0.34f, baseY - size.height * 0.08f * cos(phase)),
            radius = size.width * 0.43f * breathe,
            color = AuroraCyan,
            alpha = 0.43f,
        )
        drawGlow(
            center = Offset(size.width * 0.18f + wave * 0.22f, baseY + size.height * 0.04f * sin(phase * 1.4f)),
            radius = size.width * 0.42f,
            color = AuroraBlue,
            alpha = 0.50f,
        )
        drawGlow(
            center = Offset(size.width * 0.83f - wave * 0.26f, baseY - size.height * 0.03f * cos(phase * 1.2f)),
            radius = size.width * 0.40f,
            color = AuroraMagenta,
            alpha = 0.46f,
        )
        drawGlow(
            center = Offset(size.width * 0.65f + wave * 0.18f, size.height * 0.96f),
            radius = size.width * 0.34f,
            color = AuroraViolet,
            alpha = 0.42f,
        )
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(AuroraBlue.copy(alpha = 0f), AuroraCyan.copy(alpha = 0.8f), AuroraMagenta.copy(alpha = 0.66f), AuroraMagenta.copy(alpha = 0f)),
            ),
            start = Offset(0f, size.height - 1.5.dp.toPx()),
            end = Offset(size.width, size.height - 1.5.dp.toPx()),
            strokeWidth = 3.dp.toPx(),
        )
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
        AuroraIconButton(
            label = if (snapshot.userPaused) OverlayCopy.RESUME else OverlayCopy.PAUSE,
            onClick = { onAction(OverlayUserAction.TAKE_CONTROL) },
            enabled = snapshot.state == OverlayChromeState.ANALYSIS ||
                snapshot.state == OverlayChromeState.WORKING ||
                snapshot.state == OverlayChromeState.LIVE,
        ) {
            Icon(
                if (snapshot.userPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                OverlayCopy.AI_MODE,
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
        Spacer(Modifier.size(6.dp))
        AuroraIconButton(OverlayCopy.MINIMIZE, { onAction(OverlayUserAction.MINIMIZE) }) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.size(6.dp))
        AuroraIconButton(OverlayCopy.EXIT, { onAction(OverlayUserAction.EXIT) }) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun QuickAiSettings(
    settings: OverlayAiSettings,
    onChanged: (OverlayAiSettings) -> Unit,
) {
    var modelMenuOpen by remember { mutableStateOf(false) }
    val selectedModel = OpenRouterModelPresets.byId(settings.modelId)
    val levelIndex = intelligenceLevels.indexOf(settings.reasoningEffort).coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fast", color = Color.White.copy(alpha = 0.58f), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = levelIndex.toFloat(),
                    onValueChange = { value ->
                        onChanged(settings.copy(reasoningEffort = intelligenceLevels[value.roundToInt().coerceIn(0, 3)]))
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(
                    settings.reasoningEffort.replaceFirstChar { it.uppercase() },
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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
        modifier = Modifier.size(38.dp).semantics { contentDescription = label },
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
                    OverlayCopy.DONE,
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
) {
    val focusManager = LocalFocusManager.current
    val submit = {
        if (snapshot.composerText.isNotBlank()) {
            onRequestSubmitted(snapshot.composerText)
            focusManager.clearFocus()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = snapshot.composerText,
                onValueChange = onComposerChanged,
                singleLine = true,
                placeholder = { Text(OverlayCopy.COMPOSER) },
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Black.copy(alpha = 0.22f),
                    unfocusedContainerColor = Color.Black.copy(alpha = 0.18f),
                    focusedBorderColor = AuroraCyan.copy(alpha = 0.74f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.20f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = AuroraCyan,
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.55f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.48f),
                ),
                modifier = Modifier.weight(1f).height(52.dp),
            )
            AuroraIconButton(
                label = if (snapshot.voiceListening) OverlayCopy.LISTENING else OverlayCopy.VOICE,
                onClick = onVoiceInput,
            ) {
                Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(21.dp))
            }
            AuroraIconButton(
                label = OverlayCopy.SEND_REQUEST,
                onClick = submit,
                enabled = snapshot.composerText.isNotBlank(),
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(20.dp))
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
