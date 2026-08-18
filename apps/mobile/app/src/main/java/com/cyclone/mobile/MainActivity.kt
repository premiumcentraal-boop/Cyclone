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
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType

class MainActivity : Activity() {
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntime.initialize(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        AutomationRuntime.initialize(this)
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

        title("Cyclone Mobile · Automation Studio")
        text("Universal Android automations built from typed phone tools. Deterministic steps run without AI; Hermes can later generate and recover workflows through the documented contract.")

        section("Ask Cyclone")
        val prefs = getSharedPreferences("cyclone", Context.MODE_PRIVATE)
        val aiRequest = edit("Describe what you want Cyclone to do", prefs.getString("pendingAiBuildRequest", "") ?: "")
        button("Save AI Build request") {
            val request = aiRequest.text.toString().trim()
            prefs.edit().putString("pendingAiBuildRequest", request).apply()
            DeviceState.addLog("AI Build request saved for Agent 3 integration")
            buildUi()
        }
        text("Agent 2 stores the request boundary only. Agent 3 must compile it into validated typed workflow objects before activation.")

        section("Automations")
        val automations = AutomationRuntime.store.listAutomations()
        if (automations.isEmpty()) text("No automations yet.")
        automations.forEach { automation ->
            val enabled = CheckBox(this).apply {
                text = automation.name
                isChecked = automation.enabled
                setOnCheckedChangeListener { _, checked ->
                    AutomationRuntime.store.saveAutomation(automation.copy(enabled = checked))
                }
            }
            root.addView(enabled)
            if (automation.description.isNotBlank()) text(automation.description)
            text("Trigger: ${automation.trigger.type} · ${automation.steps.size} steps")
            button("Run · ${automation.name}") {
                AutomationRuntime.router.runManual(automation.id)
                DeviceState.addLog("Manual automation queued: ${automation.name}")
            }
        }

        section("Skills")
        val skills = AutomationRuntime.store.listSkills()
        if (skills.isEmpty()) text("No reusable skills saved yet.")
        skills.forEach { skill -> text("${skill.name} · inputs ${skill.inputs.size} · outputs ${skill.outputs.size} · ${skill.steps.size} steps") }
        if (skills.none { it.id == "skill-open-settings" }) {
            button("Add harmless example skill") {
                AutomationRuntime.store.saveSkill(
                    SkillDefinition(
                        id = "skill-open-settings",
                        name = "Open Android Settings",
                        description = "Reusable deterministic example skill.",
                        steps = listOf(
                            StepDefinition(
                                id = "skill-open-settings-step",
                                name = "Open Settings",
                                type = StepType.PHONE_TOOL,
                                parameters = mapOf("tool" to "phone.open_app", "package" to "com.android.settings")
                            )
                        )
                    )
                )
                buildUi()
            }
        }

        section("Recorder")
        val recordName = edit("Recorded automation name", "Recorded phone workflow")
        status("Recorder active", AutomationRuntime.recorder.isRecording())
        if (!AutomationRuntime.recorder.isRecording()) {
            button("Start recording") {
                AutomationRuntime.recorder.start()
                DeviceState.addLog("Automation recorder started")
                buildUi()
            }
        } else {
            text("Captured normalized steps: ${AutomationRuntime.recorder.snapshot().size}. Accessibility event hooks are exposed for Agent 1; raw coordinates are not the preferred recording format.")
            button("Stop and save recording") {
                val definition: AutomationDefinition = AutomationRuntime.recorder.stop(recordName.text.toString().trim().ifBlank { "Recorded phone workflow" })
                AutomationRuntime.store.saveAutomation(definition)
                DeviceState.addLog("Recorded automation saved: ${definition.name}")
                buildUi()
            }
            button("Cancel recording") {
                AutomationRuntime.recorder.cancel()
                buildUi()
            }
        }

        section("Runs")
        val runs = AutomationRuntime.store.listRuns(12)
        if (runs.isEmpty()) text("No automation runs yet.")
        runs.forEach { run ->
            text("${run.state} · ${run.automationName} · ${run.steps.count { it.state.name == "SUCCESS" }}/${run.steps.size} steps${run.error?.let { " · $it" } ?: ""}")
            if (run.state.name == "WAITING_FOR_HUMAN") {
                button("Resume · ${run.automationName}") {
                    DeviceState.controller = DeviceState.Controller.AGENT
                    AutomationRuntime.router.resume(run.id)
                    DeviceState.addLog("Automation resume requested: ${run.id}")
                }
            }
        }

        section("Devices")
        status("Accessibility connected", DeviceState.accessibilityConnected)
        status("Cyclone bridge connected", DeviceState.bridgeConnected)
        text("Current app: ${DeviceState.currentPackage ?: "unknown"}")
        text("Controller: ${DeviceState.controller.name}")
        button(if (DeviceState.controller == DeviceState.Controller.AGENT) "Take control from agent" else "Return control to agent") {
            DeviceState.controller = if (DeviceState.controller == DeviceState.Controller.AGENT) DeviceState.Controller.HUMAN else DeviceState.Controller.AGENT
            buildUi()
        }

        section("Permissions")
        status("Accessibility enabled", accessibilityEnabled())
        button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        status("Notification access enabled", notificationAccessEnabled())
        button("Open Notification access") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        val calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        status("Calendar permission granted", calendarGranted)
        if (!calendarGranted) button("Grant Calendar permission") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100) }

        section("Cyclone connection")
        val url = edit("Cyclone Core WebSocket URL", prefs.getString("coreWsUrl", "") ?: "")
        val token = edit("Pairing/Bearer token", prefs.getString("coreToken", "") ?: "", secret = true)
        button("Save connection") {
            prefs.edit().putString("coreWsUrl", url.text.toString().trim()).putString("coreToken", token.text.toString()).apply()
            BridgeClient.stop()
            CycloneAccessibilityService.instance?.let { BridgeClient.start(it) }
            buildUi()
        }

        section("Settings")
        text("Automation definitions and skills are persisted as readable JSON-compatible data. Consequential steps can require confirmation; the default Agent 2 confirmation gateway refuses unattended confirmation and moves control to HUMAN.")
        text("Pending AI Build request: ${prefs.getString("pendingAiBuildRequest", "").orEmpty().take(140)}")

        section("Built vs verified")
        built("Typed Automation / Trigger / Condition / Step / Skill / Run / Checkpoint models")
        built("JSON automation and skill persistence")
        built("Event-driven workflow runner with retries and recovery policies")
        built("Manual, notification, schedule, app-open, Cyclone remote, WebSocket and calendar/time trigger contracts")
        built("Selector-preserving recorder core")
        built("Automation, Skill, Recorder, Runs, Devices, Permissions and Connection UI sections")
        verified("Existing Android APK build gate previously passed on base branch", true)
        verified("This Automation Studio branch compiled in CI", false)
        verified("Automation executed on physical Android 14+ device", false)
        verified("Recorder captured real Accessibility events", false)
        verified("Agent 1 PhoneToolGateway adapter merged", false)
        verified("Agent 3 AI Build compiler connected", false)
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
