package com.cyclone.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class CycloneAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    companion object {
        @Volatile var instance: CycloneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        DeviceState.accessibilityConnected = true
        DeviceState.addLog("Accessibility connected")
        BridgeClient.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()
        if (!pkg.isNullOrBlank()) DeviceState.currentPackage = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        DeviceState.accessibilityConnected = false
        BridgeClient.stop()
        super.onDestroy()
    }

    fun observe(): JSONObject {
        val root = rootInActiveWindow
        return JSONObject().apply {
            put("package", DeviceState.currentPackage ?: JSONObject.NULL)
            put("controller", DeviceState.controller.name.lowercase())
            put("tree", if (root != null) nodeToJson(root, 0) else JSONObject.NULL)
        }
    }

    private fun nodeToJson(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val children = JSONArray()
        if (depth < 30) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> children.put(nodeToJson(child, depth + 1)) }
            }
        }
        return JSONObject().apply {
            put("text", node.text?.toString() ?: "")
            put("description", node.contentDescription?.toString() ?: "")
            put("viewId", node.viewIdResourceName ?: "")
            put("class", node.className?.toString() ?: "")
            put("clickable", node.isClickable)
            put("scrollable", node.isScrollable)
            put("editable", node.isEditable)
            put("enabled", node.isEnabled)
            put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
            put("children", children)
        }
    }

    fun clickText(text: String): Boolean {
        if (DeviceState.controller != DeviceState.Controller.AGENT) return false
        val node = findNode(rootInActiveWindow, text) ?: return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
        return tap((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
    }

    fun setText(targetText: String, value: String): Boolean {
        if (DeviceState.controller != DeviceState.Controller.AGENT) return false
        val node = findNode(rootInActiveWindow, targetText) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scrollForward(): Boolean {
        if (DeviceState.controller != DeviceState.Controller.AGENT) return false
        val scrollable = findScrollable(rootInActiveWindow) ?: return false
        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun tap(x: Float, y: Float): Boolean {
        if (DeviceState.controller != DeviceState.Controller.AGENT) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        if (DeviceState.controller != DeviceState.Controller.AGENT) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 3000)))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun goBack(): Boolean = DeviceState.controller == DeviceState.Controller.AGENT && performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = DeviceState.controller == DeviceState.Controller.AGENT && performGlobalAction(GLOBAL_ACTION_HOME)

    fun takeScreenshot(callback: (Result<File>) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                runCatching {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: error("Unable to map screenshot buffer")
                    val file = File(cacheDir, "cyclone-${System.currentTimeMillis()}.png")
                    FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 95, output) }
                    result.hardwareBuffer.close()
                    DeviceState.lastScreenshotPath = file.absolutePath
                    file
                }.also(callback)
            }

            override fun onFailure(errorCode: Int) {
                callback(Result.failure(IllegalStateException("Screenshot failed: $errorCode")))
            }
        })
    }

    private fun findNode(node: AccessibilityNodeInfo?, query: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val q = query.lowercase()
        if ((node.text?.toString()?.lowercase()?.contains(q) == true) ||
            (node.contentDescription?.toString()?.lowercase()?.contains(q) == true)) return node
        for (i in 0 until node.childCount) findNode(node.getChild(i), query)?.let { return it }
        return null
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }
}
