package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.capture.PhoneScreenCapture
import com.cyclone.mobile.capture.PhoneScreenCapture.ScreenCaptureException
import org.json.JSONObject

/**
 * Serves an on-demand Android screen frame over the gateway. The accessibility service screenshot
 * primitive is used (no MediaProjection service required); a compact scaled frame plus optional
 * base64 evidence is returned, and the frame path is attached to the current page for previews.
 */
internal object GatewayCaptureAdapter {
    fun capture(context: Context, args: JSONObject): JSONObject {
        val service = CycloneAccessibilityService.instance
            ?: throw GatewayProtocolException("ACCESSIBILITY_NOT_CONNECTED", "Cyclone Accessibility is not connected")
        val maxDimension = args.optInt("maxDimension", 0).takeIf { it > 0 }
        val includeBase64 = args.optBoolean("includeBase64", false)
        val frame = try {
            PhoneScreenCapture.capture(service, maxDimension, includeBase64)
        } catch (error: ScreenCaptureException) {
            throw GatewayProtocolException(error.code, error.message ?: "Screen capture failed")
        }
        val pageKey = GatewayObservationStore.current()?.page?.pageKey
        val filePath = frame.optString("filePath").takeIf { it.isNotBlank() }
        if (pageKey != null && filePath != null) {
            runCatching { PageAwarenessRuntime.store.attachPreview(pageKey, filePath) }
        }
        return frame
    }
}
