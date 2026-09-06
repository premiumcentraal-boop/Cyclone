package com.cyclone.mobile.capture

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Each service session consumes one new OS consent token. */
class LiveCaptureConsentActivity : Activity() {
    private var generation = 0L
    private var wholeDisplay = false
    private var consentLaunched = false
    override fun onDestroy() {
        if (!isChangingConfigurations) com.cyclone.mobile.ui.overlay.OverlayExternalInteraction.active.value = false
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wholeDisplay = intent.getBooleanExtra("wholeDisplay", false)
        generation = savedInstanceState?.getLong("generation") ?: (LiveCaptureSessionManager.request(
            if (wholeDisplay) CaptureScope.WHOLE_DISPLAY else CaptureScope.USER_CHOICE) ?: run { finish(); return })
        consentLaunched = savedInstanceState?.getBoolean("consentLaunched", false) ?: false
        if (consentLaunched) return
        if (wholeDisplay) AlertDialog.Builder(this)
            .setTitle("Share the entire screen?")
            .setMessage("Cyclone needs the entire display to see and control a task across apps. You can stop sharing at any time.")
            .setPositiveButton("Continue") { _, _ -> launchConsent() }
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }.show()
        else launchConsent()
    }
    private fun launchConsent() {
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            consentLaunched = true
            startActivityForResult(if (wholeDisplay) manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()) else manager.createScreenCaptureIntent(), CONSENT)
        }.onFailure {
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.ERROR, "Screen sharing is unavailable. Try again.")
            finish()
        }
    }
    private fun cancel() {
        LiveCaptureSessionManager.transition(generation, ScreenSharePhase.OFF)
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("generation", generation)
        outState.putBoolean("consentLaunched", consentLaunched)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity result retained for this isolated consent-only activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONSENT) return
        if (resultCode == RESULT_OK && data != null &&
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.STARTING)) {
            runCatching {
                startForegroundService(Intent(this, LiveCaptureService::class.java)
                    .putExtra("resultCode", resultCode).putExtra("consent", data)
                    .putExtra("generation", generation).putExtra("wholeDisplay", wholeDisplay))
            }.onFailure { LiveCaptureSessionManager.transition(generation, ScreenSharePhase.ERROR, "Could not start screen sharing.") }
        } else cancel()
        finish()
    }
    companion object { private const val CONSENT = 901 }
}
