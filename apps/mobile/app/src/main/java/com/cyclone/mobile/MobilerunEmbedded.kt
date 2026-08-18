package com.cyclone.mobile

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.PortalService
import com.mobilerun.portal.ui.MainActivity as MobilerunMainActivity
import com.mobilerun.portal.ui.settings.SettingsActivity as MobilerunSettingsActivity
import com.mobilerun.portal.ui.taskprompt.TaskHistoryActivity
import com.mobilerun.portal.ui.triggers.TriggerRulesActivity

/**
 * Thin host-side facade around the upstream Mobilerun Portal runtime that is
 * compiled into the Cyclone APK from the pinned git submodule.
 *
 * Cyclone keeps its own phone.* contract and safety layer. This facade only
 * exposes lifecycle/status/navigation for the embedded upstream runtime.
 */
object MobilerunEmbedded {
    fun accessibilityConnected(): Boolean = MobilerunAccessibilityService.getInstance() != null

    fun portalServiceRunning(): Boolean = PortalService.getInstance() != null

    fun startPortalService(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, PortalService::class.java),
        )
        DeviceState.addLog("Embedded Mobilerun Portal service start requested")
    }

    fun stopPortalService(context: Context) {
        context.stopService(Intent(context, PortalService::class.java))
        DeviceState.addLog("Embedded Mobilerun Portal service stop requested")
    }

    fun openPortalDashboard(context: Context) {
        context.startActivity(Intent(context, MobilerunMainActivity::class.java))
    }

    fun openPortalSettings(context: Context) {
        context.startActivity(Intent(context, MobilerunSettingsActivity::class.java))
    }

    fun openTriggers(context: Context) {
        context.startActivity(Intent(context, TriggerRulesActivity::class.java))
    }

    fun openTaskHistory(context: Context) {
        context.startActivity(Intent(context, TaskHistoryActivity::class.java))
    }
}
