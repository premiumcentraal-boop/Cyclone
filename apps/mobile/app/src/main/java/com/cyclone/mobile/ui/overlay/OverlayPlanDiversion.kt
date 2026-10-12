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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
    val title: String get() = if (steered) "You changed the plan" else "Changed course"

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

/** The whole diversion block, in the card's working state: tucked plan, the branch mark, the new steps. */
@Composable
internal fun PlanDiversionBlock(diversion: PlanDiversion, modifier: Modifier = Modifier, maxNext: Int = 4) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TuckedPlan(diversion)
        DivertMark(diversion)
        NextSteps(diversion.next.take(maxNext), more = (diversion.next.size - maxNext).coerceAtLeast(0))
    }
}

/** One line for the plan Cyclone left, like a folded thought; tap to see it, faded, and tap again to fold it. */
@Composable
private fun TuckedPlan(diversion: PlanDiversion) {
    var open by rememberSaveable(diversion.version) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220), label = "Tucked chevron")
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.045f))
            .clickable(role = Role.Button, onClickLabel = if (open) "Fold the earlier plan" else "Show the earlier plan") { open = !open }
            .semantics { stateDescription = if (open) "Shown" else "Folded" }
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoldedPlanGlyph()
            Text(diversion.tucked, color = GlassMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            diversion.dropped.firstOrNull()?.let {
                Text(it, Modifier.weight(1f), color = GlassDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textDecoration = TextDecoration.LineThrough)
            } ?: Box(Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn), tint = GlassDim)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(top = 8.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val shown = diversion.earlier.ifEmpty { diversion.dropped.map { MindPlanStep(it, MindPlanStep.DROPPED) } }
                shown.forEach { step ->
                    val left = step.text in diversion.dropped || step.dropped
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                            if (step.status == "done") Icon(Icons.Rounded.Check, null, Modifier.size(13.dp), tint = GlassDim)
                            else Box(Modifier.size(5.dp).clip(CircleShape).background(GlassDim.copy(alpha = 0.7f)))
                        }
                        Text(step.text, color = GlassDim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textDecoration = if (left) TextDecoration.LineThrough else null,
                            modifier = Modifier.semantics { if (left) contentDescription = "Let go: ${step.text}" })
                    }
                }
            }
        }
    }
}

/** Three short stacked lines, the top one longer: a plan folded away. */
@Composable
private fun FoldedPlanGlyph() {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val stroke = 1.6.dp.toPx()
        listOf(0.22f to 1f, 0.5f to 0.72f, 0.78f to 0.48f).forEach { (y, len) ->
            drawLine(GlassDim, Offset(w * 0.08f, size.height * y), Offset(w * (0.08f + 0.84f * len), size.height * y), stroke, StrokeCap.Round)
        }
    }
}

/**
 * The branch mark and its words: a small glowing circle where the old line stops and a new one curves away with an
 * arrow, drawn in once when the change arrives; "Changed course" (or "You changed the plan") and the reason.
 */
@Composable
private fun DivertMark(diversion: PlanDiversion) {
    val draw = remember(diversion.version) { Animatable(0f) }
    LaunchedEffect(diversion.version) { draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = diversion.title + (diversion.reason?.let { ". $it" } ?: "")
        },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BranchGlyph(draw.value, warm = !diversion.steered)
        Column(Modifier.weight(1f).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(diversion.title, color = if (diversion.steered) Branch else BranchWarm, fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            diversion.reason?.let {
                Text(it, color = GlassMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The fork: a faded, dashed trunk (the plan let go), and a bright branch that curves off to an arrowhead. */
@Composable
private fun BranchGlyph(progress: Float, warm: Boolean) {
    val tint = if (warm) BranchWarm else Branch
    Box(
        Modifier.size(32.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.30f), Branch.copy(alpha = 0.12f))))
            .border(0.8.dp, tint.copy(alpha = 0.45f * progress + 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(19.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 1.8.dp.toPx()
            // The old line: straight up, faded, and stopping short where it was let go.
            drawLine(Color.White.copy(alpha = 0.32f), Offset(w * 0.32f, h * 0.95f), Offset(w * 0.32f, h * 0.10f), stroke,
                StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.2f * stroke, 1.6f * stroke)))
            // The new route: it leaves the trunk and curves up and right, drawn in as [progress] runs.
            val branch = Path().apply {
                moveTo(w * 0.32f, h * 0.72f)
                cubicTo(w * 0.32f, h * 0.42f, w * 0.60f, h * 0.50f, w * 0.80f, h * 0.24f)
            }
            val measure = PathMeasure().apply { setPath(branch, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * progress, part, true)
            drawPath(part, Color.White, style = Stroke(stroke * 1.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (progress > 0.92f) {
                val tip = Offset(w * 0.80f, h * 0.24f)
                val a = 3.6.dp.toPx()
                drawLine(Color.White, tip, Offset(tip.x - a, tip.y + a * 0.15f), stroke, StrokeCap.Round)
                drawLine(Color.White, tip, Offset(tip.x - a * 0.1f, tip.y + a), stroke, StrokeCap.Round)
            }
        }
    }
}

/** The new route: each step on one lit rail that runs from the branch mark down, the current one turning. */
@Composable
private fun NextSteps(steps: List<MindPlanStep>, more: Int) {
    if (steps.isEmpty()) return
    val current = steps.indexOfFirst { it.status == "doing" }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.status == "todo" }
    Column(
        Modifier.fillMaxWidth().padding(start = 7.dp).drawBehind {
            // The rail: centred under the branch mark's circle (16 dp from the left), fading towards the end.
            val x = 9.dp.toPx()
            drawLine(
                Brush.verticalGradient(listOf(BranchWarm.copy(alpha = 0.55f), Branch.copy(alpha = 0.45f), Branch.copy(alpha = 0f))),
                Offset(x, -6.dp.toPx()), Offset(x, size.height), 1.4.dp.toPx(), StrokeCap.Round,
            )
        },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        steps.forEachIndexed { i, step ->
            val state = when {
                step.status == "done" -> 0
                i == current -> 1
                else -> 2
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        0 -> Box(Modifier.size(16.dp).clip(CircleShape).background(Branch.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
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
        if (more > 0) Text("+$more more", color = GlassDim, fontSize = 12.5.sp, modifier = Modifier.padding(start = 28.dp, top = 2.dp))
    }
}
