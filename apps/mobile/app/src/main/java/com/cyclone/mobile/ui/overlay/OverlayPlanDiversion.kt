package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.mission.Mission

/**
 * Plan 38 §4, redesigned: a change of plan stays inside the working card. The plan Cyclone left folds into one quiet
 * line ("Earlier plan · 4 steps", tap to look), a small branch mark says the course changed and why, and the new steps
 * follow on a lit rail. Nothing shows while the plan has not changed.
 */
internal data class PlanDiversion(
    val version: Int,
    /** The owner changed the task (a steer), rather than Cyclone changing course on its own. */
    val steered: Boolean,
    /** Why Cyclone changed course, once, in its own words; null for a steer or when none was given. */
    val reason: String?,
    /** The plan before the change, as it stood: done steps ticked, the rest as they were. */
    val earlier: List<MindPlanStep>,
    /** The steps that were let go. */
    val dropped: List<String>,
    /** The plan from here on: the new route and anything still to do. */
    val next: List<MindPlanStep>,
    /** Steps already done before the change. */
    val done: Int,
) {
    val title: String get() = "Plan diverted"

    /** The tucked line: what the earlier plan was, in a few words. */
    val tucked: String get() {
        val count = earlier.size.takeIf { it > 0 } ?: (done + dropped.size)
        return "Earlier plan" + if (count > 0) " · $count ${if (count == 1) "step" else "steps"}" else ""
    }

    companion object {
        private const val STEER_NOTE = "You changed this"

        /** Pure: the diversion a mission's plan shows, or null when the plan never changed. */
        fun of(mission: Mission): PlanDiversion? {
            if (mission.planVersion <= 1 || mission.plan.isEmpty()) return null
            val plan = mission.plan
            val firstBranch = plan.firstOrNull { it.branch }
            val note = firstBranch?.note?.trim()?.takeIf { it.isNotBlank() }
            val steered = mission.planLabel == STEER_NOTE || note == STEER_NOTE
            val kept = plan.filterNot { it.dropped }
            val from = kept.indexOfFirst { it.status != "done" && it.status != "skipped" }.let { if (it < 0) kept.size else it }
            return PlanDiversion(
                version = mission.planVersion,
                steered = steered,
                reason = note?.takeUnless { it == STEER_NOTE },
                earlier = mission.planHistory.lastOrNull().orEmpty().filterNot { it.dropped },
                dropped = plan.filter { it.dropped }.map { it.text },
                next = kept.drop(from),
                done = from,
            )
        }
    }
}

private val Branch = Color(0xFF83DBD7)
private val BranchWarm = Color(0xFFE9C78B)

/** Width of the marker column every row shares, so the fork, the old steps and the new steps sit on one line. */
private val Lane = 30.dp

/** Height of the first row: the switch glyph fills it, and the rail starts where it ends. */
private val ForkHeight = 46.dp

/**
 * The whole diversion as one timeline. The first row is the fork (no frame), "Changed course" and the plan it left,
 * folded to one struck line with a chevron; tap it to unfold the earlier plan, faded, right there. The reason follows
 * in the same column, and the new steps hang from the fork on one lit rail, the current one turning.
 */
