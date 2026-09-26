package com.cyclone.mobile.mind.mission

import android.content.Context
import android.os.Build
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.fastpath.InstalledAppInventory
import com.cyclone.mobile.mind.MindApp
import com.cyclone.mobile.mind.MindApproval
import com.cyclone.mobile.mind.MindApprovalReply
import com.cyclone.mobile.mind.MindDevicePort
import com.cyclone.mobile.mind.MindOwnerPort
import com.cyclone.mobile.mind.MindOwnerReply
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindRef
import com.cyclone.mobile.mind.MindSecretOutcome
import com.cyclone.mobile.mind.MindSecretReply
import com.cyclone.mobile.mind.MindValueField
import com.cyclone.mobile.mind.MindValuesOutcome
import com.cyclone.mobile.mind.MindValuesReply
import com.cyclone.mobile.places.PlaceResolver
import com.cyclone.mobile.secrets.SecretFillTarget
import com.cyclone.mobile.secrets.SecretPersona
import com.cyclone.mobile.secrets.SecretRequestMetadata
import com.cyclone.mobile.secrets.SecretUseResult
import com.cyclone.mobile.secrets.SecretUseStatus
import com.cyclone.mobile.secrets.SecretsPhoneFacade
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

internal class AndroidMindDevice(private val context: Context) : MindDevicePort {
    override fun apps(): List<MindApp> {
        if (InstalledAppInventory.snapshot.isEmpty()) InstalledAppInventory.refresh(context)
        return InstalledAppInventory.snapshot.map { MindApp(it.packageName, it.label) }
    }

    override fun now(): String {
        val zone = TimeZone.getDefault()
        return SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).apply { timeZone = zone }.format(Date()) + " (${zone.id})"
    }

    override fun notifications(): List<com.cyclone.mobile.mind.MindNotification> =
        com.cyclone.mobile.DeviceState.notificationSnapshot().filter { it.packageName != context.packageName }.take(30).map { sbn ->
            val extras = sbn.notification.extras
            com.cyclone.mobile.mind.MindNotification(
                key = sbn.key,
                app = sbn.packageName,
                title = extras.getCharSequence("android.title")?.toString().orEmpty().take(200),
                text = extras.getCharSequence("android.text")?.toString().orEmpty().take(500),
                postedAtMs = sbn.postTime,
                actions = sbn.notification.actions.orEmpty().mapNotNull { it.title?.toString() },
                openable = sbn.notification.contentIntent != null,
                replyable = sbn.notification.actions.orEmpty().any { action -> action.remoteInputs.orEmpty().any { it.allowFreeFormInput } },
            )
        }

    override fun blocker(): String? {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        return when {
            power?.isInteractive == false -> "the screen is off"
            keyguard?.isKeyguardLocked == true -> "the phone is locked"
            else -> null
        }
    }

    override fun replyNotification(key: String, text: String): String? {
        val result = com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(
            "mind-reply-${java.util.UUID.randomUUID()}", "phone.reply_notification", org.json.JSONObject().put("key", key).put("text", text)))
        return if (result.ok) null else result.error?.message ?: "The reply could not be sent."
    }

    override fun copy(text: String): Boolean = runCatching {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cyclone draft", text))
        true
    }.getOrDefault(false)

    override fun device(): String {
        val locales = context.resources.configuration.locales
        val languages = (0 until locales.size()).map { locales[it].displayLanguage }.distinct().joinToString(", ")
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}; languages: $languages"
    }
}

/**
 * The owner, reached through the approval card, the Secrets Card and the mission's own requests (in the app, the
 * overlay composer and the task notification). Every wait honours Stop and reports how long the owner took.
 */
