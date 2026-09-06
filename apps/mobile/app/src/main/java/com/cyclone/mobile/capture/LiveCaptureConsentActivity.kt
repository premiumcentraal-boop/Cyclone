package com.cyclone.mobile.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** The OS owns screen-capture consent; a token is used for exactly one capture session. */
class LiveCaptureConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(manager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()), CONSENT)
        }
    }

    @Deprecated("Activity result retained for this isolated consent-only activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CONSENT) {
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(Intent(this, LiveCaptureService::class.java)
                    .putExtra("resultCode", resultCode).putExtra("consent", data))
            }
            finish()
        }
    }

    companion object { private const val CONSENT = 901 }
}
