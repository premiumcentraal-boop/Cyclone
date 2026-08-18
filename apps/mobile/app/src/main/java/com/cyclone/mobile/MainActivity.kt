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
        text("First non-root mobile node. Built features are checked separately from on-device verification so the app never claims a test that has not happened.")

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
        text("The v0 parser currently recognizes same-day HH:MM-HH:MM notification text. Real Teamwork/Picnic formatting still needs capture and device verification before this is trusted unattended.")

        section("Human takeover")
        status("Controller: ${DeviceState.controller.name}", true)
        button(if (DeviceState.controller == DeviceState.Controller.AGENT) "Take control from agent" else "Return control to agent") {
            DeviceState.controller = if (DeviceState.controller == DeviceState.Controller.AGENT) DeviceState.Controller.HUMAN else DeviceState.Controller.AGENT
            buildUi()
        }

        section("Build checklist")
        built("Android 14+ APK target (minSdk 34)")
        built("Accessibility UI-tree observation")
        built("Semantic click + set text")
        built("Tap / swipe / scroll / Back / Home")
        built("Accessibility screenshot capture")
        built("Notification listener")
        built("Calendar conflict matcher")
        built("Work-shift notification routine scaffold")
        built("Safe dry-run / explicit auto-claim opt-in")
        built("Cyclone WebSocket command bridge")
        built("Human/agent controller lock")
        built("GitHub Actions APK build pipeline")

        section("Verification checklist")
        verified("APK compiled in GitHub CI", true)
        verified("APK installed on Android 14+ device", false)
        verified("Accessibility tree read from real phone", false)
        verified("Screenshot returned from real phone", false)
        verified("Remote semantic click performed", false)
        verified("Notification received from real work app", false)
        verified("Real Teamwork/Picnic UI mapped", false)
        verified("Calendar conflict test passed", false)
        verified("Eligible shift detected correctly", false)
        verified("Claim action verified end-to-end", false)
        verified("Cyclone Core WebSocket endpoint connected", DeviceState.bridgeConnected)
        verified("Takeover blocks agent actions", false)
        verified("24-hour reliability test", false)

        section("Next")
        text("1. Install this CI-built APK on an Android 14+ phone.\n2. Enable Accessibility + Notification access + Calendar.\n3. Run observe/screenshot/click acceptance tests.\n4. Capture one real Teamwork notification and UI tree.\n5. Replace the generic shift parser with the real app state machine.\n6. Connect to Cyclone Core and add event-driven Hermes fallback/takeover.\n7. Run restart, reliability, and battery soak tests.")
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
