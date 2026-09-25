package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.cyclone.mobile.ui.v32.CycloneConversationPanel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.SignatureMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object SignatureDrawerGeometry {
    /** Reserve the system edges and keyboard before measuring the persistent composer. */
    fun availableHeight(screenHeight: Int, keyboardHeight: Int, topClearance: Int = 48,
        restingBottom: Int = 50, keyboardGap: Int = 8): Int =
        (screenHeight - topClearance - maxOf(restingBottom, keyboardHeight + keyboardGap)).coerceAtLeast(0)

    fun visibleHeight(fullHeight: Int, reveal: Float): Int =
        (fullHeight.coerceAtLeast(0) * reveal.coerceIn(0f, 1f)).roundToInt()

    fun revealAfterDrag(start: Float, dragPx: Float, heightPx: Float): Float =
        (start - dragPx / heightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
}

/** A single bottom-anchored composer survives every expanded/minimized transition. */
@Composable
internal fun SignatureOverlayDrawer(
    expanded: Boolean,
    minimized: Boolean,
    onCollapse: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    /** Cap for the work panel above the composer; it scrolls, anchored to its newest (bottom) content. */
    upperMaxHeight: Dp = Dp.Unspecified,
    composer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val collapse by rememberUpdatedState(onCollapse)
    val expand by rememberUpdatedState(onExpand)
    var reveal by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    var fullHeight by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }

    fun settle(target: Float, after: () -> Unit = {}) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(reveal, target, animationSpec = spring(dampingRatio = 1f, stiffness = 420f)) { value, _ ->
                reveal = value.coerceIn(0f, 1f)
            }
            after()
        }
    }
    LaunchedEffect(expanded) { settle(if (expanded) 1f else 0f) }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // The handle rides on top of the work panel. When the panel is collapsed it has no height,
        // so the same handle then sits directly above the Ask bar.
        Box(
            Modifier.fillMaxWidth().height(24.dp)
                .semantics {
                    contentDescription = if (minimized) "Drag up to expand or down to hide Cyclone chat"
                    else "Drag down or tap to minimize Cyclone chat"
                }
                .clickable(role = Role.Button) {
                    if (minimized) expand() else settle(0f) { collapse() }
                }
                .pointerInput(minimized, expanded, threshold) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            drag = 0f
                            dragStart = reveal
                        },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            drag += amount
                            reveal = SignatureDrawerGeometry.revealAfterDrag(dragStart, drag, fullHeight)
                        },
                        onDragCancel = { settle(if (expanded) 1f else 0f) },
                        onDragEnd = {
                            when {
                                drag >= threshold -> settle(0f) { collapse() }
                                minimized && drag <= -threshold * .6f -> expand()
                                else -> settle(if (expanded) 1f else 0f)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(32.dp, 3.dp).background(SignatureMuted.copy(alpha = .38f), CircleShape))
        }
        // Clip only the upper content. The composer below is never translated, faded,
        // reparented, or clipped by the drawer's animated bounds.
        Box(
            Modifier.weight(1f, fill = false).fillMaxWidth()
                .then(if (upperMaxHeight != Dp.Unspecified) Modifier.heightIn(max = upperMaxHeight) else Modifier)
                .clip(RoundedCornerShape(30.dp))
                .then(if (reveal == 0f) Modifier.clearAndSetSemantics {} else Modifier)
                .layout { measurable, constraints ->
                    val measured = measurable.measure(constraints.copy(minHeight = 0))
                    fullHeight = measured.height.toFloat().coerceAtLeast(1f)
                    val visible = SignatureDrawerGeometry.visibleHeight(measured.height, reveal)
                    layout(measured.width, visible) {
                        measured.placeRelative(0, visible - measured.height)
                    }
                },
        ) {
            CycloneConversationPanel(Modifier.fillMaxWidth()) {
                Column(
                    // Reverse scrolling starts at the bottom: the live work card is always in view and
                    // earlier messages (the first prompt) are one scroll up.
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), reverseScrolling = true),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
        composer()
    }
}
