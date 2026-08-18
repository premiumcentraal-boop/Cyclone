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
            setPadding(40, 48, 40, 64)
        }
        scroll.addView(root)
        setContentView(scroll)

        title("Cyclone Mobile · Automation Studio")
        text("Universal Android automations built from typed phone tools. Deterministic steps run without AI; Hermes can later generate and recover workflows through the documented contract.")

        val prefs = getSharedPreferences("cyclone", Context.MODE_PRIVATE)

        section("Ask Cyclone")
        val aiRequest = edit("Describe what you want Cyclone to do", prefs.getString("pendingAiBuildRequest", "") ?: "")
        button("Save AI Build request") {
            prefs.edit().putString("pendingAiBuildRequest", aiRequest.text.toString().trim()).apply()
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
                setOnCheckedChangeListener { _, checked -> AutomationRuntime.store.saveAutomation(automation.copy(enabled = checked)) }
            }
            root.addView(enabled)
            if (automation.description.isNotBlank()) text(automation.description)
            text("Trigger: ${automation.trigger.type} · ${automation.steps.size} steps · failure ${automation.failureBehavior}")
            button("Run · ${automation.name}") {
                AutomationRuntime.router.runManual(automation.id)
                DeviceState.addLog("Manual automation queued: ${automation.name}")
            }
        }

        section("Automation editor")
        text("Lightweight structured form for a first trigger + condition + phone action + success criterion. Complex workflows can be created by Recorder or Agent 3 and edited in the persisted typed format.")
        val draftName = edit("Automation name", "New automation")
        val triggerType = edit("Trigger type (MANUAL / NOTIFICATION / SCHEDULE / APP_OPENED / CYCLONE_REMOTE / WEBSOCKET / CALENDAR_TIME)", "MANUAL")
        val triggerPackage = edit("Trigger package filter (optional)", "")
        val triggerText = edit("Trigger text/event filter (optional)", "")
        val conditionLeft = edit("Condition left value or variable placeholder (optional)", "")
        val conditionOperator = edit("Condition operator", "EQUALS")
        val conditionRight = edit("Condition right value", "")
        val actionTool = edit("Phone tool action", "phone.open_app")
        val actionPackage = edit("Action package argument (optional)", "com.android.settings")
        val actionSelectorText = edit("Action selector text (optional)", "")
        val confirmation = CheckBox(this).apply { text = "Require human confirmation before action" }
        root.addView(confirmation)
        val successLeft = edit("Success criterion left value or variable placeholder (optional)", "")
        val successOperator = edit("Success operator", "EQUALS")
        val successRight = edit("Success right value", "")
        val failureBehavior = edit("Failure behavior (ABORT / REQUEST_HUMAN / REQUEST_AI_HELP / GO_BACK / RESTART_APP / RETRY)", "ABORT")
        button("Create structured automation") {
            val triggerKind = parseTriggerType(triggerType.text.toString())
            val triggerParams = mutableMapOf<String, String>()
            triggerPackage.text.toString().trim().takeIf { it.isNotBlank() }?.let { triggerParams["package"] = it }
            triggerText.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                triggerParams[if (triggerKind == TriggerType.WEBSOCKET) "eventType" else "text"] = it
            }
            val conditions = conditionLeft.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                listOf(ConditionDefinition(left = it, operator = parseOperator(conditionOperator.text.toString()), right = conditionRight.text.toString()))
            }.orEmpty()
            val parameters = mutableMapOf("tool" to actionTool.text.toString().trim().ifBlank { "phone.observe" })
            actionPackage.text.toString().trim().takeIf { it.isNotBlank() }?.let { parameters["package"] = it }
            val selector = actionSelectorText.text.toString().trim().takeIf { it.isNotBlank() }?.let { Selector(text = it) }
            val verification = successLeft.text.toString().trim().takeIf { it.isNotBlank() }?.let {
                listOf(ConditionDefinition(left = it, operator = parseOperator(successOperator.text.toString()), right = successRight.text.toString()))
            }.orEmpty()
            val definition = AutomationDefinition(
                name = draftName.text.toString().trim().ifBlank { "New automation" },
                trigger = TriggerDefinition(triggerKind, triggerParams),
                conditions = conditions,
                steps = listOf(
                    StepDefinition(
                        name = actionTool.text.toString().trim().ifBlank { "Phone action" },
                        type = StepType.PHONE_TOOL,
                        parameters = parameters,
                        selector = selector,
                        confirmationRequired = confirmation.isChecked
                    )
                ),
                verification = verification,
                failureBehavior = parseFailureAction(failureBehavior.text.toString())
            )
            AutomationRuntime.store.saveAutomation(definition)
            DeviceState.addLog("Structured automation created: ${definition.name}")
            buildUi()
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
        val recordName = edit("Recorded automation name", prefs.getString("recordingName", "Recorded phone workflow") ?: "Recorded phone workflow")
        status("Recorder active", AutomationRuntime.recorder.isRecording())
        if (!AutomationRuntime.recorder.isRecording()) {
            button("Start recording") {
                prefs.edit().putString("recordingName", recordName.text.toString()).apply()
                AutomationRuntime.recorder.start()
                DeviceState.addLog("Automation recorder started")
                buildUi()
            }
        } else {
            text("Captured normalized steps: ${AutomationRuntime.recorder.snapshot().size}. Accessibility event hooks are exposed for Agent 1; raw coordinates are not the preferred recording format.")
            AutomationRuntime.recorder.snapshot().forEachIndexed { index, step ->
                text("${index + 1}. ${step.name} · ${step.parameters["tool"] ?: step.type}")
                if (index > 0) button("Move step ${index + 1} up") { AutomationRuntime.recorder.moveStep(index, index - 1); buildUi() }
                if (index < AutomationRuntime.recorder.snapshot().lastIndex) button("Move step ${index + 1} down") { AutomationRuntime.recorder.moveStep(index, index + 1); buildUi() }
                button("Delete step ${index + 1}") { AutomationRuntime.recorder.removeStep(index); buildUi() }
            }
            button("Stop and save recording") {
                val definition = AutomationRuntime.recorder.stop(prefs.getString("recordingName", "Recorded phone workflow").orEmpty().ifBlank { "Recorded phone workflow" })
                AutomationRuntime.store.saveAutomation(definition)
                DeviceState.addLog("Recorded automation saved: ${definition.name}")
                buildUi()
            }
            button("Cancel recording") { AutomationRuntime.recorder.cancel(); buildUi() }
        }

        section("Runs")
        val runs = AutomationRuntime.store.listRuns(12)
        if (runs.isEmpty()) text("No automation runs yet.")
        runs.forEach { run ->
            text("${run.state} · ${run.automationName} · ${formatTime(run.startedAt)} · ${run.steps.count { it.state.name == "SUCCESS" }}/${run.steps.size} steps${run.error?.let { " · $it" } ?: ""}")
            run.steps.takeLast(5).forEach { step -> text("  ${step.startedAt?.let(::formatTime) ?: "--:--:--"} · ${step.state} · ${step.name}${step.message?.let { " · $it" } ?: ""}") }
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
        built("Selector-preserving recorder core with edit/delete/reorder UI")
        built("Structured trigger/condition/action/verification/failure automation editor")
        built("Automation, Skill, Recorder, Runs, Devices, Permissions and Connection UI sections")
        verified("Existing Android APK build gate previously passed on base branch", true)
        verified("This Automation Studio branch compiled in CI", false)
        verified("Automation executed on physical Android 14+ device", false)
        verified("Recorder captured real Accessibility events", false)
        verified("Agent 1 PhoneToolGateway adapter merged", false)
        verified("Agent 3 AI Build compiler connected", false)
    }

    private fun parseTriggerType(raw: String): TriggerType = runCatching { TriggerType.valueOf(raw.trim().uppercase()) }.getOrDefault(TriggerType.MANUAL)
    private fun parseOperator(raw: String): ConditionOperator = runCatching { ConditionOperator.valueOf(raw.trim().uppercase()) }.getOrDefault(ConditionOperator.EQUALS)
    private fun parseFailureAction(raw: String): FailureAction = runCatching { FailureAction.valueOf(raw.trim().uppercase()) }.getOrDefault(FailureAction.ABORT)
    private fun formatTime(epochMillis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))

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
