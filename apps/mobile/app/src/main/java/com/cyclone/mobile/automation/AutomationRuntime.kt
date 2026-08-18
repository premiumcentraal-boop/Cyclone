package com.cyclone.mobile.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState

object AutomationRuntime {
    @Volatile private var initialized = false
    lateinit var store: AutomationStore
        private set
    lateinit var recorder: AutomationRecorder
        private set
    lateinit var router: AutomationEventRouter
        private set

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        store = AutomationStore(app)
        recorder = AutomationRecorder()
        val runner = AutomationRunner(
            store = store,
            phoneTools = LegacyPhoneToolAdapter(app),
            integrations = PendingIntegrationGateway,
            confirmations = ConfirmationGateway { _, _ -> false },
            takeover = TakeoverGateway { reason, runId, stepId ->
                DeviceState.controller = DeviceState.Controller.HUMAN
                DeviceState.addLog("Automation takeover required run=$runId step=$stepId reason=$reason")
                true
            }
        )
        router = AutomationEventRouter(store, runner)
        seedExamples()
        initialized = true
    }

    fun onNotification(context: Context, packageName: String, title: String, text: String) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.NOTIFICATION, mapOf("package" to packageName, "title" to title, "text" to text)))
    }

    fun onAppOpened(context: Context, packageName: String) {
        initialize(context)
        recorder.recordAppOpened(packageName)
        router.emit(TriggerEvent(TriggerType.APP_OPENED, mapOf("package" to packageName)))
    }

    fun onCycloneRemote(context: Context, payload: Map<String, String>) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.CYCLONE_REMOTE, payload))
    }

    fun onWebSocketEvent(context: Context, eventType: String, payload: Map<String, String>) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.WEBSOCKET, payload + ("eventType" to eventType)))
    }

    fun onCalendarTime(context: Context, payload: Map<String, String>) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.CALENDAR_TIME, payload))
    }

    fun schedule(context: Context, automationId: String, atMillis: Long) {
        initialize(context)
        val intent = Intent(context, AutomationAlarmReceiver::class.java).putExtra("automationId", automationId)
        val pending = PendingIntent.getBroadcast(context, automationId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }

    fun emitScheduled(context: Context, automationId: String) {
        initialize(context)
        router.emit(TriggerEvent(TriggerType.SCHEDULE, mapOf("automationId" to automationId)))
        val automation = store.getAutomation(automationId) ?: return
        val interval = automation.trigger.parameters["intervalMs"]?.toLongOrNull()?.takeIf { it > 0 } ?: return
        schedule(context, automationId, System.currentTimeMillis() + interval)
    }

    private fun seedExamples() {
        if (store.listAutomations().isNotEmpty()) return
        store.saveAutomation(
            AutomationDefinition(
                id = "example-open-settings",
                name = "Open Android Settings",
                description = "Harmless manual example showing a deterministic phone-tool workflow.",
                enabled = true,
                trigger = TriggerDefinition(TriggerType.MANUAL),
                steps = listOf(
                    StepDefinition(
                        id = "open-settings",
                        name = "Open Settings",
                        type = StepType.PHONE_TOOL,
                        parameters = mapOf("tool" to "phone.open_app", "package" to "com.android.settings"),
                        recovery = RecoveryPolicy(maxRetries = 1)
                    )
                )
            )
        )
        store.saveAutomation(
            AutomationDefinition(
                id = "example-work-shift-template",
                name = "Work shift notification template",
                description = "Disabled generic replacement for the old hard-coded shift routine. Configure package, parsing, calendar checks and claim skill before enabling.",
                enabled = false,
                trigger = TriggerDefinition(TriggerType.NOTIFICATION, mapOf("package" to "your.work.app")),
                steps = listOf(
                    StepDefinition("extract-shift", "Extract shift", StepType.REGEX_EXTRACT, mapOf("source" to "${'$'}text", "pattern" to "(\\d{1,2}[:.]\\d{2}\\s*(?:-|–|—|to)\\s*\\d{1,2}[:.]\\d{2})", "group" to "1", "target" to "shift")),
                    StepDefinition("open-work-app", "Open work app", StepType.PHONE_TOOL, mapOf("tool" to "phone.open_notification")),
                    StepDefinition("claim-skill", "Invoke configured claim skill", StepType.INVOKE_SKILL, mapOf("skillId" to "configure-me"), confirmationRequired = true)
                )
            )
        )
    }
}

class AutomationAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("automationId")?.let { AutomationRuntime.emitScheduled(context, it) }
    }
}

class LegacyPhoneToolAdapter(private val context: Context) : PhoneToolGateway {
    override fun execute(request: PhoneToolRequest): PhoneToolResult {
        if (DeviceState.controller != DeviceState.Controller.AGENT) return PhoneToolResult(false, errorCode = "HUMAN_HAS_CONTROL")
        val service = CycloneAccessibilityService.instance
        return when (request.name) {
            "phone.observe" -> if (service == null) unavailable() else PhoneToolResult(true, mapOf("observation" to service.observe().toString()))
            "phone.click" -> {
                if (service == null) unavailable() else {
                    val selector = request.selector
                    val query = selector?.resourceId?.takeIf { false } ?: selector?.text ?: selector?.partialText ?: selector?.contentDescription
                    when {
                        selector?.x != null && selector.y != null -> PhoneToolResult(service.tap(selector.x.toFloat(), selector.y.toFloat()))
                        !query.isNullOrBlank() -> PhoneToolResult(service.clickText(query))
                        else -> PhoneToolResult(false, errorCode = "SELECTOR_NOT_SUPPORTED_BY_LEGACY_ADAPTER")
                    }
                }
            }
            "phone.type", "phone.replace_text" -> {
                if (service == null) unavailable() else {
                    val query = request.selector?.text ?: request.selector?.partialText ?: request.selector?.contentDescription
                    val value = request.arguments["text"] ?: request.arguments["value"] ?: ""
                    if (query.isNullOrBlank()) PhoneToolResult(false, errorCode = "EDITABLE_SELECTOR_REQUIRED") else PhoneToolResult(service.setText(query, value))
                }
            }
            "phone.tap" -> if (service == null) unavailable() else PhoneToolResult(service.tap(request.arguments["x"]?.toFloatOrNull() ?: 0f, request.arguments["y"]?.toFloatOrNull() ?: 0f))
            "phone.scroll" -> if (service == null) unavailable() else PhoneToolResult(service.scrollForward())
            "phone.swipe" -> if (service == null) unavailable() else PhoneToolResult(service.swipe(request.arguments["x1"]?.toFloatOrNull() ?: 0f, request.arguments["y1"]?.toFloatOrNull() ?: 0f, request.arguments["x2"]?.toFloatOrNull() ?: 0f, request.arguments["y2"]?.toFloatOrNull() ?: 0f, request.arguments["durationMs"]?.toLongOrNull() ?: 350))
            "phone.back" -> if (service == null) unavailable() else PhoneToolResult(service.goBack())
            "phone.home" -> if (service == null) unavailable() else PhoneToolResult(service.goHome())
            "phone.get_current_app" -> PhoneToolResult(true, mapOf("package" to DeviceState.currentPackage.orEmpty()))
            "phone.open_app" -> {
                val packageName = request.arguments["package"] ?: return PhoneToolResult(false, errorCode = "PACKAGE_REQUIRED")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?: return PhoneToolResult(false, errorCode = "APP_NOT_FOUND")
                context.startActivity(intent)
                PhoneToolResult(true, mapOf("package" to packageName))
            }
            "phone.open_notification" -> runCatching { DeviceState.latestNotification?.notification?.contentIntent?.send() }
                .fold(onSuccess = { PhoneToolResult(it != null, errorCode = if (it == null) "NO_NOTIFICATION" else null) }, onFailure = { PhoneToolResult(false, errorCode = "OPEN_NOTIFICATION_FAILED", message = it.message) })
            else -> PhoneToolResult(false, errorCode = "PHONE_TOOL_NOT_AVAILABLE_YET", message = "Agent 1 adapter required for ${request.name}")
        }
    }

    private fun unavailable() = PhoneToolResult(false, errorCode = "ACCESSIBILITY_UNAVAILABLE")
}

private object PendingIntegrationGateway : IntegrationGateway {
    override fun refreshObservation(): Boolean = CycloneAccessibilityService.instance?.observe() != null
    override fun goBack(): Boolean = CycloneAccessibilityService.instance?.goBack() == true
    override fun sendCycloneEvent(type: String, payload: Map<String, String>): PhoneToolResult =
        PhoneToolResult(false, errorCode = "AGENT3_CYCLONE_INTEGRATION_REQUIRED", message = "Event '$type' is queued at the Agent 2/3 contract boundary")
}