@Composable
internal fun PlanDiversionBlock(diversion: PlanDiversion, modifier: Modifier = Modifier, maxNext: Int = 4, startOpen: Boolean = false) {
    var open by rememberSaveable(diversion.version) { mutableStateOf(startOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "Earlier plan chevron")
    val draw = remember(diversion.version) { Animatable(0f) }
    LaunchedEffect(diversion.version) { draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val tint = BranchWarm
    val next = diversion.next.take(maxNext)
    val more = (diversion.next.size - maxNext).coerceAtLeast(0)
    val current = next.indexOfFirst { it.status == "doing" }.takeIf { it >= 0 } ?: next.indexOfFirst { it.status == "todo" }

    Column(
        modifier.fillMaxWidth().drawBehind {
            // One rail for the whole block: it leaves the fork's branch and runs down past the last step, fading out.
            val x = Lane.toPx() / 2
            drawLine(
                Brush.verticalGradient(listOf(tint.copy(alpha = 0.6f), Branch.copy(alpha = 0.4f), Branch.copy(alpha = 0f)),
                    startY = ForkHeight.toPx(), endY = size.height),
                Offset(x, ForkHeight.toPx()), Offset(x, size.height), 1.4.dp.toPx(), StrokeCap.Round,
            )
        },
    ) {
        // The fork and the plan it left, on one line.
        Row(
            Modifier.fillMaxWidth().height(ForkHeight).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = if (open) "Fold the earlier plan" else "Show the earlier plan") { open = !open }
                .semantics(mergeDescendants = true) {
                    contentDescription = diversion.title + (diversion.dropped.firstOrNull()?.let { ". Let go: $it" } ?: "")
                    stateDescription = if (open) "Earlier plan shown" else "Earlier plan folded"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ForkGlyph(draw.value, tint)
            Text(diversion.title, color = tint, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            Text(diversion.dropped.firstOrNull() ?: diversion.tucked, Modifier.weight(1f), color = GlassDim, fontSize = 13.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (diversion.dropped.isNotEmpty()) TextDecoration.LineThrough else null)
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn), tint = GlassDim)
        }
        diversion.reason?.let {
            Text(it, Modifier.padding(start = Lane + 10.dp, bottom = 4.dp), color = GlassMuted, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // The earlier plan, unfolded in place: faded, in the same column as the words above it.
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.padding(start = Lane + 10.dp, top = 4.dp, bottom = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.035f)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val shown = diversion.earlier.ifEmpty { diversion.dropped.map { MindPlanStep(it, MindPlanStep.DROPPED) } }
                shown.forEach { step ->
                    val left = step.dropped || step.text in diversion.dropped
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) { if (left) contentDescription = "Let go: ${step.text}" }) {
                        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                            when {
                                step.status == "done" -> Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = GlassDim)
                                left -> Icon(Icons.Rounded.Close, null, Modifier.size(12.dp), tint = GlassDim.copy(alpha = 0.8f))
                                else -> Box(Modifier.size(5.dp).clip(CircleShape).background(GlassDim.copy(alpha = 0.7f)))
                            }
                        }
                        Text(step.text, color = GlassDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (left) TextDecoration.LineThrough else null)
                    }
                }
            }
        }
        // The new route, hanging from the fork on the rail.
        next.forEachIndexed { i, step ->
            val state = when {
                step.status == "done" -> 0
                i == current -> 1
                else -> 2
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(Lane), contentAlignment = Alignment.Center) {
                    when (state) {
                        0 -> Box(Modifier.size(16.dp).clip(CircleShape).background(Color(0xFF0B2226)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = Branch)
                        }
                        1 -> Box(Modifier.size(18.dp).clip(CircleShape).background(Color(0xFF0B2226)), contentAlignment = Alignment.Center) { GlassSpinner(13) }
                        else -> Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF0B2226))
                            .border(1.3.dp, GlassMuted.copy(alpha = 0.55f), CircleShape))
                    }
                }
                Text(step.text, Modifier.weight(1f), color = if (state == 1) GlassInk else if (state == 0) GlassDim else GlassMuted,
                    fontSize = 14.5.sp, fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (more > 0) Text("+$more more", color = GlassDim, fontSize = 12.5.sp, modifier = Modifier.padding(start = Lane + 10.dp, top = 2.dp))
    }
}

/**
 * The switch, like points on a railway: the old track comes in from the top right and runs on, faded and dashed, while
 * the bright new track switches off it, curves left and down and ends in an arrow pointing into the steps below. It
 * draws itself in once as [progress] runs; the arrow's tip meets the rail.
 */
@Composable
private fun ForkGlyph(progress: Float, tint: Color) {
    Canvas(Modifier.size(Lane, ForkHeight)) {
        val w = size.width
        val h = size.height
        val stroke = 1.9.dp.toPx()
        val right = w * 0.88f
        val rail = w / 2
        val split = h * 0.16f
        // The track it was on: in from the top, then on past the switch, faded and dashed.
        drawLine(tint.copy(alpha = 0.55f), Offset(right, 0f), Offset(right, split), stroke, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.34f), Offset(right, split), Offset(right, h * 0.78f), stroke, StrokeCap.Round,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.2f * stroke, 1.8f * stroke)))
        // The new track: leaves at the switch, curves left onto the rail and runs down.
        val track = Path().apply {
            moveTo(right, split)
            cubicTo(right, h * 0.52f, rail, h * 0.42f, rail, h * 0.80f)
            lineTo(rail, h - stroke)
        }
        val measure = PathMeasure().apply { setPath(track, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * progress, part, true)
        drawPath(part, tint, style = Stroke(stroke * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (progress > 0.92f) {
            val tip = Offset(rail, h - stroke)
            val a = 4.2.dp.toPx()
            drawLine(tint, tip, Offset(tip.x - a, tip.y - a), stroke, StrokeCap.Round)
            drawLine(tint, tip, Offset(tip.x + a, tip.y - a), stroke, StrokeCap.Round)
        }
    }
}