internal class AndroidMindOwner(
    private val context: Context,
    private val inbox: OwnerInbox,
    private val missionId: String,
    private val cancelled: () -> Boolean,
    private val onWaiting: (String?) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onPlan: (List<MindPlanStep>) -> Unit,
    /** The owner has the phone: the task card shows "I'm done". Null when Cyclone has it back. */
    private val onHuman: (String?) -> Unit = {},
) : MindOwnerPort {
    /** The overlay and task-card session of this mission; GATE grants are bound to it. */
    private val overlaySession = "mission-$missionId"

    override fun ask(question: String, choices: List<String>, timeoutMs: Long): MindOwnerReply {
        val request = inbox.post(missionId, OwnerRequestKind.QUESTION, question, choices)
        onWaiting(question)
        try {
            val wait = inbox.await(request, timeoutMs, cancelled)
            when (val response = wait.response) {
                is OwnerResponse.Answer -> return MindOwnerReply(response.text.isNotBlank(), response.text.trim(), wait.waitedMs)
                OwnerResponse.Done -> return MindOwnerReply(true, "I did it myself on the phone. Look at the screen again.", wait.waitedMs)
                OwnerResponse.TakeOver -> {
                    onWaiting(null)
                    val handed = takeover("Do this yourself: $question", (timeoutMs - wait.waitedMs).coerceAtLeast(60_000))
                    return MindOwnerReply(handed.answered,
                        if (handed.answered) "I did it myself on the phone and handed it back. Look at the screen again." else "",
                        wait.waitedMs + handed.waitedMs)
                }
                else -> return MindOwnerReply(false, waitedMs = wait.waitedMs)
            }
        } finally {
            onWaiting(null)
        }
    }

    override fun fill(reason: String, fields: List<MindValueField>, timeoutMs: Long): MindValuesReply {
        val request = inbox.post(missionId, OwnerRequestKind.VALUES, reason,
            fields = fields.map { OwnerField(it.label, it.kind, it.choices) })
        onWaiting(reason)
        val started = System.currentTimeMillis()
        try {
            while (true) {
                val waited = System.currentTimeMillis() - started
                when (val reply = inbox.poll(request.id)) {
                    is OwnerResponse.Values -> return MindValuesReply(MindValuesOutcome.FILLED, reply.values, reply.remember, waited)
                    OwnerResponse.Done -> {
                        OverlayChromeRuntime.missionHandBack()
                        return MindValuesReply(MindValuesOutcome.TOOK_OVER, waitedMs = waited)
                    }
                    OwnerResponse.TakeOver -> {
                        inbox.withdraw(request.id)
                        onWaiting(null)
                        val handed = takeover("Fill in: ${fields.joinToString { it.label }}", (timeoutMs - waited).coerceAtLeast(60_000))
                        return MindValuesReply(if (handed.answered) MindValuesOutcome.TOOK_OVER else MindValuesOutcome.TIMED_OUT,
                            waitedMs = waited + handed.waitedMs)
                    }
                    OwnerResponse.Decline -> return MindValuesReply(MindValuesOutcome.DECLINED, waitedMs = waited)
                    else -> Unit
                }
                if (cancelled()) return MindValuesReply(MindValuesOutcome.CANCELLED, waitedMs = waited)
                if (waited > timeoutMs) return MindValuesReply(MindValuesOutcome.TIMED_OUT, waitedMs = waited)
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onWaiting(null)
        }
    }

    override fun awaitApproval(action: String, timeoutMs: Long): MindApprovalReply {
        if (OverlayChromeRuntime.gateWait() != OverlayChromeRuntime.GateWait.PENDING) return MindApprovalReply(MindApproval.NOT_PENDING)
        val request = inbox.post(missionId, OwnerRequestKind.APPROVAL, "Cyclone wants to: $action")
        onWaiting("Approve: $action")
        val started = System.currentTimeMillis()
        try {
            while (true) {
                val waited = System.currentTimeMillis() - started
                if (cancelled()) return MindApprovalReply(MindApproval.CANCELLED, waited)
                when (OverlayChromeRuntime.gateWait()) {
                    OverlayChromeRuntime.GateWait.APPROVED -> return approved(waited)
                    OverlayChromeRuntime.GateWait.NONE -> return MindApprovalReply(if (cancelled()) MindApproval.CANCELLED else MindApproval.DECLINED, waited)
                    OverlayChromeRuntime.GateWait.PENDING -> OverlayChromeRuntime.keepGateChallengeAlive()
                }
                // The mission's request card (app, notification) answers here; the overlay button answers via gateWait().
                when (inbox.poll(request.id)) {
                    OwnerResponse.Approve -> return if (OverlayChromeRuntime.approveGateForMission()) approved(waited)
                        else MindApprovalReply(MindApproval.DECLINED, waited)
                    OwnerResponse.Decline -> {
                        OverlayChromeRuntime.declineGateForMission()
                        return MindApprovalReply(MindApproval.DECLINED, waited)
                    }
                    else -> Unit
                }
                if (waited >= timeoutMs) {
                    OverlayChromeRuntime.declineGateForMission()
                    return MindApprovalReply(MindApproval.TIMED_OUT, waited)
                }
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onWaiting(null)
        }
    }

    private fun approved(waited: Long): MindApprovalReply {
        DeviceState.setController(DeviceState.Controller.AGENT)
        OverlayChromeRuntime.missionWorking(overlaySession, "Approved")
        return MindApprovalReply(MindApproval.APPROVED, waited)
    }

    override fun fillSecret(page: AgentPageCard, target: MindRef, slot: String, reason: String, timeoutMs: Long): MindSecretReply {
        val place = PlaceResolver.resolveCurrent(page) ?: return MindSecretReply(MindSecretOutcome.UNAVAILABLE,
            detail = "Cyclone cannot identify this app or website yet (for a website, the address bar must be visible)")
        val metadata = runCatching { SecretRequestMetadata(place.id, SecretPersona.LIVE, slot, reason) }.getOrElse {
            return MindSecretReply(MindSecretOutcome.UNAVAILABLE, detail = "the request could not be described safely")
        }
        val fillTarget = runCatching { SecretFillTarget(target.elementId, target.observationId, page.sessionId, page.displayId) }.getOrElse {
            return MindSecretReply(MindSecretOutcome.FAILED, detail = "the field is not addressable")
        }
        val result = AtomicReference<SecretUseResult?>(null)
        val request = inbox.post(missionId, OwnerRequestKind.SECRET, "Fill ${target.label} for ${place.origin ?: place.id.substringAfter(':')}")
        onWaiting("Secure input: ${target.label}")
        val started = System.currentTimeMillis()
        try {
            SecretsPhoneFacade.requestForRun(context, metadata, fillTarget) { result.set(it) }
            while (result.get() == null) {
                if (cancelled()) return MindSecretReply(MindSecretOutcome.DECLINED, System.currentTimeMillis() - started)
                if (System.currentTimeMillis() - started > timeoutMs) return MindSecretReply(MindSecretOutcome.TIMED_OUT, timeoutMs)
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onWaiting(null)
        }
        val use = result.get()!!
        val waited = System.currentTimeMillis() - started
        DeviceState.setController(DeviceState.Controller.AGENT)
        return when (use.status) {
            SecretUseStatus.FILLED -> MindSecretReply(if (use.verified) MindSecretOutcome.FILLED else MindSecretOutcome.FAILED, waited,
                if (use.verified) "" else "the field did not accept the value")
            SecretUseStatus.SKIPPED, SecretUseStatus.CANCELLED -> MindSecretReply(MindSecretOutcome.DECLINED, waited)
            SecretUseStatus.MISSING -> MindSecretReply(MindSecretOutcome.MISSING, waited)
            SecretUseStatus.STORED -> MindSecretReply(MindSecretOutcome.FAILED, waited, "the value was saved but not filled")
            SecretUseStatus.FAILED, SecretUseStatus.ALREADY_USED -> MindSecretReply(MindSecretOutcome.FAILED, waited, use.errorCode.orEmpty())
        }
    }

    override fun takeover(instruction: String, timeoutMs: Long): MindOwnerReply {
        val request = inbox.post(missionId, OwnerRequestKind.CONTROL, instruction)
        onHuman(instruction)
        OverlayChromeRuntime.missionHandoff()
        val started = System.currentTimeMillis()
        try {
            // Give the owner a moment to take the phone before the controller state can count as "handed back".
            Thread.sleep(1_500)
            while (true) {
                val waited = System.currentTimeMillis() - started
                val reply = inbox.poll(request.id)
                if (reply == OwnerResponse.Done || reply is OwnerResponse.Answer) {
                    OverlayChromeRuntime.missionHandBack()
                    OverlayChromeRuntime.missionWorking(overlaySession)
                    return MindOwnerReply(true, (reply as? OwnerResponse.Answer)?.text.orEmpty(), waited)
                }
                if (DeviceState.controller == DeviceState.Controller.AGENT) {
                    OverlayChromeRuntime.missionWorking(overlaySession)
                    return MindOwnerReply(true, waitedMs = waited)
                }
                if (cancelled() || waited > timeoutMs) return MindOwnerReply(false, waitedMs = waited)
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onHuman(null)
        }
    }

    override fun awaitControl(timeoutMs: Long): MindOwnerReply {
        val request = inbox.post(missionId, OwnerRequestKind.CONTROL, "You have control of the phone. Hand it back when you are done.")
        onHuman("You have control of the phone. Tap I'm done when you are finished.")
        val started = System.currentTimeMillis()
        try {
            while (true) {
                val waited = System.currentTimeMillis() - started
                if (DeviceState.controller == DeviceState.Controller.AGENT) return MindOwnerReply(true, waitedMs = waited)
                if (cancelled() || waited > timeoutMs) return MindOwnerReply(false, waitedMs = waited)
                if (inbox.poll(request.id) == OwnerResponse.Done) {
                    DeviceState.setController(DeviceState.Controller.AGENT)
                    OverlayChromeRuntime.missionWorking(overlaySession)
                    return MindOwnerReply(true, waitedMs = waited)
                }
                Thread.sleep(POLL_MS)
            }
        } finally {
            inbox.withdraw(request.id)
            onHuman(null)
        }
    }

    override fun status(text: String) = onStatus(text)
    override fun plan(steps: List<MindPlanStep>) = onPlan(steps)

    private companion object {
        const val POLL_MS = 300L
    }
}
