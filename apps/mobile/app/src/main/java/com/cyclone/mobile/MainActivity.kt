package com.cyclone.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.TaskResultNotifierV292
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.gateway.GatewayDesktopPairingManager
import com.cyclone.mobile.infrastructure.v31.CycloneV31ProductIntegration
import com.cyclone.mobile.infrastructure.v31.CycloneV31Runtime
import com.cyclone.mobile.ui.v32.CycloneMobileV32App
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCyclone()
        migrateModelDefault()
        migrateCanonicalLearning()
        TaskResultNotifierV292.ensureChannel(this)
        setContent {
            // Android 15 enforces edge-to-edge for targetSdk 35. Keep Cyclone's interactive shell
            // inside the status-bar safe area so the top controls never compete with Wi-Fi/battery.
            Box(Modifier.fillMaxSize().statusBarsPadding()) {
                CycloneMobileV32App()
            }
        }
        handlePairingIntent(intent)
        handleOverlayVoiceIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
        handleOverlayVoiceIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        initializeCyclone()
        CycloneV31Runtime.servicesOrNull()?.refreshHealth()
    }

    /**
     * Keep the proven Cyclone product runtimes as compatibility providers, then install V3.1 as the
     * single supervising policy/capability/memory/recovery layer around them. All calls are
     * idempotent, so Activity resume cannot create duplicate runtimes or phone action engines.
     */
    private fun initializeCyclone() {
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        PageAwarenessRuntime.initialize(this)
        AgentTraceRuntime.initialize(this)
        CycloneBrainRuntime.initialize(this)
        AdaptiveBrainRuntime.initialize(this)
        BrainChatRuntime.initialize(this)
        RoutineTeachingRuntime.initialize(this)

        val v31 = CycloneV31Runtime.initialize(this)
        CycloneV31ProductIntegration.install(this, v31)
        CycloneV31ProductIntegration.finalizeStartupWhenReady(v31)
    }

    private fun handlePairingIntent(value: Intent?) {
        val uri = value?.data ?: return
        if (value.action != Intent.ACTION_VIEW || uri.scheme != "cyclone" || uri.host != "pair") return
        val approved = GatewayDesktopPairingManager.approveQrPayload(uri.toString())
        Toast.makeText(
            this,
            if (approved) "PC pairing approved. Return to Cyclone on your PC."
            else "This pairing QR code is invalid or expired. Request a new code on your PC.",
            Toast.LENGTH_LONG,
        ).show()
        value.data = null
    }

    private fun handleOverlayVoiceIntent(value: Intent?) {
        if (value?.action != ACTION_REQUEST_OVERLAY_VOICE) return
        value.action = Intent.ACTION_MAIN
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            moveTaskToBack(true)
            OverlayChromeRuntime.beginVoiceInput()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_OVERLAY_VOICE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_OVERLAY_VOICE_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            moveTaskToBack(true)
            OverlayChromeRuntime.beginVoiceInput()
        } else {
            Toast.makeText(this, "Voice requests stay off until microphone access is allowed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun migrateModelDefault() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("model_default_migrated", false)) return
        val selected = prefs.getString("openrouter_model", null)
        if (selected.isNullOrBlank()) prefs.edit().putString("openrouter_model", OpenRouterModelPresets.DEFAULT.id).apply()
        prefs.edit().putBoolean("model_default_migrated", true).apply()
    }

    /** Keep one canonical post-mission consolidator and disable the old optional duplicate worker. */
    private fun migrateCanonicalLearning() {
        val prefs = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
        if (prefs.getBoolean("v292_learning_migrated", false)) return
        prefs.edit()
            .putBoolean("cloud_brain_refinement", false)
            .putBoolean("v292_learning_migrated", true)
            .apply()
    }

    companion object {
        const val ACTION_REQUEST_OVERLAY_VOICE = "com.cyclone.mobile.action.REQUEST_OVERLAY_VOICE"
        private const val REQUEST_OVERLAY_VOICE_PERMISSION = 385
    }
}
