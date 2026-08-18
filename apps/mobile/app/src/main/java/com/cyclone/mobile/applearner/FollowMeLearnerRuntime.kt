package com.cyclone.mobile.applearner

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * User-driven whole-phone learning. Cyclone observes only; the user retains HUMAN control.
 * Each visited app still lands in the existing AppKnowledgeStore so V2.5/V2.6 learned-app features
 * keep working. Cross-app transitions are additionally recorded as Adaptive Brain micro-skills.
 */
data class FollowMeProgress(
    val active: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long? = null,
    val currentApp: String = "",
    val currentPackage: String = "",
    val currentScreen: String = "",
    val appsSeen: Int = 0,
    val screensSeen: Int = 0,
    val actionsSeen: Int = 0,
    val pathsLearned: Int = 0,
    val message: String = "Ready",
)

object FollowMeLearnerRuntime {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastObservation = AtomicLong(0L)
    @Volatile private var state = FollowMeProgress()
    @Volatile private var appContext: Context? = null
    private var previousController = DeviceState.Controller.AGENT
    private var previousPackage: String? = null
    private var previousFingerprint: String? = null
    private var previousScreenId: String? = null
    private var pendingAction: PendingUserAction? = null
    private val seenApps = linkedSetOf<String>()
    private val seenScreens = linkedSetOf<String>()
    private val seenActions = linkedSetOf<String>()
    private val seenPaths = linkedSetOf<String>()

    private data class PendingUserAction(
        val packageName: String,
        val screenId: String?,
        val label: String,
        val selector: JSONObject,
        val risk: ActionRisk,
    )

