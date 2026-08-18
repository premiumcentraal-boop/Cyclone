package com.cyclone.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.Selector as AutomationSelector
import com.cyclone.mobile.guided.GuidedRecorderOverlayController
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

class CycloneAccessibilityService : AccessibilityService() {
    data class ScreenshotArtifact(
        val file: File,
        val width: Int,
        val height: Int,
        val crop: UiBounds?,
        val timestampMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("filePath", file.absolutePath)
            .put("bytes", file.length())
            .put("width", width)
            .put("height", height)
            .put("timestampMs", timestampMs)
            .put("crop", crop?.toJson() ?: JSONObject.NULL)
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private var lastAutomationPackage: String? = null
    private var guidedOverlay: GuidedRecorderOverlayController? = null

    companion object {
        @Volatile var instance: CycloneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        DeviceState.accessibilityConnected = true
        DeviceState.addLog("Accessibility connected")
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        BridgeClient.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() }
        packageName?.let { DeviceState.currentPackage = it }
        event.className?.toString()?.takeIf { it.isNotBlank() }?.let { DeviceState.currentClassName = it }
        DeviceState.lastUiEventAtMs = System.currentTimeMillis()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != null && packageName != lastAutomationPackage) {
            lastAutomationPackage = packageName
            AutomationRuntime.onAppOpened(this, packageName)
        }
        if (AutomationRuntime.recorder.isRecording()) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_CLICKED -> event.source?.let { AutomationRuntime.recorder.recordClick(automationSelector(it)) }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> event.source?.let { AutomationRuntime.recorder.recordText(automationSelector(it)) }
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> AutomationRuntime.recorder.recordScroll("forward")
            }
        }
        AppLearnerRuntime.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        guidedOverlay?.dismiss()
        guidedOverlay = null
        instance = null
        DeviceState.accessibilityConnected = false
        BridgeClient.stop()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    /** Opens Cyclone V2.4+'s floating teach-a-routine bubble over any app. */
    fun showGuidedRecorderOverlay() {
        if (guidedOverlay == null) guidedOverlay = GuidedRecorderOverlayController(this)
        guidedOverlay?.show()
    }

    fun hideGuidedRecorderOverlay() {
        guidedOverlay?.dismiss()
        guidedOverlay = null
    }

    fun observe(markFresh: Boolean = true): UiSnapshot {
        if (markFresh) waitForUiQuiet()
        val root = rootInActiveWindow
        val metrics = resources.displayMetrics
        val nodes = mutableListOf<UiNodeSnapshot>()
        if (root != null) collectNode(root, "0", null, 0, nodes)
        val windowsSnapshot = windows.orEmpty().map { window ->
            val rect = Rect().also { window.getBoundsInScreen(it) }
            UiWindowSnapshot(
                id = window.id,
                title = window.title?.toString().orEmpty(),
                type = window.type,
                layer = window.layer,
                active = window.isActive,
                focused = window.isFocused,
                bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            )
        }
        val packageName = root?.packageName?.toString()?.takeIf { it.isNotBlank() } ?: DeviceState.currentPackage
        val fingerprint = screenFingerprint(packageName, nodes)
        val snapshot = UiSnapshot(
            packageName = packageName,
            className = DeviceState.currentClassName,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            timestampMs = System.currentTimeMillis(),
            fingerprint = fingerprint,
            controller = DeviceState.controller.name.lowercase(),
            windows = windowsSnapshot,
            nodes = nodes,
        )
        if (markFresh) DeviceState.markObserved()
        return snapshot
    }

    fun find(selector: ElementSelector, limit: Int = 20): List<SelectorMatch> =
        SelectorEngine.resolve(observe(markFresh = false), selector, limit)

    fun click(selector: ElementSelector): Boolean {
        if (!agentCanAct()) return false
        repeat(2) {
            val target = resolveLiveTarget(selector) ?: return@repeat
            val (snapshotNode, node) = target
            if (!sameNode(snapshotNode, node)) return@repeat
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var parent = node.parent
            repeat(4) {
                if (parent == null) return@repeat
                if (parent!!.isClickable && parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                parent = parent!!.parent
            }
            return tap(snapshotNode.bounds.centerX, snapshotNode.bounds.centerY)
        }
        return false
    }

    fun longPress(selector: ElementSelector, durationMs: Long = 650): Boolean {
        if (!agentCanAct()) return false
        val target = resolveLiveTarget(selector)?.first ?: return false
        return longPress(target.bounds.centerX, target.bounds.centerY, durationMs)
    }

    fun setText(selector: ElementSelector?, value: String): Boolean {
        if (!agentCanAct()) return false
        val node = if (selector == null) rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        else resolveLiveTarget(selector)?.second
        node ?: return false
        if (!node.isEditable && !node.isFocusable) return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(selector: ElementSelector?, forward: Boolean = true): Boolean {
        if (!agentCanAct()) return false
        val node = selector?.let { resolveLiveTarget(it)?.second } ?: findScrollable(rootInActiveWindow)
        node ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return node.performAction(action)
    }

    fun tap(x: Float, y: Float): Boolean {
        if (!agentCanAct()) return false
        return rawTap(x, y)
    }

    fun longPress(x: Float, y: Float, durationMs: Long = 650): Boolean {
        if (!agentCanAct()) return false
        return rawLongPress(x, y, durationMs)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        if (!agentCanAct()) return false
        return rawSwipe(x1, y1, x2, y2, durationMs)
    }

    fun goBack(): Boolean = agentCanAct() && performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = agentCanAct() && performGlobalAction(GLOBAL_ACTION_HOME)

    /** Guided gestures are direct user instructions and bypass the AGENT lock. */
    fun guidedTap(x: Float, y: Float): Boolean = rawTap(x, y)
    fun guidedLongPress(x: Float, y: Float, durationMs: Long = 750): Boolean = rawLongPress(x, y, durationMs)
    fun guidedSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean = rawSwipe(x1, y1, x2, y2, durationMs)
    fun guidedBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun guidedHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun rawTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun rawLongPress(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(450, 3000))).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun rawSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 3000))).build()
        return dispatchGesture(gesture, null, null)
    }

    fun takeScreenshot(crop: UiBounds? = null, callback: (Result<ScreenshotArtifact>) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val outcome = runCatching {
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                            ?: error("Unable to map screenshot buffer")
                        val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: wrapped
                        val boundedCrop = crop?.let { bounds ->
                            UiBounds(
                                bounds.left.coerceIn(0, bitmap.width), bounds.top.coerceIn(0, bitmap.height),
                                bounds.right.coerceIn(0, bitmap.width), bounds.bottom.coerceIn(0, bitmap.height),
                            ).takeIf { it.width > 0 && it.height > 0 }
                        }
                        val outputBitmap = boundedCrop?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) } ?: bitmap
                        val file = File(cacheDir, "cyclone-${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { output -> outputBitmap.compress(Bitmap.CompressFormat.PNG, 95, output) }
                        DeviceState.lastScreenshotPath = file.absolutePath
                        ScreenshotArtifact(file, outputBitmap.width, outputBitmap.height, boundedCrop, System.currentTimeMillis())
                    } finally { result.hardwareBuffer.close() }
                }
                callback(outcome)
            }

            override fun onFailure(errorCode: Int) {
                callback(Result.failure(IllegalStateException("Screenshot failed: $errorCode")))
            }
        })
    }

    private fun agentCanAct(): Boolean = DeviceState.controller == DeviceState.Controller.AGENT && !DeviceState.requireFreshObservation

    private fun resolveLiveTarget(selector: ElementSelector): Pair<UiNodeSnapshot, AccessibilityNodeInfo>? {
        val snapshot = observe(markFresh = false)
        val match = SelectorEngine.resolve(snapshot, selector, 1).firstOrNull() ?: return null
        val root = rootInActiveWindow ?: return null
        val live = nodeAtPath(root, match.node.path) ?: return null
        return match.node to live
    }

    private fun nodeAtPath(root: AccessibilityNodeInfo, path: String): AccessibilityNodeInfo? {
        val pieces = path.split('/').filter { it.isNotBlank() }
        if (pieces.isEmpty() || pieces.first() != "0") return null
        var node = root
        for (index in pieces.drop(1)) node = node.getChild(index.toIntOrNull() ?: return null) ?: return null
        return node
    }

    private fun sameNode(snapshot: UiNodeSnapshot, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return snapshot.className == node.className?.toString().orEmpty() &&
            snapshot.resourceId == node.viewIdResourceName.orEmpty() &&
            snapshot.bounds == UiBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun collectNode(node: AccessibilityNodeInfo, path: String, parentId: String?, depth: Int, out: MutableList<UiNodeSnapshot>) {
        if (depth > 40 || out.size >= 2500) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom)
        val id = stableNodeId(path, node, bounds)
        val childIds = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childRect = Rect().also { child.getBoundsInScreen(it) }
            childIds += stableNodeId("$path/$i", child, UiBounds(childRect.left, childRect.top, childRect.right, childRect.bottom))
        }
        out += UiNodeSnapshot(
            id = id, path = path, parentId = parentId, childIds = childIds, depth = depth, windowId = node.windowId,
            className = node.className?.toString().orEmpty(), role = inferRole(node), text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(), resourceId = node.viewIdResourceName.orEmpty(), bounds = bounds,
            clickable = node.isClickable, longClickable = node.isLongClickable, editable = node.isEditable, scrollable = node.isScrollable,
            enabled = node.isEnabled, selected = node.isSelected, checked = node.isChecked, checkable = node.isCheckable,
            focused = node.isFocused, focusable = node.isFocusable, visibleToUser = node.isVisibleToUser,
            actions = accessibilityActionNames(node),
        )
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNode(it, "$path/$i", id, depth + 1, out) }
    }

    private fun accessibilityActionNames(node: AccessibilityNodeInfo): List<String> = node.actionList.orEmpty().map { action ->
        when (action.id) {
            AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id -> "ACTION_SCROLL_TO_POSITION"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
            AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
            AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
            AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
            AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
            else -> action.label?.toString()?.takeIf { it.isNotBlank() } ?: "ACTION_${action.id}"
        }
    }.distinct()

    private fun automationSelector(node: AccessibilityNodeInfo): AutomationSelector = AutomationSelector(
        resourceId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
        text = node.text?.toString()?.takeIf { it.isNotBlank() },
        contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
        role = inferRole(node),
        className = node.className?.toString()?.takeIf { it.isNotBlank() },
        requireClickable = node.isClickable.takeIf { it },
        requireEditable = node.isEditable.takeIf { it },
        requireScrollable = node.isScrollable.takeIf { it },
    )

    private fun stableNodeId(path: String, node: AccessibilityNodeInfo, bounds: UiBounds): String {
        val raw = listOf(path, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}").joinToString("|")
        return sha256(raw).take(16)
    }

    private fun inferRole(node: AccessibilityNodeInfo): String {
        val cls = node.className?.toString().orEmpty().lowercase()
        return when {
            node.isEditable || "edittext" in cls -> "textbox"
            "button" in cls || node.isClickable && (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true) -> "button"
            "checkbox" in cls || node.isCheckable -> "checkbox"
            "switch" in cls -> "switch"
            "image" in cls -> "image"
            node.isScrollable -> "scroll_container"
            "textview" in cls -> "text"
            else -> "generic"
        }
    }

    private fun screenFingerprint(packageName: String?, nodes: List<UiNodeSnapshot>): String {
        val normalized = buildString {
            append(packageName.orEmpty())
            nodes.filter { it.visibleToUser }.take(800).forEach {
                append('|').append(it.resourceId).append('|').append(it.text.take(120)).append('|').append(it.contentDescription.take(120))
                    .append('|').append(it.className).append('|').append(it.bounds.left).append(',').append(it.bounds.top)
                    .append(',').append(it.bounds.right).append(',').append(it.bounds.bottom)
            }
        }
        return sha256(normalized).take(20)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    private fun waitForUiQuiet(quietMs: Long = 90L, maxWaitMs: Long = 300L) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < maxWaitMs) {
            val lastEvent = DeviceState.lastUiEventAtMs
            if (lastEvent == 0L || System.currentTimeMillis() - lastEvent >= quietMs) return
            Thread.sleep(20)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
