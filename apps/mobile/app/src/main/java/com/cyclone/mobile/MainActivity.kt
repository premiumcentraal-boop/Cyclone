package com.cyclone.mobile

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
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
import com.cyclone.mobile.automation.ConditionDefinition
import com.cyclone.mobile.automation.ConditionOperator
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            setPadding(40, 48, 40, 72)
        }
        scroll.addView(root)
        setContentView(scroll)
        val prefs = getSharedPreferences("cyclone", Context.MODE_PRIVATE)

        title("Cyclone Mobile V2")
        text("Universal Android 14+ control, deterministic Automation Studio, Skills, Hermes/Core pairing and optional Mobilerun-enhanced backend.")

        section("Ready status")
        status("Accessibility service", accessibilityEnabled())
        status("Notification access", notificationAccessEnabled())
        status("Calendar permission", checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
        status("Cyclone Core bridge", DeviceState.bridgeConnected)
        text("Current app: ${DeviceState.currentPackage ?: "unknown"} · Controller: ${DeviceState.controller.name}")
        if (DeviceState.requireFreshObservation) warning("Agent must observe the current screen before acting again.")

        section("Cyclone Core connection")
        val defaultId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)?.let { "android-$it" }.orEmpty()
        val defaultName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val url = edit("Core WebSocket URL", prefs.getString("coreWsUrl", "") ?: "")
        val token = edit("Pairing/Bearer token", prefs.getString("coreToken", "") ?: "", secret = true)
        val deviceId = edit("Device ID", prefs.getString("deviceId", defaultId) ?: defaultId)
        val deviceName = edit("Device name", prefs.getString("deviceName", defaultName) ?: defaultName)
        button("Save & reconnect") {
            prefs.edit()
                .putString("coreWsUrl", url.text.toString().trim())
                .putString("coreToken", token.text.toString())
                .putString("deviceId", deviceId.text.toString().trim())
                .putString("deviceName", deviceName.text.toString().trim())
                .apply()
            BridgeClient.stop()
            CycloneAccessibilityService.instance?.let { BridgeClient.start(it) }
            buildUi()
        }
        text("For Hermes remote control, point this to Cyclone Core's authenticated /api/v1/mobile/connect WebSocket endpoint.")

        section("Permissions")
        button("Open Accessibility settings") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Open Notification access") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) }
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            button("Grant Calendar permission") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 100) }
        }

        section("Human takeover")
        button(if (DeviceState.controller == DeviceState.Controller.AGENT) "Take control from agent" else "Return control to agent") {
            val target = if (DeviceState.controller == DeviceState.Controller.AGENT) DeviceState.Controller.HUMAN else DeviceState.Controller.AGENT
            DeviceState.setController(target)
            buildUi()
        }
        text("When human control is returned, mutating phone tools remain locked until a fresh phone.observe succeeds.")

        section("Ask Cyclone")
        val aiRequest = edit("Describe an automation for Hermes to build", prefs.getString("pendingAiBuildRequest", "") ?: "")
        button("Save request") {
            prefs.edit().putString("pendingAiBuildRequest", aiRequest.text.toString().trim()).apply()
            BridgeClient.sendAutomationEvent("automation.build_request", mapOf("request" to aiRequest.text.toString().trim()))
            DeviceState.addLog("Automation build request saved/sent")
            buildUi()
        }
        text("AI-generated automations are compiled through the strict proposal compiler and stay disabled until reviewed.")

        section("Automations")
        val automations = AutomationRuntime.store.listAutomations()
        if (automations.isEmpty()) text("No automations yet.")
        automations.forEach { automation ->
            val enabled = CheckBox(this).apply {
                text = automation.name
                isChecked = automation.enabled
                setOnCheckedChangeListener { _, checked ->
                    val updated = automation.copy(enabled = checked)
                    AutomationRuntime.store.saveAutomation(updated)
                    if (updated.trigger.type == TriggerType.SCHEDULE) AutomationRuntime.registerSchedule(this@MainActivity, updated)
                }
            }
            root.addView(enabled)
            if (automation.description.isNotBlank()) text(automation.description)
            text("${automation.trigger.type} · ${automation.steps.size} steps · failure ${automation.failureBehavior}")
            button("Run · ${automation.name}") {
                AutomationRuntime.router.runManual(automation.id)
                DeviceState.addLog("Manual automation queued: ${automation.name}")
            }
        }

        section("Quick automation builder")
        val draftName = edit("Name", "New automation")
        val triggerType = edit("Trigger (MANUAL / NOTIFICATION / SCHEDULE / APP_OPENED / CYCLONE_REMOTE / WEBSOCKET / CALENDAR_TIME)", "MANUAL")
        val triggerFilter = edit("Trigger package/text filter (optional)", "")
        val tool = edit("Phone tool", "phone.open_app")
        val packageArg = edit("Package argument (optional)", "com.android.settings")
        val selectorText = edit("Selector text (optional)", "")
        val requireConfirmation = CheckBox(this).apply { text = "Require human confirmation" }
        root.addView(requireConfirmation)
        button("Create automation") {
            val kind = parseTriggerType(triggerType.text.toString())
            val filter = triggerFilter.text.toString().trim()
            val triggerParams = when {
                filter.isBlank() -> emptyMap()
                kind == TriggerType.NOTIFICATION || kind == TriggerType.APP_OPENED -> mapOf("package" to filter)
                kind == TriggerType.WEBSOCKET -> mapOf("eventType" to filter)
                else -> mapOf("key" to filter)
            }
            val params = mutableMapOf("tool" to tool.text.toString().trim().ifBlank { "phone.observe" })
            packageArg.text.toString().trim().takeIf { it.isNotBlank() }?.let { params["package"] = it }
            val selector = selectorText.text.toString().trim().takeIf { it.isNotBlank() }?.let { Selector(text = it) }
            val definition = AutomationDefinition(
                name = draftName.text.toString().trim().ifBlank { "New automation" },
                trigger = TriggerDefinition(kind, triggerParams),
                steps = listOf(StepDefinition(name = params["tool"] ?: "Phone action", type = StepType.PHONE_TOOL,
                    parameters = params, selector = selector, confirmationRequired = requireConfirmation.isChecked)),
                failureBehavior = FailureAction.ABORT
            )
            AutomationRuntime.store.saveAutomation(definition)
            DeviceState.addLog("Automation created: ${definition.name}")
            buildUi()
        }

        section("Skills")
        val skills = AutomationRuntime.store.listSkills()
        if (skills.isEmpty()) text("No skills saved yet.")
        skills.forEach { skill -> text("${skill.name} · ${skill.steps.size} steps · v${skill.version}") }
        if (skills.none { it.id == "skill-open-settings" }) {
            button("Add example skill: Open Settings") {
                AutomationRuntime.store.saveSkill(SkillDefinition(
                    id = "skill-open-settings", name = "Open Android Settings", description = "Deterministic reusable example.",
                    steps = listOf(StepDefinition(id = "skill-open-settings-step", name = "Open Settings", type = StepType.PHONE_TOOL,
                        parameters = mapOf("tool" to "phone.open_app", "package" to "com.android.settings")))
                ))
                buildUi()
            }
        }

        section("Recorder")
        val recordingName = edit("Recording name", prefs.getString("recordingName", "Recorded workflow") ?: "Recorded workflow")
        status("Recorder active", AutomationRuntime.recorder.isRecording())
        if (!AutomationRuntime.recorder.isRecording()) {
            button("Start recording") {
                prefs.edit().putString("recordingName", recordingName.text.toString()).apply()
                AutomationRuntime.recorder.start()
                buildUi()
            }
        } else {
            text("Captured ${AutomationRuntime.recorder.snapshot().size} semantic steps. App opens and supported Accessibility events are normalized instead of storing raw credentials.")
            AutomationRuntime.recorder.snapshot().forEachIndexed { index, step ->
                text("${index + 1}. ${step.name} · ${step.parameters["tool"] ?: step.type}")
                button("Delete step ${index + 1}") { AutomationRuntime.recorder.removeStep(index); buildUi() }
            }
            button("Stop & save") {
                val definition = AutomationRuntime.recorder.stop(prefs.getString("recordingName", "Recorded workflow").orEmpty().ifBlank { "Recorded workflow" })
                AutomationRuntime.store.saveAutomation(definition)
                buildUi()
            }
            button("Cancel") { AutomationRuntime.recorder.cancel(); buildUi() }
        }

        section("Runs")
        val runs = AutomationRuntime.store.listRuns(12)
        if (runs.isEmpty()) text("No runs yet.")
        runs.forEach { run ->
            text("${run.state} · ${run.automationName} · ${formatTime(run.startedAt)} · ${run.steps.count { it.state.name == "SUCCESS" }}/${run.steps.size}${run.error?.let { " · $it" } ?: ""}")
            run.steps.takeLast(4).forEach { step -> text("  ${step.state} · ${step.name}${step.message?.let { " · $it" } ?: ""}") }
            if (run.state.name == "WAITING_FOR_HUMAN") {
                button("Return control & resume · ${run.automationName}") {
                    DeviceState.setController(DeviceState.Controller.AGENT)
                    AutomationRuntime.router.resume(run.id)
                    buildUi()
                }
            }
        }

        section("V2 phone toolbox")
        built("Semantic Accessibility snapshots + stable selectors")
        built("Click / long press / tap / type / scroll / swipe / Back / Home")
        built("Screenshots, app launch, notifications, clipboard, safe intents/share")
        built("wait_for / assert, retries, idempotency and duplicate suppression")
        built("HUMAN/AGENT ownership with fresh-observation safety")
        built("Automation Studio, Skills, schedules, notifications, recorder and checkpoints")
        built("Hermes/Core authenticated device identity headers and phone-tool wire protocol")
        built("Optional Mobilerun Portal compatibility backend in Cyclone Core")

        section("About enhanced Mobilerun mode")
        text("Cyclone V2 works with its native Android toolbox. Cyclone Core can additionally use Mobilerun Portal as an enhanced backend. Portal remains a separately installed AGPL component; Cyclone does not silently bundle or relicense it.")
    }

    private fun parseTriggerType(raw: String): TriggerType = runCatching { TriggerType.valueOf(raw.trim().uppercase()) }.getOrDefault(TriggerType.MANUAL)
    private fun formatTime(epochMillis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

    private fun accessibilityEnabled(): Boolean {
        val expected = ComponentName(this, CycloneAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun notificationAccessEnabled(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun title(value: String) = root.addView(TextView(this).apply { text = value; textSize = 27f; setTextColor(Color.BLACK); setPadding(0, 0, 0, 12) })
    private fun section(value: String) = root.addView(TextView(this).apply { text = value; textSize = 19f; setTextColor(Color.BLACK); setPadding(0, 28, 0, 8) })
    private fun text(value: String) = root.addView(TextView(this).apply { text = value; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(0, 4, 0, 8) })
    private fun warning(value: String) = root.addView(TextView(this).apply { text = "! $value"; textSize = 14f; setTextColor(Color.rgb(160, 80, 0)); setPadding(0, 4, 0, 8) })
    private fun status(value: String, ok: Boolean) = root.addView(TextView(this).apply { text = if (ok) "✓ $value" else "○ $value"; textSize = 15f; setPadding(0, 3, 0, 3); setTextColor(if (ok) Color.rgb(20,110,55) else Color.DKGRAY) })
    private fun built(value: String) = root.addView(CheckBox(this).apply { text = value; isChecked = true; isEnabled = false })
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
