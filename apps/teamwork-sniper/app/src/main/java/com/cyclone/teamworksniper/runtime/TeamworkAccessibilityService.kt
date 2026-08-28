package com.cyclone.teamworksniper.runtime

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.teamworksniper.ai.AiDecisionPolicy
import com.cyclone.teamworksniper.ai.AiDecisionTrace
import com.cyclone.teamworksniper.ai.OpenRouterAdvisor
import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.ActivityLogStore
import com.cyclone.teamworksniper.data.OpenShift
import com.cyclone.teamworksniper.data.RuleStore
import com.cyclone.teamworksniper.data.SettingsStore
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.TriggerEvent
import com.cyclone.teamworksniper.data.UiMapStore
import com.cyclone.teamworksniper.rules.ExecutionMode
import com.cyclone.teamworksniper.rules.RuleEngine
import com.cyclone.teamworksniper.rules.RuleMatch
import com.cyclone.teamworksniper.rules.RuleType
import com.cyclone.teamworksniper.rules.SafetyGate
import com.cyclone.teamworksniper.teamwork.SemanticNode
import com.cyclone.teamworksniper.teamwork.TeamworkParser
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TeamworkAccessibilityService : AccessibilityService() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val busy = AtomicBoolean(false)
    private var queued: TriggerEvent? = null

    private val rules by lazy { RuleStore(this) }
    private val settings by lazy { SettingsStore(this) }
    private val log by lazy { ActivityLogStore(this) }
    private val uiMap by lazy { UiMapStore(this) }
    private val ai by lazy { OpenRouterAdvisor(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        SniperCoordinator.attach(this)
    }

    override fun onDestroy() {
        SniperCoordinator.detach(this)
        job.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != TeamworkLauncher.PACKAGE) return
        SniperCoordinator.current()?.let(::requestEvaluation)
    }

    fun requestEvaluation(trigger: TriggerEvent) {
        if (!busy.compareAndSet(false, true)) {
            queued = trigger
            return
        }
        scope.launch {
            try {
                evaluate(trigger)
            } finally {
                SniperCoordinator.consume(trigger)
                busy.set(false)
                queued?.also {
                    queued = null
                    requestEvaluation(it)
                }
            }
        }
    }

    private suspend fun evaluate(trigger: TriggerEvent) {
        val started = SystemClock.elapsedRealtime()
        val parser = TeamworkParser(LocalDate.now())
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

        val openLatency = SystemClock.elapsedRealtime() - trigger.elapsedRealtimeMs
        val quick = try {
            parser.parse(AccessibilitySemanticTree.snapshot(root)).shifts.map { it.shift }
        } finally {
            root.recycle()
        }
        val currentRules = rules.load()
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
        val text = snapshot.subtreeSemanticText()
        val code = Regex("(?i)(?<![A-Z0-9])(M1|M2|S1|S2|S3)(?![A-Z0-9])").containsMatchIn(text)
        val time = Regex("\b\d{1,2}[:.]\d{2}\b").containsMatchIn(text)
        return code && time
    }

    private fun findNavigationTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
        value.replace(Regex("\s+"), " ").trim().lowercase()

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
            if (!states.add(state) || !scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            delay(90)
        }
    }

    private fun scroll(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val node = AccessibilitySemanticTree.firstScrollable(root, action) ?: return false
            try {
                node.performAction(action)
            } finally {
                node.recycle()
            }
        } finally {
            root.recycle()
        }
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
                results += target.date.toString() + " " + target.code.name + ":" + outcome.first
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

        rewind()
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

            val candidates = parser.parse(snapshot).shifts.filter { same(it.shift, target) }
            if (candidates.size > 1) {
                root.recycle()
                return Quad("NOT_EXECUTED", false, "AMBIGUOUS", "Multiple fresh semantic candidates")
            }

            if (candidates.size == 1) {
                val row = AccessibilitySemanticTree.nodeAtPath(root, candidates.single().observationPath)
                root.recycle()
                if (row == null) return Quad("NOT_EXECUTED", false, "STALE", "Fresh semantic path disappeared")
                val clickable = try {
                    AccessibilitySemanticTree.nearestClickable(row)
                } finally {
                    row.recycle()
                } ?: return Quad("NOT_EXECUTED", false, "NO_ACTION", "No clickable semantic node or ancestor")

                val sent = try {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } finally {
                    clickable.recycle()
                }
                if (!sent) return Quad("CLICK_REJECTED", false, "FAILED", "ACTION_CLICK returned false")

                delay(140)
                val confirmed = maybeConfirm(target)
                if (confirmed) delay(140)

                val post = awaitRoot()
                    ?: return Quad("ACTION_SENT", false, "UNVERIFIED", "Teamwork root unavailable after action")
                val text = try {
                    AccessibilitySemanticTree.snapshot(post).subtreeSemanticText()
                } finally {
                    post.recycle()
                }
                val success = Regex(
                    "(?i)\b(claimed|shift taken|added to (your )?schedule|you have this shift|dienst genomen|toegevoegd aan (je|jouw) rooster)\b",
                ).containsMatchIn(text)

                if (!confirmed && !success) {
                    return Quad(
                        "ROW_ACTION_SENT",
                        false,
                        "UNVERIFIED",
                        "No bound confirmation or explicit success evidence",
                    )
                }

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

            root.recycle()
            if (!scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return Quad("NOT_EXECUTED", false, "STOPPED", "Target no longer present")
            }
            delay(90)
        }
        return Quad("NOT_EXECUTED", false, "STOPPED", "Bounded fresh scan exhausted")
    }

    private fun maybeConfirm(target: OpenShift): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            if (root.packageName?.toString() != TeamworkLauncher.PACKAGE) return false
            val button = AccessibilitySemanticTree.findClickableByOwnText(root) {
                Regex("(?i)^(confirm|take shift|claim shift|take|bevestig|dienst nemen)$").matches(it.trim())
            } ?: return false
            val belongs = ancestorMentions(button, target)
            if (!belongs) {
                button.recycle()
                return false
            }
            try {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } finally {
                button.recycle()
            }
        } finally {
            root.recycle()
        }
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

    private fun same(a: OpenShift, b: OpenShift) =
        a.date == b.date &&
            a.code == b.code &&
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
                    shift.date.toString() + " " + shift.code.name +
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
}
