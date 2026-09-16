package com.cyclone.mobile.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ui.v32.LocalCycloneInsideLiquidHost
import com.cyclone.mobile.ui.v32.LocalCycloneLiquidBackdrop
import com.cyclone.mobile.ui.v32.LocalCycloneOverlayChrome
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val OverlayGlass = Color(0xFF1C1C1E).copy(alpha = 0.90f)
private val OverlayGlassStrong = Color(0xFF1C1C1E).copy(alpha = 0.94f)
private val OverlayGlassInner = Color.White.copy(alpha = 0.10f)
private val OverlayText = Color(0xFFF5F5F7)
private val OverlaySecondaryText = Color(0xFFD1D1D6)
private val OverlayBlue = Color(0xFF64B5FF)

private val OverlayDarkScheme = darkColorScheme(
    primary = OverlayBlue,
    onPrimary = Color.White,
    onSurface = OverlayText,
    onSurfaceVariant = OverlaySecondaryText,
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
)

/**
 * Overlay-only glass. Android cannot sample pixels owned by another app into a Compose backdrop, so
 * the system overlay uses Kyant refraction over a dense charcoal fill — readable white type on
 * Apple Regular glass, never a clear window onto the launcher.
 */
@Composable
internal fun OverlayAppleGlass(
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
    strong: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    val shape = RoundedCornerShape(cornerRadius)
    val surface = if (strong) OverlayGlassStrong else OverlayGlass
    val glassModifier = if (backdrop != null) {
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(16f.dp.toPx())
                lens(12f.dp.toPx(), 24f.dp.toPx(), chromaticAberration = true)
            },
            onDrawSurface = { drawRect(surface) },
        )
    } else {
        modifier.background(surface, shape)
    }
    CompositionLocalProvider(
        LocalCycloneInsideLiquidHost provides true,
        LocalCycloneOverlayChrome provides true,
    ) {
        MaterialTheme(colorScheme = OverlayDarkScheme) {
            Box(
                glassModifier,
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

@Composable
internal fun OverlayAppleComposerBar(
    text: String,
    onTextChanged: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    placeholder: String,
    menuOpen: Boolean,
    voiceListening: Boolean,
    working: Boolean,
    paused: Boolean,
    taskKey: String,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onMenu: () -> Unit,
    onDictate: () -> Unit,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayAppleGlass(
        modifier = modifier.fillMaxWidth().height(66.dp),
        cornerRadius = 33.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().padding(start = 7.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OverlayAppleCircleAction(
                onClick = onMenu,
                selected = menuOpen,
                description = if (menuOpen) "Close tools" else "Open tools",
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = OverlayText,
                    modifier = Modifier.size(28.dp),
                )
            }

            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                singleLine = true,
                textStyle = TextStyle(color = OverlayText, fontSize = 17.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(OverlayBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!working && text.isNotBlank()) onPrimary() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 10.dp, vertical = 15.dp)
                    .semantics { contentDescription = "Ask Cyclone" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                placeholder,
                                color = OverlaySecondaryText.copy(alpha = if (working) .56f else .78f),
                                fontSize = 17.sp,
                            )
                        }
                        field()
                    }
                },
            )

            run {
                Box(
                    Modifier
                        .size(46.dp)
                        .clickable(enabled = !working, role = Role.Button, onClick = onDictate)
                        .semantics { contentDescription = "Dictate request" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = if (working) OverlaySecondaryText else if (voiceListening) OverlayBlue else OverlayText,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            OverlayRequestAction(working, paused, taskKey, text.isNotBlank(), onPrimary, onPause, onStop)

        }
    }
}

@Composable
private fun OverlayAppleCircleAction(
    onClick: () -> Unit,
    selected: Boolean,
    description: String,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .size(50.dp)
            .background(if (selected) Color.White.copy(alpha = .14f) else OverlayGlassInner, CircleShape)
            .border(0.7.dp, Color.White.copy(alpha = if (selected) .18f else .08f), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun OverlayAppleToolsMenu(
    sharingActive: Boolean,
    onCamera: () -> Unit,
    onFiles: () -> Unit,
    onShareScreen: () -> Unit,
    onCrossAppShare: () -> Unit,
    onModelAndIntelligence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayAppleGlass(
        modifier = modifier.widthIn(max = 310.dp),
        cornerRadius = 30.dp,
        strong = true,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OverlayAppleMenuRow(Icons.Rounded.CameraAlt, "Camera", onCamera)
            OverlayAppleMenuRow(Icons.Rounded.AttachFile, "Files & photos", onFiles)
            OverlayAppleMenuRow(Icons.Rounded.ScreenShare, "Share screen", onShareScreen, enabled = !sharingActive)
            OverlayAppleMenuRow(Icons.Rounded.Apps, "Cross-app share", onCrossAppShare, enabled = !sharingActive)
            OverlayAppleMenuRow(Icons.Rounded.Tune, "Model & intelligence", onModelAndIntelligence)
        }
    }
}

@Composable
private fun OverlayAppleMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val alpha = if (enabled) 1f else .42f
    Row(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(OverlayGlassInner.copy(alpha = OverlayGlassInner.alpha * alpha), CircleShape)
                .border(0.7.dp, Color.White.copy(alpha = .08f * alpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = OverlayText.copy(alpha = alpha),
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            label,
            color = OverlayText.copy(alpha = alpha),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun OverlayAppleStatusPill(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    OverlayAppleGlass(
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp),
        cornerRadius = 23.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text, color = OverlayText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel,
                    color = OverlayBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
                )
            }
        }
    }
}

@Composable
private fun OverlayRequestAction(
    working: Boolean, paused: Boolean, taskKey: String, canSend: Boolean,
    onSend: () -> Unit, onPauseOrResume: () -> Unit, onStop: () -> Unit,
) {
    val gesture = remember(taskKey, working) { ComposerStopGesture() }
    val pauseState by rememberUpdatedState(paused)
    val pauseAction by rememberUpdatedState(onPauseOrResume)
    val stopAction by rememberUpdatedState(onStop)
    var progress by remember(taskKey, working) { mutableFloatStateOf(0f) }
    LaunchedEffect(gesture) {
        while (working) {
            withFrameNanos { progress = gesture.progress(android.os.SystemClock.uptimeMillis()) }
        }
    }
    fun accessibleTap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (gesture.armed(now)) { gesture.reset(); stopAction() }
        else if (pauseState) { gesture.reset(); pauseAction() }
        else { gesture.press(now); gesture.release(now); pauseAction() }
    }
    val action = if (working) Modifier
        .pointerInput(gesture) {
            detectTapGestures(onPress = {
                coroutineScope {
                    val wasPaused = pauseState
                    val wasArmed = gesture.armed(android.os.SystemClock.uptimeMillis())
                    gesture.press(android.os.SystemClock.uptimeMillis())
                    if (!wasPaused) pauseAction()
                    val hold = launch {
                        delay(ComposerStopGesture.HOLD_MS)
                        if (gesture.hold(android.os.SystemClock.uptimeMillis())) stopAction()
                    }
                    try {
                        if (tryAwaitRelease()) {
                            when (gesture.release(android.os.SystemClock.uptimeMillis())) {
                                ComposerStopGesture.Release.STOP -> stopAction()
                                ComposerStopGesture.Release.FIRST_TAP -> if (wasPaused && !wasArmed) {
                                    gesture.reset(); pauseAction()
                                }
                                else -> Unit
                            }
                        } else gesture.cancel()
                    } finally { hold.cancel() }
                }
            })
        }
        .semantics {
            contentDescription = if (paused) "Resume request. Hold two seconds or tap twice to stop" else "Pause request. Hold two seconds or tap twice to stop"
            onClick { accessibleTap(); true }
            customActions = listOf(CustomAccessibilityAction("Stop request") { gesture.reset(); stopAction(); true })
        }
    else Modifier.clickable(enabled = canSend, role = Role.Button, onClick = onSend)
        .semantics { contentDescription = "Send request" }
    Box(Modifier.size(50.dp).background(Color.White.copy(alpha = if (working || canSend) 1f else .35f), CircleShape)
        .then(action), contentAlignment = Alignment.Center) {
        Icon(if (!working) Icons.Rounded.ArrowUpward else if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
            contentDescription = null, tint = Color(0xFF111216), modifier = Modifier.size(27.dp))
        if (progress > 0f) Canvas(Modifier.size(48.dp)) {
            drawArc(OverlayBlue, -90f, progress * 360f, false,
                style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
