package com.cyclone.teamworksniper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cyclone.teamworksniper.ai.OpenRouterSecretStore
import com.cyclone.teamworksniper.data.ActivityEntry
import com.cyclone.teamworksniper.data.ActivityLogStore
import com.cyclone.teamworksniper.data.AiSettings
import com.cyclone.teamworksniper.data.AiSettingsStore
import com.cyclone.teamworksniper.data.CalendarShiftEvent
import com.cyclone.teamworksniper.data.PhoneCalendarGateway
import com.cyclone.teamworksniper.data.RuleStore
import com.cyclone.teamworksniper.data.SettingsStore
import com.cyclone.teamworksniper.data.ShiftRule
import com.cyclone.teamworksniper.data.SniperSettings
import com.cyclone.teamworksniper.data.UiPreferencesStore
import com.cyclone.teamworksniper.rules.TargetSelectionRules
import com.cyclone.teamworksniper.runtime.PermissionChecker
import com.cyclone.teamworksniper.runtime.PermissionState
import com.cyclone.teamworksniper.runtime.TeamworkDailySync
import com.cyclone.teamworksniper.teamwork.ShiftTemplateProvider
import com.cyclone.teamworksniper.ui.SniperScreen
import java.time.LocalDate

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
                onSettings = { next ->
                    val previous = settings.load()
                    settings.save(next)
                    applyConnectionSideEffects(previous, next)
                    refresh()
                },
                onRules = {
                    rules.save(it)
                    if (settings.load().calendarSync) writeCalendar()
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
                onSyncNow = {
                    TeamworkDailySync.syncNow(this)
                    refresh()
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::rules.isInitialized) refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALENDAR) return
        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            settings.save(settings.load().copy(calendarSync = true))
            writeCalendar()
        } else {
            settings.save(settings.load().copy(calendarSync = false))
        }
        refresh()
    }

    private fun applyConnectionSideEffects(previous: SniperSettings, next: SniperSettings) {
        if (next.calendarSync && !previous.calendarSync) {
            if (hasCalendarPermission()) writeCalendar()
            else ActivityCompat.requestPermissions(this, CALENDAR_PERMISSIONS, REQ_CALENDAR)
        } else if (!next.calendarSync && previous.calendarSync) {
            PhoneCalendarGateway(this).disconnect()
        } else if (next.calendarSync) {
            writeCalendar()
        }

        if (next.teamworkDailySync && !previous.teamworkDailySync) {
            TeamworkDailySync.syncNow(this)
        } else if (!next.teamworkDailySync && previous.teamworkDailySync) {
            TeamworkDailySync.cancel(this)
        }
    }

    private fun writeCalendar() {
        if (!hasCalendarPermission()) return
        val templates = ShiftTemplateProvider()
        val claimed = claimedShiftKeys(activity.load())
        val selected = rules.load()
        val start = LocalDate.now()
        val events = (0 until 21).flatMap { offset ->
            val date = start.plusDays(offset.toLong())
            templates.forDate(date).map { shift ->
                val key = date.toString() + "|" + shift.code.name
                val status = when {
                    key in claimed -> "claimed"
                    selected.any { TargetSelectionRules.isExactTarget(it, date, shift.code) } -> "sniping"
                    else -> "open"
                }
                CalendarShiftEvent(date, shift.code, shift.start, shift.end, status)
            }
        }
        PhoneCalendarGateway(this).sync(events)
    }

    private fun hasCalendarPermission(): Boolean =
        CALENDAR_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

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
        private const val REQ_CALENDAR = 91
        private val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        private fun claimedShiftKeys(entries: List<ActivityEntry>): Set<String> =
            entries.filter { it.claimResult?.contains("VERIFIED", ignoreCase = true) == true || it.verificationResult?.contains("VERIFIED", ignoreCase = true) == true }
                .flatMap { it.openShifts }
                .toSet()
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
