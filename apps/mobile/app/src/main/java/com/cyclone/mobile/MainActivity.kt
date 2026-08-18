package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.ai.AgentTraceRuntime
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
    }
}
