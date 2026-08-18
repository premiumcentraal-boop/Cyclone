package com.cyclone.mobile

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 64)
        }
        scroll.addView(root)
        setContentView(scroll)

        title("Cyclone Mobile · Android 14+")
        text("Universal non-root phone toolbox. Built features are tracked separately from physical-device verification.")

        section("Device permissions")
        status("Accessibility enabled", accessibilityEnabled())
        button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        status("Notification access enabled", notificationAccessEnabled())
        button("Open Notification access") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        val calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        status("Calendar permission granted", calendarGranted)
        if (!calendarGranted) button("Grant Calendar permission") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100) }

        section("Cyclone connection")
        val prefs = getSharedPreferences("cyclone", Context.MODE_PRIVATE)
        val url = edit("Cyclone Core WebSocket URL", prefs.getString("coreWsUrl", "") ?: "")
        val token = edit("Pairing/Bearer token", prefs.getString("coreToken", "") ?: "", secret = true)
        button("Save connection") {
            prefs.edit().putString("coreWsUrl", url.text.toString().trim()).putString("coreToken", token.text.toString()).apply()
            BridgeClient.stop()
            CycloneAccessibilityService.instance?.let { BridgeClient.start(it) }
            buildUi()
        }
        status("Bridge connected", DeviceState.bridgeConnected)

        section("Work-shift routine (safe by default)")
        val packageFilter = edit("Work app package filter", prefs.getString("workPackage", "") ?: "")
        val claimText = edit("Claim button text", prefs.getString("claimText", "claim") ?: "claim")
        val auto = CheckBox(this).apply {
            text = "Enable real auto-claim (OFF by default)"
            isChecked = prefs.getBoolean("autoClaimEnabled", false)
        }
        root.addView(auto)
        button("Save routine settings") {
            prefs.edit().putString("workPackage", packageFilter.text.toString().trim())
                .putString("claimText", claimText.text.toString().trim())
                .putBoolean("autoClaimEnabled", auto.isChecked).apply()
        }
        text("Picnic/Teamwork remains one optional workflow. The underlying device layer is now being generalized for arbitrary Android apps.")

        section("Human takeover")
        status("Controller: ${DeviceState.controller.name}", true)
        if (DeviceState.requireFreshObservation) status("Fresh observation required before next agent action", false)
        button(if (DeviceState.controller == DeviceState.Controller.AGENT) "Take control from agent" else "Return control to agent") {
            val target = if (DeviceState.controller == DeviceState.Controller.AGENT) DeviceState.Controller.HUMAN else DeviceState.Controller.AGENT
            DeviceState.setController(target)
            buildUi()
        }

        section("Universal toolbox · built")
        built("Typed phone.* tool protocol")
        built("Normalized flat UI snapshots + screen fingerprints")
        built("Selectors: IDs, text, descriptions, class/role, hierarchy, coordinates, relative and fuzzy")
        built("Click / long press / text / tap / swipe / scroll / Back / Home")
        built("Wait/assert + retries + duplicate suppression + action evidence")
        built("Accessibility screenshot + crop metadata")
        built("App launch + notification open + clipboard + safe intents/share")
        built("Capability registry")
        built("Human/agent controller lock with forced fresh observe")
        built("Privacy-conscious command audit records")
        built("Notification listener + calendar matcher")
        built("Cyclone WebSocket command bridge")

        section("Verification checklist")
        verified("APK compiles in GitHub CI", true)
        verified("Universal toolbox branch compiles in GitHub CI", false)
        verified("APK installed on Android 14+ device", false)
        verified("Normalized UI snapshot read from real phone", false)
        verified("Screenshot returned from real phone", false)
        verified("Selector-based click performed", false)
        verified("Long press / swipe / scroll verified", false)
        verified("App launch + notification open verified", false)
        verified("Clipboard tools verified", false)
        verified("Takeover blocks agent actions and requires fresh observe", false)
        verified("Stale UI recovery verified", false)
        verified("24-hour reliability test", false)

        section("Next")
        text("1. Build this Agent 1 branch in CI.\n2. Install on Android 14+ hardware.\n3. Run the phone-tool acceptance checklist.\n4. Feed the typed PhoneToolRegistry contract to Automation Studio and Hermes agents.\n5. Keep vision as a fallback only after structured selectors fail.")
    }

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CycloneAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun notificationAccessEnabled(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun title(value: String) = root.addView(TextView(this).apply { text = value; textSize = 26f; setTextColor(Color.BLACK); setPadding(0, 0, 0, 12) })
    private fun section(value: String) = root.addView(TextView(this).apply { text = value; textSize = 19f; setTextColor(Color.BLACK); setPadding(0, 28, 0, 8) })
    private fun text(value: String) = root.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 8) })
    private fun status(value: String, ok: Boolean) = root.addView(TextView(this).apply { text = if (ok) "✓ $value" else "○ $value"; textSize = 15f; setPadding(0, 3, 0, 3); setTextColor(if (ok) Color.rgb(20,110,55) else Color.DKGRAY) })
    private fun built(value: String) = root.addView(CheckBox(this).apply { text = "BUILT · $value"; isChecked = true; isEnabled = false })
    private fun verified(value: String, valueOk: Boolean) = root.addView(CheckBox(this).apply { text = "VERIFIED · $value"; isChecked = valueOk; isEnabled = false })
    private fun button(label: String, action: () -> Unit) = root.addView(Button(this).apply { text = label; setOnClickListener { action() } })

    private fun edit(label: String, value: String, secret: Boolean = false): EditText {
        text(label)
        return EditText(this).apply {
            setText(value)
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            root.addView(this)
        }
    }
}
