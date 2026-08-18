package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainChatRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.ui.CycloneMobileV27App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeCyclone()
        setContent {
            CycloneMobileV27App()
        }
    }

    override fun onResume() {
        super.onResume()
        initializeCyclone()
    }

    private fun initializeCyclone() {
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        AgentTraceRuntime.initialize(this)
        CycloneBrainRuntime.initialize(this)
        AdaptiveBrainRuntime.initialize(this)
        BrainChatRuntime.initialize(this)
        BridgeClient.start(this)
        // V2.7 intentionally does not restore a persistent AI overlay. The adaptive agent creates
        // a task-scoped overlay only while a task is actually running and removes it after Done/Stop.
    }
}