    fun progress(): FollowMeProgress = state

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        AppLearnerRuntime.initialize(app)
        AdaptiveBrainRuntime.initialize(app)
        previousController = DeviceState.controller
        DeviceState.setController(DeviceState.Controller.HUMAN)
        previousPackage = null
        previousFingerprint = null
        previousScreenId = null
        pendingAction = null
        seenApps.clear(); seenScreens.clear(); seenActions.clear(); seenPaths.clear()
        state = FollowMeProgress(active = true, startedAt = System.currentTimeMillis(), message = "Use your phone normally. Cyclone is observing navigation in the background and will not click for you.")
    }

    fun pause() {
        if (!state.active) return
        state = state.copy(paused = true, message = "Follow Me paused. No navigation is being learned.")
    }

    fun resume() {
        if (!state.active) return
        state = state.copy(paused = false, message = "Follow Me resumed. Keep using your phone normally.")
    }

    @Synchronized
    fun stop() {
        val app = appContext
        state = state.copy(active = false, paused = false, message = "Follow Me stopped. Learned app maps and micro-skills were saved locally.")
        pendingAction = null
        if (previousController == DeviceState.Controller.AGENT) {
            DeviceState.setController(DeviceState.Controller.AGENT)
            if (app != null) PhoneToolExecutor.execute(app, PhoneToolRequest("follow-me-final-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()))
        }
        runCatching { AdaptiveBrainRuntime.store.writeMirror() }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val app = appContext ?: return
        if (!state.active || state.paused) return
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (packageName == app.packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> event.source?.let { captureUserAction(packageName, it) }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Unit // Never learn typed contents.
        }

        if (event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
            )) {
            val now = System.currentTimeMillis()
            val previous = lastObservation.get()
            if (now - previous < 320L || !lastObservation.compareAndSet(previous, now)) return
            executor.submit {
                Thread.sleep(100)
                if (!state.active || state.paused) return@submit
                val result = PhoneToolExecutor.execute(app, PhoneToolRequest("follow-me-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()))
                val snapshot = result.payload as? JSONObject ?: return@submit
                learnSnapshot(snapshot)
            }
        }
    }

    private fun captureUserAction(packageName: String, node: AccessibilityNodeInfo) {
        val json = nodeJson(node)
        if (ActionSafetyPolicy.looksSensitiveField(json)) return
        val label = json.optString("text").trim()
            .ifBlank { json.optString("contentDescription").trim() }
            .ifBlank { json.optString("resourceId").substringAfterLast('/') }
        if (label.isBlank()) return
        val selector = JSONObject().apply {
            json.optString("resourceId").takeIf { it.isNotBlank() }?.let { put("resourceId", it.take(180)) }
            json.optString("text").takeIf { it.isNotBlank() }?.let { put("text", it.take(180)) }
            json.optString("contentDescription").takeIf { it.isNotBlank() }?.let { put("contentDescription", it.take(180)) }
            json.optString("role").takeIf { it.isNotBlank() }?.let { put("role", it) }
        }
        pendingAction = PendingUserAction(
            packageName = packageName,
            screenId = if (packageName == previousPackage) previousScreenId else null,
            label = label.take(120),
            selector = selector,
            risk = ActionSafetyPolicy.classify(label, json.optString("resourceId"), json.optString("contentDescription")),
        )
        seenActions += "$packageName|$label"
        publish(message = "Saw you use $label")
    }

    private fun learnSnapshot(snapshot: JSONObject) {
        val app = appContext ?: return
        val packageName = snapshot.optString("package").takeIf { it.isNotBlank() } ?: return
        if (packageName == app.packageName) return
        val label = appLabel(packageName)
        val store = AppLearnerRuntime.store
        val packageInfo = runCatching { app.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val existingApp = store.getApp(packageName)
        store.upsertApp((existingApp ?: LearnedApp(packageName = packageName, label = label)).copy(
            label = label,
            versionName = packageInfo?.versionName,
            versionCode = packageInfo?.longVersionCode,
            knowledgeState = if (existingApp == null) KnowledgeState.DISCOVERED else existingApp.knowledgeState,
            confidence = maxOf(existingApp?.confidence ?: 0.0, 0.58),
            lastLearnedAt = System.currentTimeMillis(),
            instructionSummary = "Observed in V2.7 Follow Me mode while the user navigated normally.",
        ))

        val candidate = ScreenSemanticizer.fromSnapshot(packageName, snapshot)
        val match = store.findBestScreenMatch(packageName, candidate.recognition)
        val screen = if (match != null) {
            val current = match.first
            current.copy(
                title = if (candidate.title != "Screen") candidate.title else current.title,
                purpose = if (current.purpose.startsWith("A learned screen")) candidate.purpose else current.purpose,
                recognition = candidate.recognition,
                knowledgeState = if (match.second >= 0.90) KnowledgeState.UNDERSTOOD else current.knowledgeState,
                confidence = maxOf(current.confidence, match.second.coerceIn(0.58, 0.94)),
                appVersion = packageInfo?.versionName,
                lastSeenAt = System.currentTimeMillis(),
            )
        } else {
            LearnedScreen(
                packageName = packageName,
                identity = uniqueIdentity(store, packageName, candidate.identity),
                title = candidate.title,
                purpose = candidate.purpose,
                recognition = candidate.recognition,
                knowledgeState = KnowledgeState.DISCOVERED,
                confidence = 0.62,
                appVersion = packageInfo?.versionName,
            )
        }
        store.upsertScreen(screen)
        candidate.actions.forEach { action ->
            if (action.requiredInput == null) store.upsertAction(action.copy(screenId = screen.id, knowledgeState = KnowledgeState.UNDERSTOOD, confidence = maxOf(action.confidence, 0.64)))
        }

        seenApps += packageName
        seenScreens += "$packageName|${screen.identity}"

        val previousPkg = previousPackage
        val previousScreen = previousScreenId
        val action = pendingAction
        if (previousPkg != null && previousPkg == packageName && previousScreen != null && previousScreen != screen.id && action != null && action.packageName == packageName) {
            val learned = LearnedAction(
                packageName = packageName,
                screenId = previousScreen,
                semanticName = ScreenSemanticizer.slugify(action.label).ifBlank { "user_action" },
                label = action.label,
                androidActions = listOf("USER_DEMONSTRATED"),
                selectorJson = action.selector.toString(),
                risk = action.risk,
                knowledgeState = KnowledgeState.UNDERSTOOD,
                confidence = 0.76,
            )
            store.upsertAction(learned)
            val storedAction = store.listActions(packageName).lastOrNull {
                it.screenId == previousScreen && it.semanticName == learned.semanticName
            }
            if (storedAction != null) {
                store.upsertTransition(LearnedTransition(
                    packageName = packageName,
                    fromScreenId = previousScreen,
                    actionId = storedAction.id,
                    toScreenId = screen.id,
                    knowledgeState = KnowledgeState.UNDERSTOOD,
                    confidence = 0.77,
                ))
                seenPaths += "$packageName|$previousScreen|${storedAction.id}|${screen.id}"
            }
        } else if (previousPkg != null && previousPkg != packageName && action != null && action.packageName == previousPkg) {
            AdaptiveBrainRuntime.store.recordHumanTransition(
                goalHint = "open $label",
                fromPackage = previousPkg,
                fromFingerprint = previousFingerprint,
                targetPackage = packageName,
                targetFingerprint = snapshot.optString("fingerprint"),
                selector = action.selector,
            )
            seenPaths += "$previousPkg->${packageName}"
        }

        previousPackage = packageName
        previousFingerprint = snapshot.optString("fingerprint").takeIf { it.isNotBlank() }
        previousScreenId = screen.id
        pendingAction = null
        store.mirror(packageName)
        publish(
            currentApp = label,
            currentPackage = packageName,
            currentScreen = screen.title,
            message = "Learning while you navigate $label",
        )
    }

    private fun publish(
        currentApp: String = state.currentApp,
        currentPackage: String = state.currentPackage,
        currentScreen: String = state.currentScreen,
        message: String = state.message,
    ) {
        state = state.copy(
            currentApp = currentApp,
            currentPackage = currentPackage,
            currentScreen = currentScreen,
            appsSeen = seenApps.size,
            screensSeen = seenScreens.size,
            actionsSeen = seenActions.size,
            pathsLearned = seenPaths.size,
            message = message,
        )
    }

    private fun appLabel(packageName: String): String {
        val app = appContext ?: return packageName
        return runCatching {
            val info = app.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            app.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { packageName.substringAfterLast('.') }
    }

    private fun uniqueIdentity(store: AppKnowledgeStore, packageName: String, base: String): String {
        val known = store.listScreens(packageName).map { it.identity }.toSet()
        if (base !in known) return base
        var index = 2
        while ("${base}_$index" in known) index++
        return "${base}_$index"
    }

    private fun nodeJson(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect().also(node::getBoundsInScreen)
        return JSONObject()
            .put("text", node.text?.toString().orEmpty())
            .put("contentDescription", node.contentDescription?.toString().orEmpty())
            .put("resourceId", node.viewIdResourceName.orEmpty())
            .put("role", when {
                node.isEditable -> "textbox"
                node.isCheckable -> "checkbox"
                node.isScrollable -> "scroll_container"
                node.isClickable -> "button"
                else -> "generic"
            })
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("password", node.isPassword)
            .put("bounds", JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom))
    }
}
