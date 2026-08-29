package com.cyclone.teamworksniper

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cyclone.teamworksniper.ai.OpenRouterSecretStore
import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.ActivityLogStore
import com.cyclone.teamworksniper.data.AiSettings
import com.cyclone.teamworksniper.data.AiSettingsStore
import com.cyclone.teamworksniper.data.RuleStore
import com.cyclone.teamworksniper.data.SettingsStore
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.SniperSettings
import com.cyclone.teamworksniper.data.UiPreferencesStore
import com.cyclone.teamworksniper.runtime.PermissionChecker
import com.cyclone.teamworksniper.runtime.PermissionState
import com.cyclone.teamworksniper.ui.SniperScreen

class MainActivity : ComponentActivity() {
    private lateinit var rules: RuleStore
    private lateinit var settings: SettingsStore
    private lateinit var aiSettings: AiSettingsStore
    private lateinit var aiSecret: OpenRouterSecretStore
    private lateinit var activity: ActivityLogStore
    private lateinit var uiPreferences: UiPreferencesStore

    private var state by mutableStateOf(
        UiState(
            settings = SniperSettings(),
            aiSettings = AiSettings(),
            aiKeyPresent = false,
            permissions = PermissionState(false, false),
            rules = emptyList(),
            activity = emptyList(),
            onboardingComplete = false,
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rules = RuleStore(this)
        settings = SettingsStore(this)
        aiSettings = AiSettingsStore(this)
        aiSecret = OpenRouterSecretStore(this)
        activity = ActivityLogStore(this)
        uiPreferences = UiPreferencesStore(this)
        refresh()

        setContent {
            SniperScreen(
                state = state,
                onSettings = {
                    settings.save(it)
                    refresh()
                },
                onRules = {
                    rules.save(it)
                    refresh()
                },
                onNotification = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                },
                onAccessibility = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                onOnboardingComplete = {
                    uiPreferences.setOnboardingComplete(true)
                    refresh()
                },
                onOpenTeamwork = ::openTeamwork,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::rules.isInitialized) refresh()
    }

    private fun openTeamwork() {
        val launch = packageManager.getLaunchIntentForPackage(TEAMWORK_PACKAGE)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        }
    }

    private fun refresh() {
        state = UiState(
            settings = settings.load(),
            aiSettings = aiSettings.load(),
            aiKeyPresent = aiSecret.hasKey(),
            permissions = PermissionChecker.read(this),
            rules = rules.load(),
            activity = activity.load(),
            onboardingComplete = uiPreferences.isOnboardingComplete(),
        )
    }

    companion object {
        private const val TEAMWORK_PACKAGE = "tech.picnic.workapp"
    }
}

data class UiState(
    val settings: SniperSettings,
    val aiSettings: AiSettings,
    val aiKeyPresent: Boolean,
    val permissions: PermissionState,
    val rules: List<ShiftRule>,
    val activity: List<ActivityEntry>,
    val onboardingComplete: Boolean,
)
