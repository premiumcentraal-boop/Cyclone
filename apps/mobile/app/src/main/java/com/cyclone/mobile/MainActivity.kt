package com.cyclone.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.ui.CycloneMobileV25App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        BridgeClient.start(this)
        setContent {
            CycloneMobileV25App()
        }
    }

    override fun onResume() {
        super.onResume()
        AutomationRuntime.initialize(this)
        AppLearnerRuntime.initialize(this)
        BridgeClient.start(this)
    }
}
