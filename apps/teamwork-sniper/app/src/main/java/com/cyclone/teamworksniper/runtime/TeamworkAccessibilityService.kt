package com.cyclone.teamworksniper.runtime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.teamworksniper.ai.AiDecisionPolicy
import com.cyclone.teamworksniper.ai.AiDecisionTrace
import com.cyclone.teamworksniper.ai.OpenRouterAdvisor
import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.ActivityLogStore
import com.cyclone.teamworksniper.data.OpenShift
import com.cyclone.teamworksniper.data.RuleMatch
import com.cyclone.teamworksniper.data.RuleType
import com.cyclone.teamworksniper.data.RuleStore
import com.cyclone.teamworksniper.data.SettingsStore
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.TriggerEvent
import com.cyclone.teamworksniper.data.UiMapStore
import com.cyclone.teamworksniper.rules.ExecutionMode
import com.cyclone.teamworksniper.rules.RuleEngine
import com.cyclone.teamworksniper.rules.SafetyGate
import com.cyclone.teamworksniper.teamwork.SemanticNode
import com.cyclone.teamworksniper.teamwork.TeamworkParser
import com.cyclone.teamworksniper.teamwork.flatten
import com.cyclone.teamworksniper.ui.overlay.TeamworkOverlayController
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class TeamworkAccessibilityService : AccessibilityService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val busy = AtomicBoolean(false)
    private var active: TriggerEvent? = null
    private var queued: TriggerEvent? = null

    private val rules by lazy { RuleStore(this) }
    private val settings by lazy { SettingsStore(this) }
    private val log by lazy { ActivityLogStore(this) }
    private val uiMap by lazy { UiMapStore(this) }
    private val ai by lazy { OpenRouterAdvisor(this) }
    private val overlay by lazy { TeamworkOverlayController(this) { rootInActiveWindow } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SniperCoordinator.attach(this)
        overlay.start()
    }

    override fun onDestroy() {
        SniperCoordinator.detach(this)
        overlay.dispose()
        job.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        overlay.onAccessibilityEvent(event)
        if (event?.packageName?.toString() != TeamworkLauncher.PACKAGE) return
        SniperCoordinator.current()?.let(::requestEvaluation)
    }

    @Synchronized
    fun requestEvaluation(trigger: TriggerEvent) {
        if (!busy.compareAndSet(false, true)) {
            if (sameTrigger(active, trigger) || sameTrigger(queued, trigger)) return
            queued = trigger
            return
        }
        active = trigger
        scope.launch {
            try {
                evaluate(trigger)
            } finally {
                SniperCoordinator.consume(trigger)
                active = null
                busy.set(false)
                queued?.also {
                    queued = null
                    requestEvaluation(it)
                }
            }
        }
    }

    private fun sameTrigger(first: TriggerEvent?, second: TriggerEvent) =
        first?.source == second.source &&
            first.wallClockEpochMs == second.wallClockEpochMs &&
            first.notificationText == second.notificationText

    private suspend fun evaluate(trigger: TriggerEvent) {
        val started = SystemClock.elapsedRealtime()
        val parser = TeamworkParser(LocalDate.now())
        val currentRules = rules.load()
        val directDetail = awaitOpenDetailCandidate(parser)
        if (directDetail != null) {
            val evaluation = RuleEngine(LocalDate.now()).evaluate(currentRules, listOf(directDetail))
            val mode = SafetyGate.decide(settings.load(), evaluation)
            val match = AiDecisionPolicy.deterministic(evaluation.matches).firstOrNull()
            val claimStarted = SystemClock.elapsedRealtime()
            val outcome = if (mode == ExecutionMode.CLAIM && match != null) {
                confirmAndVerify(directDetail, parser)
            } else null
            record(
                trigger = trigger,
                decision = when (mode) {
                    ExecutionMode.CLAIM -> "CLAIM_FLOW"
                    ExecutionMode.DRY_RUN -> "WOULD_CLAIM"
                    ExecutionMode.NO_ACTION -> if (evaluation.matches.isEmpty()) "MISS" else "DISABLED"
                },
                shifts = listOf(directDetail),
                evaluated = evaluation.evaluatedRuleIds,
                attempted = outcome != null,
                result = outcome?.first,
                verify = outcome?.third,
                failure = outcome?.fourth,
                open = SystemClock.elapsedRealtime() - trigger.elapsedRealtimeMs,
                compare = SystemClock.elapsedRealtime() - trigger.elapsedRealtimeMs,
                evaluation = SystemClock.elapsedRealtime() - started,
                claim = outcome?.let { SystemClock.elapsedRealtime() - claimStarted },
                engine = "DETERMINISTIC_DETAIL",
                aiNote = "Notification/detail fast path; AI not invoked",
            )
            return
        }
        val root = awaitShiftRoot(parser)
        if (root == null) {
            record(
                trigger = trigger,
                decision = "NO_ACTION",
                shifts = emptyList(),
                evaluated = emptyList(),
                attempted = false,
                result = null,
                verify = null,
                failure = "Teamwork accessibility root unavailable; launch=" + trigger.launchOutcome,
            )
            return
        }
        root.recycle()

        val preferredDate = parser.parseDate(trigger.notificationText.orEmpty())
            ?: currentRules.asSequence().filter { it.enabled }.flatMap { it.dates.asSequence() }.minOrNull()
        if (preferredDate != null && !navigateToDate(preferredDate)) {
            record(
                trigger = trigger,
                decision = "NO_ACTION",
                shifts = emptyList(),
                evaluated = currentRules.filter { it.enabled }.map { it.id },
                attempted = false,
                result = null,
                verify = null,
                failure = "Could not navigate the Teamwork calendar to $preferredDate",
            )
            return
        }

        val openLatency = SystemClock.elapsedRealtime() - trigger.elapsedRealtimeMs
        val quickRoot = awaitRoot()
        val quick = if (quickRoot == null) emptyList() else try {
            parser.parse(AccessibilitySemanticTree.snapshot(quickRoot)).shifts.map { it.shift }
        } finally {
            quickRoot.recycle()
        }
        RuleEngine(LocalDate.now()).evaluate(currentRules, quick)
        val compareLatency = SystemClock.elapsedRealtime() - trigger.elapsedRealtimeMs

        val all = scan(parser, reset = true)
        val evaluation = RuleEngine(LocalDate.now()).evaluate(currentRules, all)
        val mode = SafetyGate.decide(settings.load(), evaluation)
        val decisionTrace = if (mode == ExecutionMode.CLAIM) {
            ai.prioritize(evaluation.matches, trigger)
        } else {
            AiDecisionTrace(
                orderedMatches = AiDecisionPolicy.deterministic(evaluation.matches),
                engine = "DETERMINISTIC",
                note = if (mode == ExecutionMode.DRY_RUN) "Disarmed mode; AI not invoked" else null,
            )
        }

        var attempted = false
        var result: String? = null
        var verify: String? = null
        var failure: String? = null
        var claimDuration: Long? = null

        if (mode == ExecutionMode.CLAIM) {
            attempted = true
            val claimStarted = SystemClock.elapsedRealtime()
            val outcome = execute(decisionTrace.orderedMatches, currentRules, parser)
            claimDuration = SystemClock.elapsedRealtime() - claimStarted
            result = outcome.first
            verify = outcome.second
            failure = outcome.third
        }

        record(
            trigger = trigger,
            decision = when (mode) {
                ExecutionMode.NO_ACTION -> if (evaluation.matches.isEmpty()) "MISS" else "DISABLED"
                ExecutionMode.DRY_RUN -> "WOULD_CLAIM"
                ExecutionMode.CLAIM -> "CLAIM_FLOW"
            },
            shifts = all,
            evaluated = evaluation.evaluatedRuleIds,
            attempted = attempted,
            result = result,
            verify = verify,
            failure = failure,
            open = openLatency,
            compare = compareLatency,
            evaluation = SystemClock.elapsedRealtime() - started,
            claim = claimDuration,
            engine = decisionTrace.engine,
            aiNote = decisionTrace.note,
        )
    }

    private suspend fun awaitRoot(): AccessibilityNodeInfo? {
        repeat(12) {
            val root = rootInActiveWindow
            if (root != null) {
                if (root.packageName?.toString() == TeamworkLauncher.PACKAGE) return root
                root.recycle()
            }
            delay(100)
        }
        return null
    }

    private suspend fun awaitShiftRoot(parser: TeamworkParser): AccessibilityNodeInfo? {
        repeat(4) {
            val root = awaitRoot() ?: return null
            val snapshot = AccessibilitySemanticTree.snapshot(root)
            val parsed = parser.parse(snapshot)
            if (parsed.openMarkersSeen > 0 || looksLikeShiftSurface(snapshot)) return root

            val target = findNavigationTarget(root)
            if (target == null) return root
            val id = target.viewIdResourceName
            val label = AccessibilitySemanticTree.ownSemanticText(target)
            val sent = try {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                target.recycle()
                root.recycle()
            }
            if (!sent) return awaitRoot()
            uiMap.save(id, label)
            delay(140)
        }
        return awaitRoot()
    }

    private fun looksLikeShiftSurface(snapshot: SemanticNode): Boolean {
        return snapshot.flatten().any {
            it.node.resourceId == "$TEAMWORK_ID/agenda-list" || it.node.resourceId == "agenda-list"
        }
    }

    private fun findNavigationTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        AccessibilitySemanticTree.findClickableByResourceId(root, "$TEAMWORK_ID/tab-calendar")?.let { return it }
        val hint = uiMap.load()
        hint?.resourceId?.let { id ->
            AccessibilitySemanticTree.findClickableByResourceId(root, id)?.let { return it }
        }
        hint?.semanticLabel?.let { expected ->
            AccessibilitySemanticTree.findClickableByOwnText(root) {
                normalize(it) == normalize(expected)
            }?.let { return it }
        }

        val labels = Regex("(?i)^(calendar|schedule|shifts?|diensten?|rooster|planning)$")
        AccessibilitySemanticTree.findClickableByOwnText(root) {
            labels.matches(it.trim())
        }?.let { return it }

        return AccessibilitySemanticTree.findClickableByResourceIdContains(
            root,
            Regex("(?i)(calendar|schedule|shift|roster|rooster|planning)"),
        )
    }

    private fun normalize(value: String): String =
        value.replace(Regex("""\s+"""), " ").trim().lowercase()

    private suspend fun scan(parser: TeamworkParser, reset: Boolean): List<OpenShift> {
        if (reset) rewind()
        val states = mutableSetOf<String>()
        val shifts = linkedMapOf<String, OpenShift>()
        var noNew = 0
        repeat(16) {
            val root = rootInActiveWindow ?: return@repeat
            if (root.packageName?.toString() != TeamworkLauncher.PACKAGE) {
                root.recycle()
                return shifts.values.toList()
            }
            val snapshot = try {
                AccessibilitySemanticTree.snapshot(root)
            } finally {
                root.recycle()
            }
            val state = normalize(snapshot.subtreeSemanticText()).take(5000)
            if (!states.add(state)) return shifts.values.toList()
            val before = shifts.size
            parser.parse(snapshot).shifts.map { it.shift }.forEach { shift ->
                shifts.putIfAbsent(shift.stableKey, shift)
            }
            noNew = if (shifts.size == before) noNew + 1 else 0
            if (noNew >= 2 || !scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return shifts.values.toList()
            }
            delay(90)
        }
        return shifts.values.toList()
    }

    private suspend fun rewind() {
        val states = mutableSetOf<String>()
        repeat(16) {
            val root = rootInActiveWindow ?: return
            val state = try {
                normalize(AccessibilitySemanticTree.snapshot(root).subtreeSemanticText()).take(5000)
            } finally {
                root.recycle()
            }
            if (!states.add(state) || !scrollVertical(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            delay(90)
        }
    }

    private fun scroll(action: Int): Boolean = scrollVertical(action)

    private fun scrollVertical(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val node = AccessibilitySemanticTree.firstScrollableByClass(
                root,
                "android.widget.ScrollView",
                action,
            ) ?: return false
            try {
                node.performAction(action)
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private suspend fun navigateToDate(target: LocalDate): Boolean {
        val targetWeekStart = target.minusDays((target.dayOfWeek.value - 1).toLong())
        repeat(17) {
            val pageRoot = rootInActiveWindow ?: return false
            val activeWeekStart = try {
                activeWeekStart(pageRoot, targetWeekStart)
            } finally {
                pageRoot.recycle()
            } ?: return false
            if (activeWeekStart == targetWeekStart) return true

            val action = if (targetWeekStart.isAfter(activeWeekStart)) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            } else {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            }
            val scrollRoot = rootInActiveWindow ?: return false
            val moved = try {
                val agenda = AccessibilitySemanticTree.findByResourceId(scrollRoot, "$TEAMWORK_ID/agenda-list")
                    ?: AccessibilitySemanticTree.findByResourceId(scrollRoot, "agenda-list")
                    ?: return false
                try {
                    val pager = AccessibilitySemanticTree.firstScrollableByClass(
                        agenda,
                        "android.widget.HorizontalScrollView",
                        action,
                    ) ?: return false
                    try {
                        pager.performAction(action)
                    } finally {
                        pager.recycle()
                    }
                } finally {
                    agenda.recycle()
                }
            } finally {
                scrollRoot.recycle()
            }
            if (!moved) return false
            delay(500)
        }
        return false
    }

    private fun activeWeekStart(root: AccessibilityNodeInfo, targetWeekStart: LocalDate): LocalDate? {
        val week = Regex("\\bWeek\\s+(\\d{1,2})\\b")
            .find(AccessibilitySemanticTree.snapshot(root).subtreeSemanticText())
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val targetYear = targetWeekStart.get(IsoFields.WEEK_BASED_YEAR)
        return (targetYear - 1..targetYear + 1).map { year ->
            val januaryFourth = LocalDate.of(year, 1, 4)
            val firstMonday = januaryFourth.minusDays((januaryFourth.dayOfWeek.value - 1).toLong())
            firstMonday.plusWeeks((week - 1).toLong())
        }.minByOrNull { candidate ->
            kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(candidate, targetWeekStart))
        }
    }

    private fun currentWeekRange(root: AccessibilityNodeInfo): Pair<LocalDate, LocalDate>? {
        val prefix = "$TEAMWORK_ID/calendar-week-selector-"
        val ranges = AccessibilitySemanticTree.resourceIdsWithPrefix(
            root,
            prefix,
        ).distinct().mapNotNull { id ->
            val match = WEEK_SELECTOR.find(id) ?: return@mapNotNull null
            val start = runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
                ?: return@mapNotNull null
            val end = runCatching { LocalDate.parse(match.groupValues[2]) }.getOrNull()
                ?: return@mapNotNull null
            start to end
        }.sortedBy { it.first }
        return ranges.getOrNull(ranges.size / 2)
    }

    private suspend fun execute(
        matches: List<RuleMatch>,
        evaluatedRules: List<ShiftRule>,
        parser: TeamworkParser,
    ): Triple<String, String, String?> {
        val byId = evaluatedRules.associateBy { it.id }
        val done = mutableSetOf<String>()
        val results = mutableListOf<String>()

        for (match in matches) {
            val rule = byId[match.ruleId] ?: continue
            val current = rules.load().firstOrNull { it.id == rule.id && it.enabled }
            if (current != rule) return Triple(results.joinToString(";"), "STOPPED", "Rule changed before claim")
            if (!settings.load().let { it.enabled && it.armed }) {
                return Triple(results.joinToString(";"), "STOPPED", "Sniper not both enabled and armed")
            }

            val preflight = RuleEngine(LocalDate.now())
                .evaluate(listOf(rule), scan(parser, reset = true))
                .matches
                .firstOrNull { it.date == match.date }
                ?: return Triple(results.joinToString(";"), "STOPPED", "Rule no longer fully matches")

            if (rule.type == RuleType.SEQUENCE &&
                preflight.shifts.map { it.code } != match.shifts.map { it.code }
            ) {
                return Triple(results.joinToString(";"), "STOPPED", "Sequence changed before action")
            }

            for (target in match.shifts) {
                if (target.stableKey in done) continue
                if (rule.type == RuleType.SEQUENCE) {
                    val remaining = match.shifts
                        .filter { it.stableKey !in done }
                        .map { it.code }
                        .toSet()
                    val fresh = scan(parser, reset = true)
                        .filter { it.date == match.date && it.code in remaining }
                        .map { it.code }
                        .toSet()
                    if (!fresh.containsAll(remaining)) {
                        return Triple(results.joinToString(";"), "PARTIAL_STOP", "Sequence member disappeared")
                    }
                }

                val outcome = clickFresh(target, rule, parser)
                results += target.date.toString() + " " + target.codeLabel + ":" + outcome.first
                if (!outcome.second) {
                    return Triple(results.joinToString(";"), outcome.third, outcome.fourth)
                }
                done += target.stableKey
            }
        }

        return Triple(
            results.ifEmpty { listOf("NO_UNIQUE_TARGET") }.joinToString(";"),
            if (done.isEmpty()) "NOT_EXECUTED" else "VERIFIED_NOT_OPEN_AFTER_ACTION",
            null,
        )
    }

    private suspend fun clickFresh(
        target: OpenShift,
        rule: ShiftRule,
        parser: TeamworkParser,
    ): Quad {
        if (!rules.load().any { it == rule && it.enabled } ||
            !settings.load().let { it.enabled && it.armed }
        ) {
            return Quad("NOT_EXECUTED", false, "STOPPED", "Safety state changed")
        }

        if (!navigateToDate(target.date)) {
            return Quad("NOT_EXECUTED", false, "STOPPED", "Target calendar week unavailable")
        }
        val states = mutableSetOf<String>()
        repeat(16) {
            val root = rootInActiveWindow ?: return@repeat
            if (root.packageName?.toString() != TeamworkLauncher.PACKAGE) {
                root.recycle()
                return Quad("NOT_EXECUTED", false, "STOPPED", "Teamwork not active")
            }

            val snapshot = AccessibilitySemanticTree.snapshot(root)
            val state = normalize(snapshot.subtreeSemanticText()).take(5000)
            if (!states.add(state)) {
                root.recycle()
                return Quad("NOT_EXECUTED", false, "STOPPED", "Target not found")
            }

            if (detailMatches(snapshot, target)) {
                root.recycle()
                return confirmAndVerify(target, parser)
            }

            val candidates = parser.parse(snapshot).shifts.filter { same(it.shift, target) }
            if (candidates.size > 1) {
                root.recycle()
                return Quad("NOT_EXECUTED", false, "AMBIGUOUS", "Multiple fresh semantic candidates")
            }

            if (candidates.size == 1) {
                val exactResourceId = target.semanticIdentity.takeIf {
                    it.startsWith(com.cyclone.teamworksniper.teamwork.TeamworkNativeShiftId.PREFIX)
                }
                val sent = if (exactResourceId != null) {
                    root.recycle()
                    tapVisibleResourceId(exactResourceId)
                } else run {
                    val row = AccessibilitySemanticTree.nodeAtPath(
                        root,
                        candidates.single().observationPath,
                    )
                    val clickable = if (row == null) null else try {
                        AccessibilitySemanticTree.nearestClickable(row)
                    } finally {
                        row?.recycle()
                    }
                    root.recycle()
                    if (clickable == null) false else try {
                        clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } finally {
                        clickable.recycle()
                    }
                }
                if (!sent) return Quad("CLICK_REJECTED", false, "FAILED", "Bound semantic tap was rejected")

                val detail = awaitMatchingDetail(target)
                if (!detail.matched) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return Quad(
                        "ROW_ACTION_SENT",
                        false,
                        "MISMATCH",
                        "Opened detail did not match target after bounded semantic wait" +
                            detail.evidence?.let { "; observed=$it" }.orEmpty(),
                    )
                }
                return confirmAndVerify(target, parser)
            }

            root.recycle()
            if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return Quad("NOT_EXECUTED", false, "STOPPED", "Target no longer present")
            }
            delay(90)
        }
        return Quad("NOT_EXECUTED", false, "STOPPED", "Bounded fresh scan exhausted")
    }

    private suspend fun confirmAndVerify(target: OpenShift, parser: TeamworkParser): Quad {
        val confirmed = maybeConfirm(target)
        if (confirmed) {
            delay(180)
            maybeFinalConfirm(target)
            delay(300)
        }

        val post = awaitRoot()
            ?: return Quad("ACTION_SENT", false, "UNVERIFIED", "Teamwork root unavailable after action")
        val postSnapshot = try {
            AccessibilitySemanticTree.snapshot(post)
        } finally {
            post.recycle()
        }
        val success = Regex(
            "(?i)\\b(claimed|shift taken|added to (your )?schedule|you have this shift|dienst genomen|toegevoegd aan (je|jouw) rooster)\\b",
        ).containsMatchIn(postSnapshot.subtreeSemanticText())

        if (!confirmed && !success) {
            return Quad(
                "DETAIL_VERIFIED",
                false,
                "UNVERIFIED",
                "Exact detail was open but Claim shift was not accepted",
            )
        }
        if (!returnToCalendar(target.date)) {
            return Quad(
                "ACTION_SENT",
                false,
                "UNVERIFIED",
                if (success) "Success text seen but calendar re-verification failed" else "Calendar re-verification failed",
            )
        }
        refreshCalendar()
        val stillOpen = scan(parser, reset = true).any { same(it, target) }
        return if (!stillOpen) {
            Quad(
                if (confirmed) "CONFIRMED_ACTION_SENT" else "DIRECT_ACTION_SENT",
                true,
                "TARGET_NO_LONGER_OPEN",
                null,
            )
        } else {
            Quad("ACTION_SENT", false, "FAILED", "Target still Open to take")
        }
    }

    private suspend fun tapVisibleResourceId(resourceId: String): Boolean {
        repeat(12) {
            val root = awaitRoot() ?: return false
            val row = AccessibilitySemanticTree.findByResourceId(root, resourceId)
            if (row != null) {
                if (row.isVisibleToUser) {
                    val bounds = Rect().also(row::getBoundsInScreen)
                    row.recycle()
                    root.recycle()
                    if (bounds.isEmpty) return false
                    return dispatchSemanticTap(bounds)
                }
                val requested = row.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id,
                )
                row.recycle()
                root.recycle()
                if (requested) {
                    delay(100)
                    return@repeat
                }
            } else {
                root.recycle()
            }
            if (!scrollVertical(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false
            delay(90)
        }
        return false
    }

    private suspend fun dispatchSemanticTap(bounds: Rect): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
            null,
        )
        if (!accepted && continuation.isActive) continuation.resume(false)
    }

    private suspend fun maybeConfirm(target: OpenShift): Boolean {
        val root = rootInActiveWindow ?: return false
        val belongs = try {
            if (root.packageName?.toString() != TeamworkLauncher.PACKAGE) return false
            detailMatches(AccessibilitySemanticTree.snapshot(root), target)
        } finally {
            root.recycle()
        }
        if (!belongs) return false
        return tapVisibleResourceId("$TEAMWORK_ID/shift-detail-button")
    }

    private fun ancestorMentions(button: AccessibilityNodeInfo, target: OpenShift): Boolean {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(button)
        repeat(5) {
            val node = current ?: return false
            val text = AccessibilitySemanticTree.snapshot(node).subtreeSemanticText()
            val hasCode = Regex(
                "(?i)(?<![A-Z0-9])" + target.code.name + "(?![A-Z0-9])",
            ).containsMatchIn(text)
            val hasTime = target.startTime?.let { time ->
                val minute = "%02d".format(time.minute)
                text.contains(time.hour.toString() + ":" + minute) ||
                    text.contains(time.hour.toString() + "." + minute)
            } ?: true
            if (hasCode && hasTime) {
                node.recycle()
                return true
            }
            val parent = node.parent
            node.recycle()
            current = parent
        }
        current?.recycle()
        return false
    }

    private suspend fun awaitMatchingDetail(target: OpenShift): DetailWait {
        var evidence: String? = null
        repeat(24) {
            val root = awaitRoot()
            if (root != null) {
                val snapshot = try {
                    AccessibilitySemanticTree.snapshot(root)
                } finally {
                    root.recycle()
                }
                if (detailMatches(snapshot, target)) return DetailWait(true, detailEvidence(snapshot))
                if (Regex("(?i)\\b(open to take|claim shift)\\b").containsMatchIn(snapshot.subtreeSemanticText())) {
                    evidence = detailEvidence(snapshot)
                }
            }
            delay(100)
        }
        return DetailWait(false, evidence)
    }

    private fun detailEvidence(snapshot: SemanticNode): String {
        val text = snapshot.subtreeSemanticText()
        val codes = Regex("(?i)(?<![A-Z0-9])(M1|M2|S1|S2|S3)(?![A-Z0-9])")
            .findAll(text).map { it.value.uppercase() }.distinct().joinToString("-")
        val times = Regex("\\b\\d{1,2}:\\d{2}\\b").findAll(text).map { it.value }.distinct().take(4).joinToString("-")
        val date = TeamworkParser(LocalDate.now()).parseDate(text)?.toString().orEmpty()
        return listOf(date, codes, times).filter { it.isNotBlank() }.joinToString("|").take(120)
    }

    private suspend fun maybeFinalConfirm(target: OpenShift): Boolean {
        repeat(16) {
            val root = awaitRoot() ?: return false
            val validDialog = try {
                if (root.packageName?.toString() != TeamworkLauncher.PACKAGE) return false
                val snapshot = AccessibilitySemanticTree.snapshot(root)
                val text = snapshot.subtreeSemanticText()
                val hasPrompt = Regex("(?i)\\bclaim this shift\\??").containsMatchIn(text)
                val hasDate = snapshot.flatten().any {
                    TeamworkParser(target.date).parseDate(it.node.ownSemanticText()) == target.date
                }
                val hasTimes = target.startTime?.let(::formatTime)?.let(text::contains) == true &&
                    target.endTime?.let(::formatTime)?.let(text::contains) == true
                hasPrompt && hasDate && hasTimes
            } finally {
                root.recycle()
            }
            if (validDialog) {
                val confirmRoot = rootInActiveWindow ?: return false
                val confirm = try {
                    AccessibilitySemanticTree.findClickableByOwnText(confirmRoot) {
                        Regex("(?i)^(confirm|bevestig)$").matches(it.trim())
                    }
                } finally {
                    confirmRoot.recycle()
                } ?: return false
                val bounds = Rect().also(confirm::getBoundsInScreen)
                confirm.recycle()
                if (bounds.isEmpty) return false
                return dispatchSemanticTap(bounds)
            }
            delay(75)
        }
        return false
    }

    private suspend fun returnToCalendar(targetDate: LocalDate): Boolean {
        repeat(3) {
            val root = awaitRoot() ?: return false
            val calendar = try {
                (AccessibilitySemanticTree.findByResourceId(root, "$TEAMWORK_ID/agenda-list")
                    ?: AccessibilitySemanticTree.findByResourceId(root, "agenda-list"))?.let {
                    it.recycle()
                    true
                } ?: false
            } finally {
                root.recycle()
            }
            if (calendar) return navigateToDate(targetDate)
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(120)
        }
        val root = awaitShiftRoot(TeamworkParser(LocalDate.now())) ?: return false
        root.recycle()
        return navigateToDate(targetDate)
    }

    private suspend fun refreshCalendar() {
        val root = awaitRoot() ?: return
        val button = try {
            AccessibilitySemanticTree.findClickableByResourceId(root, "$TEAMWORK_ID/refresh-button")
                ?: AccessibilitySemanticTree.findClickableByResourceId(root, "refresh-button")
        } finally {
            root.recycle()
        } ?: return
        try {
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            button.recycle()
        }
        delay(180)
    }

    private fun detailMatches(snapshot: SemanticNode, target: OpenShift): Boolean {
        val text = snapshot.subtreeSemanticText()
        val hasOpenDetail = Regex("(?i)\\bopen to take\\b").containsMatchIn(text)
        val hasCode = Regex(
            "(?i)(?<![A-Z0-9])" + Regex.escape(target.codeLabel) + "(?![A-Z0-9])",
        ).containsMatchIn(text)
        val start = target.startTime?.let(::formatTime)
        val end = target.endTime?.let(::formatTime)
        val hasTimes = start != null && end != null && text.contains(start) && text.contains(end)
        val parser = TeamworkParser(target.date)
        val hasDate = snapshot.flatten().any { parser.parseDate(it.node.ownSemanticText()) == target.date }
        return hasOpenDetail && hasCode && hasTimes && hasDate
    }

    private fun formatTime(time: java.time.LocalTime) = "%02d:%02d".format(time.hour, time.minute)

    private fun same(a: OpenShift, b: OpenShift) =
        a.date == b.date &&
            a.codes == b.codes &&
            a.startTime == b.startTime &&
            a.endTime == b.endTime

    private fun record(
        trigger: TriggerEvent,
        decision: String,
        shifts: List<OpenShift>,
        evaluated: List<String>,
        attempted: Boolean,
        result: String?,
        verify: String?,
        failure: String?,
        open: Long? = null,
        compare: Long? = null,
        evaluation: Long? = null,
        claim: Long? = null,
        engine: String = "DETERMINISTIC",
        aiNote: String? = null,
    ) {
        val currentSettings = settings.load()
        log.append(
            ActivityEntry(
                id = UUID.randomUUID().toString(),
                triggerSource = trigger.source,
                triggerEpochMs = trigger.wallClockEpochMs,
                notificationTitle = trigger.notificationTitle,
                notificationText = trigger.notificationText,
                teamworkOpenLatencyMs = open,
                firstComparisonLatencyMs = compare,
                evaluationDurationMs = evaluation,
                claimDurationMs = claim,
                openShifts = shifts.map { shift ->
                    shift.date.toString() + " " + shift.codeLabel +
                        (shift.startTime?.let { " " + it } ?: "")
                },
                evaluatedRules = evaluated,
                decision = decision,
                armedState = currentSettings.armed,
                claimAttempted = attempted,
                claimResult = result,
                verificationResult = verify,
                failureReason = failure,
                decisionEngine = engine,
                aiAdvice = aiNote,
            ),
        )
    }

    private data class Quad(
        val first: String,
        val second: Boolean,
        val third: String,
        val fourth: String?,
    )

    private data class DetailWait(val matched: Boolean, val evidence: String?)

    companion object {
        private const val TEAMWORK_ID = "tech.picnic.workapp:id"
        private val WEEK_SELECTOR = Regex(
            "calendar-week-selector-(\\d{4}-\\d{2}-\\d{2})-(\\d{4}-\\d{2}-\\d{2})$",
        )
    }

    private fun openDetailCandidate(parser: TeamworkParser): OpenShift? {
        val root = rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() != TeamworkLauncher.PACKAGE) return null
            val detail = AccessibilitySemanticTree.findByResourceId(root, "shift-detail-test-id")
                ?: AccessibilitySemanticTree.findByResourceId(root, "$TEAMWORK_ID/shift-detail-test-id")
                ?: return null
            val snapshot = try {
                AccessibilitySemanticTree.snapshot(detail)
            } finally {
                detail.recycle()
            }
            val text = snapshot.subtreeSemanticText()
            if (!Regex("(?i)\\bopen to take\\b").containsMatchIn(text) ||
                !Regex("(?i)\\bclaim shift\\b").containsMatchIn(text)
            ) return null
            val date = parser.parseDate(text)
            if (date == null) return null
            val times = Regex("\\b(\\d{1,2})[:.](\\d{2})\\b").findAll(text).mapNotNull { match ->
                runCatching {
                    java.time.LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                }.getOrNull()
            }.distinct().take(2).toList()
            if (times.size != 2) return null
            val codes = snapshot.flatten().mapNotNull { ref ->
                val raw = ref.node.resourceId?.substringAfterLast('/')
                com.cyclone.teamworksniper.data.ShiftCode.fromRaw(raw)
            }.distinct().sortedBy { it.order }
            if (codes.isEmpty()) return null
            OpenShift(
                date = date,
                code = codes.first(),
                codes = codes,
                startTime = times[0],
                endTime = times[1],
                semanticIdentity = "teamwork-open-detail|$date|${codes.joinToString("-")}|${times[0]}|${times[1]}",
            )
        } finally {
            root.recycle()
        }
    }

    private suspend fun awaitOpenDetailCandidate(parser: TeamworkParser): OpenShift? {
        repeat(8) {
            openDetailCandidate(parser)?.let { return it }
            delay(50)
        }
        return null
    }
}
