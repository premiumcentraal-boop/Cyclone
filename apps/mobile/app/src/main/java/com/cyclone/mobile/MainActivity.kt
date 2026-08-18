package com.cyclone.mobile

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.AiTraceOverlayRuntime
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.ui.CycloneMobileV26App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        AgentTraceRuntime.initialize(this)
        CycloneBrainRuntime.initialize(this)
        BridgeClient.start(this)
        restoreTraceOverlayIfEnabled()
        setContent {
            CycloneMobileV26App()
        }
    }

    override fun onResume() {
        super.onResume()
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        AgentTraceRuntime.initialize(this)
        CycloneBrainRuntime.initialize(this)
        BridgeClient.start(this)
        restoreTraceOverlayIfEnabled()
    }

    private fun restoreTraceOverlayIfEnabled() {
        val enabled = getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
            .getBoolean("trace_overlay", false)
        val service = CycloneAccessibilityService.instance
        if (enabled && service != null) AiTraceOverlayRuntime.enable(service)
        if (!enabled) AiTraceOverlayRuntime.disable()
    }
}
